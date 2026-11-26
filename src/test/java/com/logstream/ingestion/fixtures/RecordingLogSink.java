package com.logstream.ingestion.fixtures;

import com.logstream.ingestion.LogQuery;
import com.logstream.ingestion.LogSink;
import com.logstream.ingestion.TimeSeriesBucket;
import com.logstream.ingestion.v1.LogMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Thread-safe in-memory {@link LogSink} for service tests.
 */
public final class RecordingLogSink implements LogSink {
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final List<LogMessage> acceptedLogs = new ArrayList<>();
    private final List<List<LogMessage>> batches = new ArrayList<>();
    private RuntimeException persistenceFailure;

    @Override
    public synchronized void accept(LogMessage logMessage) {
        LogMessage acceptedLog = Objects.requireNonNull(logMessage, "logMessage");
        throwIfPersistenceFails();
        acceptedLogs.add(acceptedLog);
    }

    @Override
    public synchronized void acceptAll(List<LogMessage> logMessages) {
        List<LogMessage> batch = List.copyOf(Objects.requireNonNull(logMessages, "logMessages"));
        batches.add(batch);
        throwIfPersistenceFails();
        acceptedLogs.addAll(batch);
    }

    @Override
    public synchronized List<LogMessage> queryLogs(LogQuery query) {
        Objects.requireNonNull(query, "query");
        return acceptedLogs.stream()
                .filter(query::matches)
                .sorted(Comparator.comparingLong(LogMessage::getTimestamp))
                .skip(query.offset())
                .limit(query.limit())
                .toList();
    }

    @Override
    public synchronized long countLogs(LogQuery query) {
        Objects.requireNonNull(query, "query");
        return acceptedLogs.stream().filter(query::matches).count();
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

    /**
     * Returns an immutable snapshot of successfully accepted logs.
     *
     * @return accepted log messages
     */
    public synchronized List<LogMessage> getAcceptedLogs() {
        return List.copyOf(acceptedLogs);
    }

    /**
     * Returns the number of {@link #acceptAll(List)} calls, including calls that failed.
     *
     * @return batch call count
     */
    public synchronized int getAcceptAllInvocationCount() {
        return batches.size();
    }

    /**
     * Returns the sizes of all attempted batches in call order.
     *
     * @return batch sizes
     */
    public synchronized List<Integer> getBatchSizes() {
        return batches.stream().map(List::size).toList();
    }

    /**
     * Returns immutable snapshots of all attempted batches in call order.
     *
     * @return attempted batches, including any that failed to persist
     */
    public synchronized List<List<LogMessage>> getBatches() {
        return List.copyOf(batches);
    }

    /**
     * Configures a failure for subsequent persistence calls; pass {@code null} to clear it.
     *
     * @param persistenceFailure exception to throw, or {@code null} to disable failures
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
