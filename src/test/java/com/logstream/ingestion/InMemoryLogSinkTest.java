package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryLogSinkTest {
    @Test
    void queriesLogsByFiltersInChronologicalOrderWithPagination() {
        InMemoryLogSink sink = new InMemoryLogSink();
        sink.accept(log("api", "later", LogLevel.INFO, 300));
        sink.accept(log("worker", "earlier", LogLevel.ERROR, 100));
        sink.accept(log("api", "before start", LogLevel.ERROR, 199));
        sink.accept(log("api", "middle", LogLevel.ERROR, 200));
        sink.accept(log("api", "same time second", LogLevel.ERROR, 200));
        sink.accept(log("api", "at end", LogLevel.ERROR, 301));

        List<LogMessage> filtered = sink.queryLogs(
                new LogQuery(LogLevel.ERROR, "api", 200L, 301L, 10, 0));
        assertEquals(List.of("middle", "same time second"),
                filtered.stream().map(LogMessage::getMessage).toList());

        List<LogMessage> page = sink.queryLogs(new LogQuery(null, null, null, null, 2, 2));
        assertEquals(List.of("middle", "same time second"),
                page.stream().map(LogMessage::getMessage).toList());
    }

    @Test
    void returnsEmptyListWhenNoLogsMatch() {
        InMemoryLogSink sink = new InMemoryLogSink();
        sink.accept(log("api", "available", LogLevel.INFO, 100));

        assertEquals(List.of(), sink.queryLogs(
                new LogQuery(LogLevel.ERROR, "api", null, null, 10, 0)));
    }

    private static LogMessage log(String service, String message, LogLevel level, long timestamp) {
        return LogMessage.newBuilder()
                .setTimestamp(timestamp)
                .setLevel(level)
                .setService(service)
                .setMessage(message)
                .build();
    }
}
