package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;

public record LogQuery(
        LogLevel level,
        String service,
        Long startTimestampMs,
        Long endTimestampMs,
        int limit,
        int offset) {
    public static final int MAX_LIMIT = 1_000;

    public LogQuery {
        if (level == LogLevel.LOG_LEVEL_UNSPECIFIED || level == LogLevel.UNRECOGNIZED) {
            throw new IllegalArgumentException("Log level must be specified");
        }
        if (service != null && service.isBlank()) {
            throw new IllegalArgumentException("Service must not be blank");
        }
        if (startTimestampMs != null && startTimestampMs < 0) {
            throw new IllegalArgumentException("Start timestamp must not be negative");
        }
        if (endTimestampMs != null && endTimestampMs < 0) {
            throw new IllegalArgumentException("End timestamp must not be negative");
        }
        if (startTimestampMs != null && endTimestampMs != null
                && endTimestampMs <= startTimestampMs) {
            throw new IllegalArgumentException("End timestamp must be after start timestamp");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative");
        }
    }

    public boolean matches(LogMessage logMessage) {
        long timestamp = logMessage.getTimestamp();
        return (level == null || level == logMessage.getLevel())
                && (service == null || service.equals(logMessage.getService()))
                && (startTimestampMs == null || timestamp >= startTimestampMs)
                && (endTimestampMs == null || timestamp < endTimestampMs);
    }
}
