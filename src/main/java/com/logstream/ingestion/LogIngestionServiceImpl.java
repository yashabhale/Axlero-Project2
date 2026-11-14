package com.logstream.ingestion;

import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogCountRequest;
import com.logstream.ingestion.v1.LogCountResponse;
import com.logstream.ingestion.v1.LogIngestionServiceGrpc;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LogIngestionServiceImpl extends LogIngestionServiceGrpc.LogIngestionServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(LogIngestionServiceImpl.class);
    private static final int STREAM_BATCH_SIZE = 256;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MAX_AGGREGATION_RANGE_MS = 31L * 24 * 60 * MILLIS_PER_MINUTE;
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

    @Override
    public void getLogCountsByMinute(LogCountRequest request, StreamObserver<LogCountResponse> responseObserver) {
        long startTimestampMs = request.getStartTimestampMs();
        long endTimestampMs = request.getEndTimestampMs();
        if (startTimestampMs < 0 || endTimestampMs <= startTimestampMs
                || endTimestampMs - startTimestampMs > MAX_AGGREGATION_RANGE_MS) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Aggregation range must be positive and no longer than 31 days")
                    .asRuntimeException());
            return;
        }

        try {
            Map<Long, Long> countsByMinute = new TreeMap<>();
            for (TimeSeriesBucket bucket : logSink.countLogsByMinute(startTimestampMs, endTimestampMs)) {
                countsByMinute.put(bucket.minuteStartMs(), bucket.count());
            }

            long firstMinute = startTimestampMs / MILLIS_PER_MINUTE * MILLIS_PER_MINUTE;
            long lastMinute = (endTimestampMs - 1) / MILLIS_PER_MINUTE * MILLIS_PER_MINUTE;
            LogCountResponse.Builder response = LogCountResponse.newBuilder();
            for (long minuteStart = firstMinute; ; minuteStart += MILLIS_PER_MINUTE) {
                response.addBuckets(com.logstream.ingestion.v1.LogCountBucket.newBuilder()
                        .setMinuteStartMs(minuteStart)
                        .setCount(countsByMinute.getOrDefault(minuteStart, 0L)));
                if (minuteStart == lastMinute) {
                    break;
                }
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (RuntimeException exception) {
            logger.error("Failed to aggregate log counts by minute", exception);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Log aggregation failed")
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
