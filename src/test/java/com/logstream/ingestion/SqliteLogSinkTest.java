package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteLogSinkTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsLogsAndTagsAcrossConnections() throws Exception {
        Path databasePath = tempDirectory.resolve("logs.db");
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            sink.accept(log("api", "first").toBuilder()
                    .putTags("environment", "test")
                    .putTags("region", "west")
                    .build());
            sink.accept(log("worker", "second"));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var logQuery = connection.createStatement();
             ResultSet logs = logQuery.executeQuery(
                     "SELECT service, level, message, response_time_ms FROM log_events ORDER BY id")) {
            assertTrue(logs.next());
            assertEquals("api", logs.getString("service"));
            assertEquals("INFO", logs.getString("level"));
            assertEquals("first", logs.getString("message"));
            assertEquals(42, logs.getInt("response_time_ms"));
            assertTrue(logs.next());
            assertEquals("worker", logs.getString("service"));
            assertEquals("second", logs.getString("message"));
            assertFalse(logs.next());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet tags = statement.executeQuery(
                     "SELECT tag_key, tag_value FROM log_tags ORDER BY tag_key")) {
            assertTrue(tags.next());
            assertEquals("environment", tags.getString("tag_key"));
            assertEquals("test", tags.getString("tag_value"));
            assertTrue(tags.next());
            assertEquals("region", tags.getString("tag_key"));
            assertEquals("west", tags.getString("tag_value"));
            assertFalse(tags.next());
        }
    }

    @Test
    void rollsBackEntireBatchWhenOneLogViolatesSchema() throws Exception {
        Path databasePath = tempDirectory.resolve("logs.db");
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            LogMessage invalidLog = log(" ", "invalid");
            assertThrows(IllegalStateException.class,
                    () -> sink.acceptAll(List.of(log("api", "valid"), invalidLog)));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet logs = statement.executeQuery("SELECT COUNT(*) FROM log_events")) {
            logs.next();
            assertEquals(0, logs.getInt(1));
        }
    }

    private static LogMessage log(String service, String message) {
        return LogMessage.newBuilder()
                .setTimestamp(1_730_000_000_000L)
                .setLevel(LogLevel.INFO)
                .setService(service)
                .setMessage(message)
                .setResponseTime(42)
                .build();
    }
}
