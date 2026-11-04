package com.logstream.ingestion;

import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogIngestionServiceGrpc;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class LogIngestionServiceImpl extends LogIngestionServiceGrpc.LogIngestionServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(LogIngestionServiceImpl.class);
    private static final int STREAM_BATCH_SIZE = 256;
    private final LogSink logSink;

    public LogIngestionServiceImpl(LogSink logSink) {
        this.logSink = logSink;
    }

    @Override
    public StreamObserver<LogMessage> ingestLog(StreamObserver<IngestResponse> responseObserver) {
        AtomicLong acceptedCount = new AtomicLong();

        return new StreamObserver<>() {
            private boolean terminated;
            private final List<LogMessage> pendingLogs = new ArrayList<>(STREAM_BATCH_SIZE);

            @Override
            public void onNext(LogMessage logMessage) {
                if (terminated) {
                    return;
                }
                try {
                    validate(logMessage);
                    pendingLogs.add(logMessage);
                    if (pendingLogs.size() == STREAM_BATCH_SIZE) {
                        flushPendingLogs();
                    }
                } catch (IllegalArgumentException exception) {
                    terminated = true;
                    try {
                        flushPendingLogs();
                    } catch (RuntimeException storageException) {
                        pendingLogs.clear();
                        logger.error("Failed to persist logs before rejecting streamed log", storageException);
                        responseObserver.onError(Status.INTERNAL
                                .withDescription("Log ingestion failed")
                                .asRuntimeException());
                        return;
                    }
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription(exception.getMessage())
                            .asRuntimeException());
                } catch (RuntimeException exception) {
                    terminated = true;
                    pendingLogs.clear();
                    logger.error("Failed to ingest streamed log", exception);
                    responseObserver.onError(Status.INTERNAL
                            .withDescription("Log ingestion failed")
                            .asRuntimeException());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                terminated = true;
                try {
                    flushPendingLogs();
                } catch (RuntimeException exception) {
                    logger.error("Failed to persist logs from interrupted stream", exception);
                }
                logger.warn("Log ingestion stream cancelled or failed: {}", throwable.getMessage());
            }

            @Override
            public void onCompleted() {
                if (terminated) {
                    return;
                }
                try {
                    flushPendingLogs();
                    long count = acceptedCount.get();
                    logger.info("Accepted {} logs from streaming ingestion", count);
                    responseObserver.onNext(IngestResponse.newBuilder().setAcceptedCount(count).build());
                    responseObserver.onCompleted();
                } catch (RuntimeException exception) {
                    terminated = true;
                    logger.error("Failed to persist streamed logs", exception);
                    responseObserver.onError(Status.INTERNAL
                            .withDescription("Log ingestion failed")
                            .asRuntimeException());
                }
            }

            private void flushPendingLogs() {
                if (pendingLogs.isEmpty()) {
                    return;
                }
                logSink.acceptAll(pendingLogs);
                acceptedCount.addAndGet(pendingLogs.size());
                pendingLogs.clear();
            }
        };
    }

    @Override
    public void ingestBatchLogs(IngestBatchRequest request, StreamObserver<IngestResponse> responseObserver) {
        try {
            for (LogMessage logMessage : request.getLogsList()) {
                validate(logMessage);
            }

            logSink.acceptAll(request.getLogsList());

            int count = request.getLogsCount();
            logger.info("Accepted batch of {} logs", count);
            responseObserver.onNext(IngestResponse.newBuilder().setAcceptedCount(count).build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(exception.getMessage())
                    .asRuntimeException());
        } catch (RuntimeException exception) {
            logger.error("Failed to ingest log batch", exception);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Log ingestion failed")
                    .asRuntimeException());
        }
    }

    private static void validate(LogMessage logMessage) {
        if (logMessage.getLevel() == LogLevel.LOG_LEVEL_UNSPECIFIED
                || logMessage.getLevel() == LogLevel.UNRECOGNIZED) {
            throw new IllegalArgumentException("Log level must be specified");
        }
        if (logMessage.getService().isBlank()) {
            throw new IllegalArgumentException("Service must not be blank");
        }
        if (logMessage.getMessage().isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
        if (logMessage.getResponseTime() < 0) {
            throw new IllegalArgumentException("Response time must not be negative");
        }
        if (logMessage.getTimestamp() <= 0) {
            throw new IllegalArgumentException("Timestamp must be a positive Unix timestamp in milliseconds");
        }
    }
}
