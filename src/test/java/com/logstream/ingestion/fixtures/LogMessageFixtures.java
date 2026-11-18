package com.logstream.ingestion.fixtures;

import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Factory methods for deterministic protobuf log messages used in tests.
 */
public final class LogMessageFixtures {
    private static final long DEFAULT_TIMESTAMP_MS = 1_730_000_000_000L;
    private static final LogLevel DEFAULT_LEVEL = LogLevel.INFO;
    private static final String DEFAULT_SERVICE = "test-service";
    private static final String DEFAULT_MESSAGE = "A deterministic test log";
    private static final int DEFAULT_RESPONSE_TIME_MS = 42;

    private LogMessageFixtures() {
    }

    /**
     * Creates a valid log with the fixture's default field values.
     *
     * @return a valid deterministic log message
     */
    public static LogMessage validLog() {
        return create(
                DEFAULT_TIMESTAMP_MS,
                DEFAULT_LEVEL,
                DEFAULT_SERVICE,
                DEFAULT_MESSAGE,
                DEFAULT_RESPONSE_TIME_MS,
                Map.of());
    }

    /**
     * Creates a valid default log with the specified timestamp.
     *
     * @param timestamp timestamp in milliseconds
     * @return a valid log message
     */
    public static LogMessage validLogAt(long timestamp) {
        return create(
                timestamp,
                DEFAULT_LEVEL,
                DEFAULT_SERVICE,
                DEFAULT_MESSAGE,
                DEFAULT_RESPONSE_TIME_MS,
                Map.of());
    }

    /**
     * Creates a valid default log with the specified level.
     *
     * @param level log level
     * @return a valid log message
     */
    public static LogMessage validLogWithLevel(LogLevel level) {
        return create(
                DEFAULT_TIMESTAMP_MS,
                level,
                DEFAULT_SERVICE,
                DEFAULT_MESSAGE,
                DEFAULT_RESPONSE_TIME_MS,
                Map.of());
    }

    /**
     * Builds a protobuf message without validating its fields, allowing tests to create invalid inputs.
     *
     * @param timestamp timestamp in milliseconds
     * @param level log level
     * @param service service name
     * @param message log message
     * @param responseTime response time in milliseconds
     * @param tags log tags
     * @return the configured protobuf message
     */
    public static LogMessage create(
            long timestamp,
            LogLevel level,
            String service,
            String message,
            int responseTime,
            Map<String, String> tags) {
        LogMessage.Builder builder = LogMessage.newBuilder()
                .setTimestamp(timestamp)
                .setLevel(level)
                .setService(service)
                .setMessage(message)
                .setResponseTime(responseTime);
        new TreeMap<>(Objects.requireNonNull(tags, "tags")).forEach(builder::putTags);
        return builder.build();
    }
}
