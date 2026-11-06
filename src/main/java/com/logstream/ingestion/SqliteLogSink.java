package com.logstream.ingestion;

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
