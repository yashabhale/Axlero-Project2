package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SqliteLogSink implements LogSink {
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Connection connection;

    public SqliteLogSink(Path databasePath) throws IOException, SQLException {
        Objects.requireNonNull(databasePath, "databasePath");
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + absolutePath);
        try {
            configureConnection();
            initializeSchema();
            connection.setAutoCommit(false);
        } catch (IOException | SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    @Override
    public void accept(LogMessage logMessage) {
        acceptAll(List.of(logMessage));
    }

    @Override
    public synchronized void acceptAll(List<LogMessage> logMessages) {
        Objects.requireNonNull(logMessages, "logMessages");
        if (logMessages.isEmpty()) {
            return;
        }

        try (PreparedStatement insertLog = connection.prepareStatement(
                     "INSERT INTO log_events (timestamp_ms, level, service, message, response_time_ms) "
                             + "VALUES (?, ?, ?, ?, ?)");
             Statement lastInsertId = connection.createStatement();
             PreparedStatement insertTag = connection.prepareStatement(
                     "INSERT INTO log_tags (log_id, tag_key, tag_value) VALUES (?, ?, ?)")) {
            for (LogMessage logMessage : logMessages) {
                insertLog.setLong(1, logMessage.getTimestamp());
                insertLog.setString(2, logMessage.getLevel().name());
                insertLog.setString(3, logMessage.getService());
                insertLog.setString(4, logMessage.getMessage());
                insertLog.setInt(5, logMessage.getResponseTime());
                insertLog.executeUpdate();

                long logId;
                try (ResultSet result = lastInsertId.executeQuery("SELECT last_insert_rowid()")) {
                    if (!result.next()) {
                        throw new SQLException("SQLite did not return the inserted log ID");
                    }
                    logId = result.getLong(1);
                }

                for (var tag : logMessage.getTagsMap().entrySet()) {
                    insertTag.setLong(1, logId);
                    insertTag.setString(2, tag.getKey());
                    insertTag.setString(3, tag.getValue());
                    insertTag.addBatch();
                }
                insertTag.executeBatch();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw new IllegalStateException("Failed to persist log batch", exception);
        }
    }

    @Override
    public synchronized List<LogMessage> queryLogs(LogQuery query) {
        Objects.requireNonNull(query, "query");
        StringBuilder sql = new StringBuilder(
                "SELECT page.id, page.timestamp_ms, page.level, page.service, page.message, "
                        + "page.response_time_ms, tags.tag_key, tags.tag_value "
                        + "FROM (SELECT id, timestamp_ms, level, service, message, response_time_ms "
                        + "FROM log_events");
        List<Object> parameters = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (query.level() != null) {
            conditions.add("level = ?");
            parameters.add(query.level().name());
        }
        if (query.service() != null) {
            conditions.add("service = ?");
            parameters.add(query.service());
        }
        if (query.startTimestampMs() != null) {
            conditions.add("timestamp_ms >= ?");
            parameters.add(query.startTimestampMs());
        }
        if (query.endTimestampMs() != null) {
            conditions.add("timestamp_ms < ?");
            parameters.add(query.endTimestampMs());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY timestamp_ms ASC, id ASC LIMIT ? OFFSET ?) AS page ")
                .append("LEFT JOIN log_tags AS tags ON tags.log_id = page.id ")
                .append("ORDER BY page.timestamp_ms ASC, page.id ASC, tags.tag_key ASC");
        parameters.add(query.limit());
        parameters.add(query.offset());

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet results = statement.executeQuery()) {
                List<LogMessage> logs = new ArrayList<>();
                LogMessage.Builder currentLog = null;
                long currentId = -1;
                while (results.next()) {
                    long logId = results.getLong("id");
                    if (currentLog == null || logId != currentId) {
                        if (currentLog != null) {
                            logs.add(currentLog.build());
                        }
                        currentId = logId;
                        currentLog = LogMessage.newBuilder()
                                .setTimestamp(results.getLong("timestamp_ms"))
                                .setLevel(LogLevel.valueOf(results.getString("level")))
                                .setService(results.getString("service"))
                                .setMessage(results.getString("message"))
                                .setResponseTime(results.getInt("response_time_ms"));
                    }
                    String tagKey = results.getString("tag_key");
                    if (tagKey != null) {
                        currentLog.putTags(tagKey, results.getString("tag_value"));
                    }
                }
                if (currentLog != null) {
                    logs.add(currentLog.build());
                }
                return List.copyOf(logs);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query logs", exception);
        }
    }

    @Override
    public synchronized List<TimeSeriesBucket> countLogsByMinute(long startTimestampMs, long endTimestampMs) {
        String sql = "SELECT (timestamp_ms / 60000) * 60000 AS minute_start_ms, COUNT(*) AS log_count "
                + "FROM log_events WHERE timestamp_ms >= ? AND timestamp_ms < ? "
                + "GROUP BY minute_start_ms ORDER BY minute_start_ms";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setLong(1, startTimestampMs);
            query.setLong(2, endTimestampMs);
            try (ResultSet results = query.executeQuery()) {
                List<TimeSeriesBucket> buckets = new ArrayList<>();
                while (results.next()) {
                    buckets.add(new TimeSeriesBucket(
                            results.getLong("minute_start_ms"),
                            results.getLong("log_count")));
                }
                return buckets;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to aggregate log counts by minute", exception);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to close SQLite log database", exception);
        }
    }

    private void configureConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        }
    }

    private void initializeSchema() throws IOException, SQLException {
        try (InputStream schemaStream = SqliteLogSink.class.getResourceAsStream("/db/schema.sql")) {
            if (schemaStream == null) {
                throw new IOException("Database schema resource /db/schema.sql was not found");
            }
            String schema = new String(schemaStream.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }
}
