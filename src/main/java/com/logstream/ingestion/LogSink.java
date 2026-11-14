package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogMessage;

import java.util.List;

public interface LogSink extends AutoCloseable {
    void accept(LogMessage logMessage);

    List<TimeSeriesBucket> countLogsByMinute(long startTimestampMs, long endTimestampMs);

    default void acceptAll(List<LogMessage> logMessages) {
        for (LogMessage logMessage : logMessages) {
            accept(logMessage);
        }
    }

    @Override
    default void close() {
    }
}
