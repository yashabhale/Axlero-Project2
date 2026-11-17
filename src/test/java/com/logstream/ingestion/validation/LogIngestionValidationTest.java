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

class LogIngestionValidationTest {
    @Test
    void rejectsUnspecifiedLogLevel() {
        assertRejectedWithoutPersistence(LogMessageFixtures.validLog().toBuilder()
                .setLevel(LogLevel.LOG_LEVEL_UNSPECIFIED)
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
