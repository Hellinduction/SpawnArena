package me.zeronull.spawnarena.config.impl;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.YamlConfigurationProperties;

@Configuration
public final class DatabaseConfig {

    public static final YamlConfigurationProperties PROPERTIES = YamlConfigurationProperties.newBuilder().build();

    @Comment("Database backend: H2 (embedded) or MYSQL")
    public String type = "H2";

    @Comment("MySQL connection settings — ignored when type is H2")
    public String host = "localhost";
    public int port = 3306;
    public String name = "spawnarena";
    public String username = "root";
    public String password = "";

    @Comment("HikariCP pool sizes")
    public int minimumIdle = 1;
    public int maximumPoolSize = 5;
}
