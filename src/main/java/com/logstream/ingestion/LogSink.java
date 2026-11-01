package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogMessage;

public interface LogSink {
    void accept(LogMessage logMessage);
}
