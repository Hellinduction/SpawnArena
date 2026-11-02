package me.zeronull.spawnarena;

import org.bukkit.Bukkit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

/**
 * MULTI crystal arenas' copy worlds are new worlds the server's own combat logger (Cuy/Economy) doesn't know: they're
 * added to its list of worlds it leaves alone (combatLoggerWorldsFile, the list under combatLoggerWorldsSection), and
 * it's reloaded (combatLoggerWorldsReload). Only the missing names are added, as new lines at the end of that list, so
 * the rest of the file (comments, layout) stays exactly as it was; a copy of the file is kept first.
 */
final class CombatLoggerWorlds {
    private CombatLoggerWorlds() {}

    /** Makes sure these worlds are on the list; reloads the plugin if any had to be added. */
    static void include(final Collection<String> worlds) {
        if (SpawnArena.settings == null || worlds.isEmpty())
            return;
        final String path = SpawnArena.settings.combatLoggerWorldsFile;
        final String section = SpawnArena.settings.combatLoggerWorldsSection;
        if (path == null || path.isBlank() || section == null || section.isBlank())
            return;
        final File file = new File(Bukkit.getWorldContainer(), path);
        if (!file.isFile())
            return; // that plugin isn't on this server
        try {
            final String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            // Written back with the file's own line endings (Windows or not), so nothing else in it changes
            final String newline = text.contains("\r\n") ? "\r\n" : "\n";
            final boolean endsWithNewline = text.endsWith("\n");
            final List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
            if (endsWithNewline)
                lines.remove(lines.size() - 1);
            final int list = findList(lines, section.split("\\."));
            if (list < 0) {
                SpawnArena.INSTANCE.getLogger().warning("Couldn't find " + section + " in " + path
                        + ": add the crystal arena copy worlds " + worlds + " to it yourself.");
                return;
            }
            // The list's items: "  - name" lines right under it
            final String itemIndent = indentOf(lines.get(list)) + "  ";
            int end = list + 1;
            final List<String> present = new ArrayList<>();
            while (end < lines.size() && lines.get(end).trim().startsWith("- ")) {
                present.add(lines.get(end).trim().substring(2).trim().replace("'", "").replace("\"", ""));
                end++;
            }
            final String itemPrefix = end > list + 1 ? lines.get(list + 1).substring(0, lines.get(list + 1).indexOf('-')) : itemIndent;
            final List<String> added = new ArrayList<>();
            for (final String world : worlds)
                if (present.stream().noneMatch(p -> p.equalsIgnoreCase(world)))
                    added.add(world);
            if (added.isEmpty())
                return;
            int at = end;
            for (final String world : added)
                lines.add(at++, itemPrefix + "- " + world);

            final File backup = new File(file.getParentFile(), file.getName() + ".bak-spawnarena-" + System.currentTimeMillis());
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            final File tmp = new File(file.getParentFile(), file.getName() + ".spawnarena-tmp");
            Files.writeString(tmp.toPath(), String.join(newline, lines) + (endsWithNewline ? newline : ""), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            SpawnArena.INSTANCE.getLogger().info("Added " + added + " to " + section + " in " + path + " (old copy: " + backup.getName() + ")");

            final String reload = SpawnArena.settings.combatLoggerWorldsReload;
            if (reload != null && !reload.isBlank())
                Threads.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reload.startsWith("/") ? reload.substring(1) : reload));
        } catch (final Exception e) {
            SpawnArena.INSTANCE.getLogger().log(Level.WARNING, "Couldn't add the crystal arena copy worlds to " + path, e);
        }
    }

    /** The line of the last key of {@code keys} (nested YAML keys, each indented under the one before), or -1. */
    private static int findList(final List<String> lines, final String[] keys) {
        int from = 0;
        int parentIndent = -1;
        int found = -1;
        for (final String key : keys) {
            found = -1;
            for (int i = from; i < lines.size(); i++) {
                final String line = lines.get(i);
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#"))
                    continue;
                final int indent = indentOf(line).length();
                if (parentIndent >= 0 && indent <= parentIndent)
                    break; // out of the parent's section
                if (trimmed.equals(key + ":") || trimmed.startsWith(key + ": ") && trimmed.substring(key.length() + 1).trim().startsWith("#")) {
                    found = i;
                    parentIndent = indent;
                    from = i + 1;
                    break;
                }
            }
            if (found < 0)
                return -1;
        }
        return found;
    }

    private static String indentOf(final String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ')
            i++;
        return line.substring(0, i);
    }
}
