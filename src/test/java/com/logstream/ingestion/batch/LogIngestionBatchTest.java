package com.logstream.ingestion.batch;

import com.logstream.ingestion.LogIngestionServiceImpl;
import com.logstream.ingestion.fixtures.LogMessageFixtures;
import com.logstream.ingestion.fixtures.RecordingLogSink;
import com.logstream.ingestion.fixtures.RecordingStreamObserver;
import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogIngestionBatchTest {
    @Test
    void emptyBatchReturnsZeroAcceptedAndCompletes() {
        RecordingLogSink sink = new RecordingLogSink();
        RecordingStreamObserver<IngestResponse> observer = submitBatch(sink, List.of());

        assertSuccessfulBatch(observer, 0);
        assertEquals(0, sink.getAcceptedLogs().size());
        assertEquals(List.of(0), sink.getBatchSizes());
    }

    @Test
    void singleValidLogIsAccepted() {
        RecordingLogSink sink = new RecordingLogSink();
        LogMessage log = LogMessageFixtures.validLog();
        RecordingStreamObserver<IngestResponse> observer = submitBatch(sink, List.of(log));

        assertSuccessfulBatch(observer, 1);
        assertEquals(List.of(log), sink.getAcceptedLogs());
        assertEquals(List.of(1), sink.getBatchSizes());
    }

    @Test
    void multipleValidLogsAreAcceptedWithCorrectCount() {
        RecordingLogSink sink = new RecordingLogSink();
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog(),
                LogMessageFixtures.validLog().toBuilder().setService("worker").build(),
                LogMessageFixtures.validLog().toBuilder().setService("scheduler").build());
        RecordingStreamObserver<IngestResponse> observer = submitBatch(sink, logs);

        assertSuccessfulBatch(observer, logs.size());
        assertEquals(logs, sink.getAcceptedLogs());
        assertEquals(List.of(logs.size()), sink.getBatchSizes());
    }

    @Test
    void invalidRecordRejectsEntireBatchWithoutPersistence() {
        RecordingLogSink sink = new RecordingLogSink();
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog(),
                LogMessageFixtures.validLog().toBuilder().setMessage("").build());
        RecordingStreamObserver<IngestResponse> observer = submitBatch(sink, logs);

        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.getError());
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(0, observer.getValues().size());
        assertEquals(0, observer.getCompletionCount());
        assertEquals(0, sink.getAcceptedLogs().size());
        assertEquals(0, sink.getAcceptAllInvocationCount());
    }

    @Test
    void persistenceFailureReturnsInternalWithoutSuccessResponse() {
        RecordingLogSink sink = new RecordingLogSink();
        sink.setPersistenceFailure(new IllegalStateException("simulated persistence failure"));
        RecordingStreamObserver<IngestResponse> observer =
                submitBatch(sink, List.of(LogMessageFixtures.validLog()));

        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.getError());
        assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
        assertEquals(0, observer.getValues().size());
        assertEquals(0, observer.getCompletionCount());
        assertEquals(0, sink.getAcceptedLogs().size());
        assertEquals(1, sink.getAcceptAllInvocationCount());
    }

    private static RecordingStreamObserver<IngestResponse> submitBatch(
            RecordingLogSink sink,
            List<LogMessage> logs) {
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addAllLogs(logs)
                .build();
        service.ingestBatchLogs(request, observer);
        return observer;
    }

    private static void assertSuccessfulBatch(
            RecordingStreamObserver<IngestResponse> observer,
            int expectedCount) {
        assertNull(observer.getError());
        assertEquals(1, observer.getValues().size());
        assertEquals(expectedCount, observer.getValues().get(0).getAcceptedCount());
        assertEquals(1, observer.getCompletionCount());
    }
}
