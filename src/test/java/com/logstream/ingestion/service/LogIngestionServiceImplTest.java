package com.logstream.ingestion.service;

import com.logstream.ingestion.LogIngestionServiceImpl;
import com.logstream.ingestion.fixtures.LogMessageFixtures;
import com.logstream.ingestion.fixtures.RecordingLogSink;
import com.logstream.ingestion.fixtures.RecordingStreamObserver;
import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogCountRequest;
import com.logstream.ingestion.v1.LogCountResponse;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogIngestionServiceImplTest {
    @Test
    void successfulBatchIngestionReturnsAcceptedCountAndCompletesObserver() {
        RecordingLogSink sink = new RecordingLogSink();
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog(),
                LogMessageFixtures.validLog().toBuilder().setService("worker").build());
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addAllLogs(logs)
                .build();

        service.ingestBatchLogs(request, observer);

        assertNull(observer.getError());
        assertEquals(1, observer.getValues().size());
        assertEquals(logs.size(), observer.getValues().get(0).getAcceptedCount());
        assertEquals(1, observer.getCompletionCount());
        assertEquals(logs, sink.getAcceptedLogs());
    }

    @Test
    void successfulBatchPreservesMultipleTagsOnEachLog() {
        RecordingLogSink sink = new RecordingLogSink();
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog().toBuilder()
                        .putTags("environment", "test")
                        .putTags("region", "west")
                        .build(),
                LogMessageFixtures.validLog().toBuilder()
                        .setService("worker")
                        .putTags("environment", "staging")
                        .putTags("team", "platform")
                        .build());
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addAllLogs(logs)
                .build();

        service.ingestBatchLogs(request, observer);

        assertNull(observer.getError());
        assertEquals(1, observer.getValues().size());
        assertEquals(2, observer.getValues().get(0).getAcceptedCount());
        assertEquals(1, observer.getCompletionCount());
        assertEquals(logs, sink.getAcceptedLogs());
        assertEquals(Map.of("environment", "test", "region", "west"),
                sink.getAcceptedLogs().get(0).getTagsMap());
        assertEquals(Map.of("environment", "staging", "team", "platform"),
                sink.getAcceptedLogs().get(1).getTagsMap());
    }

    @Test
    void successfulAggregationReturnsMinuteBucketsAndCompletesObserver() {
        RecordingLogSink sink = new RecordingLogSink();
        long firstMinute = 1_730_000_000_000L / 60_000 * 60_000;
        sink.acceptAll(List.of(
                LogMessageFixtures.validLog().toBuilder().setTimestamp(firstMinute + 1_000).build(),
                LogMessageFixtures.validLog().toBuilder().setTimestamp(firstMinute + 61_000).build()));
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<LogCountResponse> observer = new RecordingStreamObserver<>();
        LogCountRequest request = LogCountRequest.newBuilder()
                .setStartTimestampMs(firstMinute)
                .setEndTimestampMs(firstMinute + 180_000)
                .build();

        service.getLogCountsByMinute(request, observer);

        assertNull(observer.getError());
        assertEquals(1, observer.getValues().size());
        LogCountResponse response = observer.getValues().get(0);
        assertEquals(3, response.getBucketsCount());
        assertEquals(firstMinute, response.getBuckets(0).getMinuteStartMs());
        assertEquals(1, response.getBuckets(0).getCount());
        assertEquals(firstMinute + 60_000, response.getBuckets(1).getMinuteStartMs());
        assertEquals(1, response.getBuckets(1).getCount());
        assertEquals(firstMinute + 120_000, response.getBuckets(2).getMinuteStartMs());
        assertEquals(0, response.getBuckets(2).getCount());
        assertEquals(1, observer.getCompletionCount());
    }

    @Test
    void emptyAggregationRangeReturnsInvalidArgumentWithoutSuccessResponse() {
        RecordingLogSink sink = new RecordingLogSink();
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<LogCountResponse> observer = new RecordingStreamObserver<>();
        long timestamp = 1_730_000_000_000L;
        LogCountRequest request = LogCountRequest.newBuilder()
                .setStartTimestampMs(timestamp)
                .setEndTimestampMs(timestamp)
                .build();

        service.getLogCountsByMinute(request, observer);

        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.getError());
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(0, observer.getValues().size());
        assertEquals(0, observer.getCompletionCount());
    }

    @Test
    void sinkFailureDuringBatchIngestionReturnsInternalStatus() {
        RecordingLogSink sink = new RecordingLogSink();
        sink.setPersistenceFailure(new IllegalStateException("simulated persistence failure"));
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addLogs(LogMessageFixtures.validLog())
                .build();

        service.ingestBatchLogs(request, observer);

        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.getError());
        assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
        assertEquals(0, observer.getValues().size());
        assertEquals(0, observer.getCompletionCount());
        assertEquals(0, sink.getAcceptedLogs().size());
    }
}
