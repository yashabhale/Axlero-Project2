package com.logstream.ingestion;

import com.logstream.ingestion.v1.LogLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LogQueryTest {
    @Test
    void rejectsInvalidFiltersAndPaginationParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> query(LogLevel.LOG_LEVEL_UNSPECIFIED, null, null, null, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> query(null, " ", null, null, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> query(null, null, -1L, null, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> query(null, null, null, -1L, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> query(null, null, 20L, 20L, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> query(null, null, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> query(null, null, null, null, LogQuery.MAX_LIMIT + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> query(null, null, null, null, 10, -1));
    }

    private static LogQuery query(
            LogLevel level,
            String service,
            Long startTimestampMs,
            Long endTimestampMs,
            int limit,
            int offset) {
        return new LogQuery(level, service, startTimestampMs, endTimestampMs, limit, offset);
    }
}
