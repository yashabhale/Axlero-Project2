package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogMessage;

import java.util.List;

public interface LogSink extends AutoCloseable {
    void accept(LogMessage logMessage);

    default void acceptAll(List<LogMessage> logMessages) {
        for (LogMessage logMessage : logMessages) {
            accept(logMessage);
        }
    }

    @Override
    default void close() {
    }
}
