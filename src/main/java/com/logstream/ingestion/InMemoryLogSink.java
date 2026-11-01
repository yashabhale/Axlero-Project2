package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogMessage;

import java.util.concurrent.atomic.LongAdder;

public final class InMemoryLogSink implements LogSink {
    private final LongAdder acceptedCount = new LongAdder();

    @Override
    public void accept(LogMessage logMessage) {
        acceptedCount.increment();
    }

    public long getAcceptedCount() {
        return acceptedCount.sum();
    }
}
