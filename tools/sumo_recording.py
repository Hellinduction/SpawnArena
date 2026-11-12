"""Reads SpawnArena's sumo, spleef and crystal match recordings (plugins/SpawnArena/recordings/<date>/*.sarec.gz).

As a module:
    from sumo_recording import read
    match = read("recordings/2026-11-05/130512_sumo_Alice-Bob.sarec.gz")
    match["fighters"][0]["frames"][0]  # {"ms": 0, "x": ..., "forward": True, "swung": False, ...}

From the command line, a summary as JSON:
    python sumo_recording.py <file.sarec.gz> [--json]

The format is described in MatchRecorder.write (the plugin's source).
"""
import gzip
import json
import struct
import sys
import uuid

KEYS = ["forward", "back", "left", "right", "jump", "sneak", "sprint"]
STATE = ["on_ground", "sprinting", "sneaking", "swung", "landed_hit", "was_hit"]
# Format 2's "more" byte (the velocity and who they hit are read separately)
MORE = ["sent_velocity", "digging", "shot", "teleported", "landed_on_fighter"]
# Format 3's block changes, and format 4's events
HOW = {0: "hand", 1: "snowball", 2: "placed", 3: "exploded"}
EVENTS = {1: "equip", 2: "totem", 3: "crystal_placed", 4: "crystal_hit", 5: "crystal_gone", 6: "health"}
SLOTS = ["main_hand", "off_hand", "feet", "legs", "chest", "head"]
FRAME = struct.Struct(">ifffffBB")
FRAME_2 = struct.Struct(">hB")


class _Reader:
    def __init__(self, data):
        self.data, self.at = data, 0

    def take(self, fmt):
        values = struct.unpack_from(">" + fmt, self.data, self.at)
        self.at += struct.calcsize(">" + fmt)
        return values if len(values) > 1 else values[0]

    def text(self):
        n = self.take("H")
        s = self.data[self.at:self.at + n].decode("utf-8", "replace")
        self.at += n
        return s


def read(path):
    with gzip.open(path, "rb") as f:
        r = _Reader(f.read())
    if r.data[:5] != b"SAREC":
        raise ValueError("not a SpawnArena recording")
    r.at = 5
    version = r.take("i")
    if version not in (1, 2, 3, 4):
        raise ValueError("unknown recording format %d" % version)
    match = {"format": version, "started": r.take("q"), "arena": r.text(), "variant": r.text() or None,
             "centre": [r.take("d"), r.take("d"), r.take("d")], "length_ms": r.take("i"),
             "duel": bool(r.take("b")), "winning_team": r.take("b"), "fighters": []}
    # Format 3: a spleef match, a free-for-all round (each fighter a team of their own)
    flags = r.take("B") if version >= 3 else 0
    match["spleef"], match["free_for_all"], match["crystal"] = bool(flags & 1), bool(flags & 2), bool(flags & 4)
    for _ in range(r.take("b")):
        most, least = r.take("q"), r.take("q")
        fighter = {"uuid": str(uuid.UUID(int=((most & (2**64 - 1)) << 64) | (least & (2**64 - 1)))),
                   "name": r.text(), "team": r.take("b"), "won": bool(r.take("b"))}
        # Their stats in that arena as the match started
        for stat in ["elo", "wins", "losses", "win_streak", "best_win_streak", "fight_seconds"]:
            fighter[stat] = r.take("i")
        fighter["bots_beaten"] = {}
        for _ in range(r.take("b")):
            level = r.text()
            fighter["bots_beaten"][level] = r.take("i")
        fighter["ping_at_start"], fighter["ping_average"] = r.take("i"), r.take("i")
        fighter["client"] = r.text()
        match["fighters"].append(fighter)
    for fighter in match["fighters"]:
        frames = []
        for _ in range(r.take("i")):
            ms, x, y, z, yaw, pitch, keys, state = FRAME.unpack_from(r.data, r.at)
            r.at += FRAME.size
            frame = {"ms": ms, "x": x, "y": y, "z": z, "yaw": yaw, "pitch": pitch}
            frame.update({k: bool(keys >> i & 1) for i, k in enumerate(KEYS)})
            frame.update({k: bool(state >> i & 1) for i, k in enumerate(STATE)})
            if version >= 2:
                # Their ping, and what else happened since the frame before: the velocity sent (knockback), holding the
                # attack button on a block, hit by an arrow/snowball/rod, teleported, and which fighter they hit
                frame["ping"], more = FRAME_2.unpack_from(r.data, r.at)
                r.at += FRAME_2.size
                frame.update({k: bool(more >> i & 1) for i, k in enumerate(MORE)})
                frame["velocity"] = [r.take("f"), r.take("f"), r.take("f")] if more & 1 else None
                frame["hit_fighter"] = r.take("b") if more & 16 else None
            frames.append(frame)
        fighter["frames"] = frames
    # Format 3: blocks changed (spleef's floor broken): when, where (from the centre's block), by which fighter
    # (-1 none), how (0 by hand, 1 snowball), what it became
    match["blocks"] = []
    if version >= 3:
        for _ in range(r.take("i")):
            ms, x, y, z, by, how = r.take("iiiibb")
            match["blocks"].append({"ms": ms, "x": x, "y": y, "z": z, "by": by,
                                    "how": HOW.get(how, how), "became": r.text()})
    # Format 4: events (see MatchRecorder.write): equipment, totems, crystals, health
    match["events"] = []
    if version >= 4:
        for _ in range(r.take("i")):
            ms, fighter, kind, n = r.take("ibbH")
            d = _Reader(r.data[r.at:r.at + n])
            r.at += n
            event = {"ms": ms, "fighter": fighter, "kind": EVENTS.get(kind, kind)}
            if kind == 1:
                event.update({"slot": SLOTS[d.take("b")], "item": d.text(), "enchanted": bool(d.take("b"))})
            elif kind == 3:
                event.update({"crystal": d.take("i"), "x": d.take("f"), "y": d.take("f"), "z": d.take("f")})
            elif kind in (4, 5):
                event["crystal"] = d.take("i")
            elif kind == 6:
                event["health"] = d.take("f")
            match["events"].append(event)
    return match


