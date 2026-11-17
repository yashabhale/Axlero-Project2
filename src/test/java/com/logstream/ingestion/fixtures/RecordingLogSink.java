package com.logstream.ingestion.fixtures;

import com.logstream.ingestion.LogSink;
import com.logstream.ingestion.TimeSeriesBucket;
import com.logstream.ingestion.v1.LogMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class RecordingLogSink implements LogSink {
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final List<LogMessage> acceptedLogs = new ArrayList<>();
    private final List<Integer> batchSizes = new ArrayList<>();
    private RuntimeException persistenceFailure;
    private int acceptAllInvocationCount;

    @Override
    public synchronized void accept(LogMessage logMessage) {
        throwIfPersistenceFails();
        acceptedLogs.add(Objects.requireNonNull(logMessage, "logMessage"));
    }

    @Override
    public synchronized void acceptAll(List<LogMessage> logMessages) {
        Objects.requireNonNull(logMessages, "logMessages");
        acceptAllInvocationCount++;
        batchSizes.add(logMessages.size());
        throwIfPersistenceFails();
        acceptedLogs.addAll(logMessages);
    }

    @Override
    public synchronized List<TimeSeriesBucket> countLogsByMinute(
            long startTimestampMs,
            long endTimestampMs) {
        Map<Long, Long> countsByMinute = new TreeMap<>();
        for (LogMessage logMessage : acceptedLogs) {
            long timestamp = logMessage.getTimestamp();
            if (timestamp >= startTimestampMs && timestamp < endTimestampMs) {
                long minuteStart = timestamp / MILLIS_PER_MINUTE * MILLIS_PER_MINUTE;
                countsByMinute.merge(minuteStart, 1L, Long::sum);
            }
        }

        List<TimeSeriesBucket> buckets = new ArrayList<>(countsByMinute.size());
        countsByMinute.forEach((minuteStart, count) ->
                buckets.add(new TimeSeriesBucket(minuteStart, count)));
        return List.copyOf(buckets);
    }

    public synchronized List<LogMessage> getAcceptedLogs() {
        return List.copyOf(acceptedLogs);
    }

    public synchronized int getAcceptAllInvocationCount() {
        return acceptAllInvocationCount;
    }

    public synchronized List<Integer> getBatchSizes() {
        return List.copyOf(batchSizes);
    }

    /**
     * Configures a failure for subsequent persistence calls; pass {@code null} to clear it.
     */
    public synchronized void setPersistenceFailure(RuntimeException persistenceFailure) {
        this.persistenceFailure = persistenceFailure;
    }

    private void throwIfPersistenceFails() {
        if (persistenceFailure != null) {
            throw persistenceFailure;
        }
    }
}
