package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;

public final class InMemoryLogSink implements LogSink {
    private final LongAdder acceptedCount = new LongAdder();
    private final NavigableMap<Long, Long> logCountsByTimestamp = new TreeMap<>();

    @Override
    public synchronized void accept(LogMessage logMessage) {
        acceptedCount.increment();
        logCountsByTimestamp.merge(logMessage.getTimestamp(), 1L, Long::sum);
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
