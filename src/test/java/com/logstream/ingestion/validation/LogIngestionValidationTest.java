package com.logstream.ingestion.validation;

import com.logstream.ingestion.LogIngestionServiceImpl;
import com.logstream.ingestion.fixtures.LogMessageFixtures;
import com.logstream.ingestion.fixtures.RecordingLogSink;
import com.logstream.ingestion.fixtures.RecordingStreamObserver;
import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogIngestionValidationTest {
    @Test
    void rejectsUnspecifiedLogLevel() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setLevel(LogLevel.LOG_LEVEL_UNSPECIFIED)
                .build());
    }

    @Test
    void rejectsUnrecognizedLogLevel() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setLevelValue(99)
                .build());
    }

    @Test
    void rejectsBlankServiceName() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setService("  ")
                .build());
    }

    @Test
    void rejectsBlankMessage() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setMessage("\t ")
                .build());
    }

    @Test
    void rejectsNegativeResponseTime() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setResponseTime(-1)
                .build());
    }

    @Test
    void acceptsResponseTimeBoundaryValues() {
        RecordingLogSink sink = new RecordingLogSink();
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addLogs(LogMessageFixtures.validLog().toBuilder().setResponseTime(0).build())
                .addLogs(LogMessageFixtures.validLog().toBuilder()
                        .setService("slow-service")
                        .setResponseTime(Integer.MAX_VALUE)
                        .build())
                .build();

        service.ingestBatchLogs(request, observer);

        assertNull(observer.getError());
        assertEquals(1, observer.getValues().size());
        assertEquals(2, observer.getValues().get(0).getAcceptedCount());
        assertEquals(1, observer.getCompletionCount());
        assertEquals(List.of(0, Integer.MAX_VALUE),
                sink.getAcceptedLogs().stream().map(LogMessage::getResponseTime).toList());
    }

    @Test
    void rejectsZeroTimestamp() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setTimestamp(0)
                .build());
    }

    @Test
    void rejectsNegativeTimestamp() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setTimestamp(-1)
                .build());
    }

    @Test
    void mixedValidAndInvalidBatchRejectsWithoutPersistingAnyRecords() {
        RecordingLogSink sink = new RecordingLogSink();
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addAllLogs(List.of(
                        LogMessageFixtures.validLog(),
                        LogMessageFixtures.validLog().toBuilder().setService(" ").build()))
                .build();

        service.ingestBatchLogs(request, observer);

        assertInvalidArgument(observer);
        assertEquals(0, sink.getAcceptedLogs().size());
        assertEquals(0, sink.getAcceptAllInvocationCount());
    }

    private static void assertRejectedWithoutPersistence(LogMessage invalidLog) {
        RecordingLogSink sink = new RecordingLogSink();
        LogIngestionServiceImpl service = new LogIngestionServiceImpl(sink);
        RecordingStreamObserver<IngestResponse> observer = new RecordingStreamObserver<>();
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addLogs(invalidLog)
                .build();

        service.ingestBatchLogs(request, observer);

        assertInvalidArgument(observer);
        assertEquals(0, sink.getAcceptedLogs().size());
        assertEquals(0, sink.getAcceptAllInvocationCount());
    }

    private static void assertInvalidArgument(RecordingStreamObserver<IngestResponse> observer) {
        StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.getError());
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(0, observer.getValues().size());
        assertEquals(0, observer.getCompletionCount());
    }
}
