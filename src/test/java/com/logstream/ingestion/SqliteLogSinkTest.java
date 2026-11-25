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
            sink.accept(log("worker", "second").toBuilder()
                    .setLevel(LogLevel.ERROR)
                    .build());
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
            assertEquals("ERROR", logs.getString("level"));
            assertEquals("second", logs.getString("message"));
            assertFalse(logs.next());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet tags = statement.executeQuery(
                     "SELECT log_events.service, tag_key, tag_value "
                             + "FROM log_tags JOIN log_events ON log_tags.log_id = log_events.id "
                             + "ORDER BY tag_key")) {
            assertTrue(tags.next());
            assertEquals("api", tags.getString("service"));
            assertEquals("environment", tags.getString("tag_key"));
            assertEquals("test", tags.getString("tag_value"));
            assertTrue(tags.next());
            assertEquals("api", tags.getString("service"));
            assertEquals("region", tags.getString("tag_key"));
            assertEquals("west", tags.getString("tag_value"));
            assertFalse(tags.next());
        }
    }

    @Test
    void emptyBatchDoesNotPersistRecordsOrPreventLaterWrites() throws Exception {
        Path databasePath = tempDirectory.resolve("empty-batch.db");
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            sink.acceptAll(List.of());
            sink.accept(log("api", "after empty batch"));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet logs = statement.executeQuery(
                     "SELECT service, message FROM log_events ORDER BY id")) {
            assertTrue(logs.next());
            assertEquals("api", logs.getString("service"));
            assertEquals("after empty batch", logs.getString("message"));
            assertFalse(logs.next());
        }
    }

    @Test
    void persistsSpecialCharactersInMessagesAndTags() throws Exception {
        Path databasePath = tempDirectory.resolve("special-characters.db");
        String message = "can't parse 'x'; keep -- as text\nsecond line";
        String tagKey = "owner's team";
        String tagValue = "ops; west = 'blue'";

        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            sink.accept(log("api", message).toBuilder()
                    .putTags(tagKey, tagValue)
                    .build());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet logs = statement.executeQuery(
                     "SELECT log_events.message, log_tags.tag_key, log_tags.tag_value "
                             + "FROM log_events JOIN log_tags ON log_events.id = log_tags.log_id")) {
            assertTrue(logs.next());
            assertEquals(message, logs.getString("message"));
            assertEquals(tagKey, logs.getString("tag_key"));
            assertEquals(tagValue, logs.getString("tag_value"));
            assertFalse(logs.next());
        }
    }

    @Test
    void rollsBackEntireBatchWhenOneLogViolatesSchema() throws Exception {
        Path databasePath = tempDirectory.resolve("logs.db");
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            LogMessage invalidLog = log(" ", "invalid");
            LogMessage validLog = log("api", "valid").toBuilder()
                    .putTags("environment", "test")
                    .build();
            assertThrows(IllegalStateException.class,
                    () -> sink.acceptAll(List.of(validLog, invalidLog)));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet counts = statement.executeQuery(
                     "SELECT (SELECT COUNT(*) FROM log_events) AS log_count, "
                             + "(SELECT COUNT(*) FROM log_tags) AS tag_count")) {
            assertTrue(counts.next());
            assertEquals(0, counts.getInt("log_count"));
            assertEquals(0, counts.getInt("tag_count"));
        }
    }

    @Test
    void rollbackPreservesPreviouslyCommittedLogs() throws Exception {
        Path databasePath = tempDirectory.resolve("preserve-committed.db");
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            sink.accept(log("api", "already committed"));
            LogMessage validLog = log("worker", "should roll back");
            LogMessage invalidLog = log(" ", "invalid");

            assertThrows(IllegalStateException.class,
                    () -> sink.acceptAll(List.of(validLog, invalidLog)));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement();
             ResultSet logs = statement.executeQuery(
                     "SELECT service, message FROM log_events ORDER BY id")) {
            assertTrue(logs.next());
            assertEquals("api", logs.getString("service"));
            assertEquals("already committed", logs.getString("message"));
            assertFalse(logs.next());
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
    void aggregatesMultipleMinutesWithHalfOpenBoundariesAndChronologicalOrdering() throws Exception {
        Path databasePath = tempDirectory.resolve("boundary-aggregation.db");
        long firstMinute = 1_730_000_000_000L / 60_000 * 60_000;
        long start = firstMinute + 10;
        long end = firstMinute + 120_000;
        try (SqliteLogSink sink = new SqliteLogSink(databasePath)) {
            sink.accept(logAt("excluded-before", "before start", start - 1));
            sink.accept(logAt("api", "at start", start));
            sink.accept(logAt("worker", "same timestamp", start).toBuilder()
                    .setLevel(LogLevel.ERROR)
                    .build());
            sink.accept(logAt("api", "before minute boundary", firstMinute + 59_999)
                    .toBuilder()
                    .setLevel(LogLevel.WARN)
                    .build());
            sink.accept(logAt("worker", "at minute boundary", firstMinute + 60_000)
                    .toBuilder()
                    .setLevel(LogLevel.DEBUG)
                    .build());
            sink.accept(logAt("scheduler", "before end", end - 1));
            sink.accept(logAt("excluded-at-end", "at end", end));
            sink.accept(logAt("excluded-after", "after end", end + 1));

            List<TimeSeriesBucket> buckets = sink.countLogsByMinute(start, end);

            assertEquals(2, buckets.size());
            assertEquals(firstMinute, buckets.get(0).minuteStartMs());
            assertEquals(3, buckets.get(0).count());
            assertEquals(firstMinute + 60_000, buckets.get(1).minuteStartMs());
            assertEquals(2, buckets.get(1).count());
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
