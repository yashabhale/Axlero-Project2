package com.logstream.ingestion.fixtures;

import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;

import java.util.Map;
import java.util.TreeMap;

public final class LogMessageFixtures {
    private static final long DEFAULT_TIMESTAMP_MS = 1_730_000_000_000L;
    private static final LogLevel DEFAULT_LEVEL = LogLevel.INFO;
    private static final String DEFAULT_SERVICE = "test-service";
    private static final String DEFAULT_MESSAGE = "A deterministic test log";
    private static final int DEFAULT_RESPONSE_TIME_MS = 42;

    private LogMessageFixtures() {
    }

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
     * Builds a protobuf message without validating its fields, so tests can create invalid inputs.
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
        new TreeMap<>(tags).forEach(builder::putTags);
        return builder.build();
    }
}