def summary(match):
    out = {k: v for k, v in match.items() if k not in ("fighters", "blocks", "events")}
    out["blocks"] = len(match.get("blocks", []))
    out["events"] = {}
    for e in match.get("events", []):
        out["events"][e["kind"]] = out["events"].get(e["kind"], 0) + 1
    out["format"] = match.get("format", 1)
    out["winners"] = [f["name"] for f in match["fighters"] if f["won"]]
    out["fighters"] = []
    for f in match["fighters"]:
        frames = f["frames"]
        s = {k: v for k, v in f.items() if k != "frames"}
        s["frames"] = len(frames)
        for k in KEYS + STATE + MORE:
            s[k] = sum(fr.get(k, False) for fr in frames)
        s["furthest"] = round(max((abs(fr["x"]) + abs(fr["z"]) for fr in frames), default=0), 2)
        me = match["fighters"].index(f)
        s["broke_by_hand"] = sum(1 for b in match.get("blocks", []) if b["by"] == me and b["how"] == "hand")
        s["broke_by_snowball"] = sum(1 for b in match.get("blocks", []) if b["by"] == me and b["how"] == "snowball")
        s["placed_blocks"] = sum(1 for b in match.get("blocks", []) if b["by"] == me and b["how"] == "placed")
        mine = [e for e in match.get("events", []) if e["fighter"] == me]
        for kind in ("crystal_placed", "crystal_hit", "totem", "equip", "health"):
            s[kind] = sum(1 for e in mine if e["kind"] == kind)
        s["held"] = sorted({e["item"] for e in mine if e["kind"] == "equip" and e["slot"] == "main_hand"})
        s["wore"] = sorted({e["item"] for e in mine if e["kind"] == "equip" and e["slot"] in ("feet", "legs", "chest", "head", "off_hand") and e["item"] != "minecraft:air"})
        out["fighters"].append(s)
    return out


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print(json.dumps(summary(read(sys.argv[1])), indent=None if "--json" in sys.argv else 2))
