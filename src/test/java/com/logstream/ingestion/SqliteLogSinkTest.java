package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogCountRequest;
import com.logstream.ingestion.v1.LogCountResponse;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void aggregatesLogCountsIntoMinuteBucketsWithinRequestedRange() throws Exception {
        Path databasePath = tempDirectory.resolve("logs.db");
        long start = 1_730_000_000_000L;
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            sink.accept(logAt("api", "first", start + 1_000));
            sink.accept(logAt("worker", "second", start + 20_000));
            sink.accept(logAt("api", "next minute", start + 61_000));

            List<TimeSeriesBucket> buckets = sink.countLogsByMinute(start, start + 60_000);

            assertEquals(1, buckets.size());
            assertEquals(start / 60_000 * 60_000, buckets.get(0).minuteStartMs());
            assertEquals(2, buckets.get(0).count());
        }
    }

    @Test
    void grpcAggregationReturnsZeroCountBucketsForEmptyMinutes() throws Exception {
        long start = 1_730_000_000_000L / 60_000 * 60_000;
        try (SqliteLogSink sink = new SqliteLogSink(tempDirectory.resolve("logs.db"))) {
            sink.accept(logAt("api", "first", start + 1_000));
            List<LogCountResponse> responses = new ArrayList<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            StreamObserver<LogCountResponse> observer = new StreamObserver<>() {
                @Override
                public void onNext(LogCountResponse response) {
                    responses.add(response);
                }

                @Override
                public void onError(Throwable throwable) {
                    error.set(throwable);
                }

                @Override
                public void onCompleted() {
                }
            };

            new LogIngestionServiceImpl(sink).getLogCountsByMinute(
                    LogCountRequest.newBuilder()
                            .setStartTimestampMs(start)
                            .setEndTimestampMs(start + 120_000)
                            .build(),
                    observer);

            assertNull(error.get());
            assertEquals(2, responses.get(0).getBucketsCount());
            assertEquals(1, responses.get(0).getBuckets(0).getCount());
            assertEquals(0, responses.get(0).getBuckets(1).getCount());
        }
    }

    private static LogMessage log(String service, String message) {
        return logAt(service, message, 1_730_000_000_000L);
    }

    private static LogMessage logAt(String service, String message, long timestamp) {
        return LogMessage.newBuilder()
                .setTimestamp(timestamp)
                .setLevel(LogLevel.INFO)
                .setService(service)
                .setMessage(message)
                .setResponseTime(42)
                .build();
    }
}
