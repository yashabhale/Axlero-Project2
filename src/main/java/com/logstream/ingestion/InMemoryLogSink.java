package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;

public final class InMemoryLogSink implements LogSink {
    private final LongAdder acceptedCount = new LongAdder();
    private final NavigableMap<Long, Long> logCountsByTimestamp = new TreeMap<>();
    private final List<LogMessage> logs = new ArrayList<>();

    @Override
    public synchronized void accept(LogMessage logMessage) {
        LogMessage acceptedLog = Objects.requireNonNull(logMessage, "logMessage");
        acceptedCount.increment();
        logs.add(acceptedLog);
        logCountsByTimestamp.merge(acceptedLog.getTimestamp(), 1L, Long::sum);
    }

    @Override
    public synchronized List<LogMessage> queryLogs(LogQuery query) {
        Objects.requireNonNull(query, "query");
        return logs.stream()
                .filter(query::matches)
                .sorted(Comparator.comparingLong(LogMessage::getTimestamp))
                .skip(query.offset())
                .limit(query.limit())
                .toList();
    }

    @Override
    public synchronized long countLogs(LogQuery query) {
        Objects.requireNonNull(query, "query");
        return logs.stream().filter(query::matches).count();
    }

    @Override
    public synchronized List<TimeSeriesBucket> countLogsByMinute(long startTimestampMs, long endTimestampMs) {
        Map<Long, Long> countsByMinute = new TreeMap<>();
        logCountsByTimestamp.subMap(startTimestampMs, true, endTimestampMs, false)
                .forEach((timestamp, count) ->
                        countsByMinute.merge((timestamp / 60_000) * 60_000, count, Long::sum));
        List<TimeSeriesBucket> buckets = new ArrayList<>();
        countsByMinute.forEach((minuteStart, count) -> buckets.add(new TimeSeriesBucket(minuteStart, count)));
        return buckets;
    }

    public long getAcceptedCount() {
        return acceptedCount.sum();
    }
}
