package com.shitulelv.aicollab.acceptance;

import java.nio.file.*;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit local offline-copy rehearsal only; cannot point at an arbitrary database. */
public final class AcceptanceDatabaseSupport {
    private AcceptanceDatabaseSupport() {}
    public static Properties localProperties() throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(Path.of("../.env"))) { properties.load(input); }
        return properties;
    }
    public static DriverManagerDataSource dataSource() throws Exception {
        if (!"true".equals(System.getenv("AI_UPGRADE_REHEARSAL")))
            throw new IllegalStateException("Explicit offline-copy rehearsal opt-in required");
        var properties = localProperties();
        return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/" + properties.getProperty("POSTGRES_DB"),
                properties.getProperty("POSTGRES_USER"), properties.getProperty("POSTGRES_PASSWORD"));
    }
    public static JdbcTemplate jdbc() throws Exception { return new JdbcTemplate(dataSource()); }
}
