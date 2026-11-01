package com.logstream.ingestion;

import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogIngestionServiceGrpc;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MockLogGenerator {
    private MockLogGenerator() {
    }

    public static long streamLogs(String host, int port, String serviceName, int logCount) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<IngestResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            StreamObserver<LogMessage> requestObserver =
                    LogIngestionServiceGrpc.newStub(channel).ingestLog(new StreamObserver<>() {
                        @Override
                        public void onNext(IngestResponse value) {
                            response.set(value);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            failure.set(throwable);
                            completed.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            completed.countDown();
                        }
                    });

            for (int i = 0; i < logCount; i++) {
                requestObserver.onNext(createLog(serviceName, i));
            }
            requestObserver.onCompleted();

            if (!completed.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for streaming ingestion response");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Streaming ingestion failed", failure.get());
            }
            IngestResponse result = response.get();
            if (result == null) {
                throw new IllegalStateException("Streaming ingestion completed without a response");
            }
            return result.getAcceptedCount();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for ingestion response", exception);
        } finally {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException exception) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static LogMessage createLog(String serviceName, int sequence) {
        LogLevel[] levels = {LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG};
        return LogMessage.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setLevel(levels[Math.floorMod(sequence, levels.length)])
                .setService(serviceName)
                .setMessage("Mock log event " + sequence + " from " + serviceName)
                .setResponseTime(Math.floorMod(sequence * 17, 1000))
                .putTags("environment", "local")
                .putTags("sequence", Integer.toString(sequence))
                .build();
    }
}
