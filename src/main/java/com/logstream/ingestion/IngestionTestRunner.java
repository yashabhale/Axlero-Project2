package com.logstream.ingestion;

import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogIngestionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class IngestionTestRunner {
    private static final Logger logger = LoggerFactory.getLogger(IngestionTestRunner.class);

    private IngestionTestRunner() {
    }

    public static void main(String[] args) throws Exception {
        int serviceCount = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int logsPerService = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
        if (serviceCount <= 0 || logsPerService < 0) {
            throw new IllegalArgumentException("Service count must be positive and log count must not be negative");
        }

        InMemoryLogSink sink = new InMemoryLogSink();
        Server server = ServerBuilder.forPort(0)
                .addService(new LogIngestionServiceImpl(sink))
                .build()
                .start();

        try {
            long startNanos = System.nanoTime();
            runConcurrentStreams(server.getPort(), serviceCount, logsPerService);
            verifyBatchIngestion(server.getPort());

            long streamingExpected = (long) serviceCount * logsPerService;
            long expectedTotal = streamingExpected + 2;
            long accepted = sink.getAcceptedCount();
            if (accepted != expectedTotal) {
                throw new IllegalStateException(
                        "Expected " + expectedTotal + " accepted logs but found " + accepted);
            }

            double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            logger.info("Verified {} accepted logs from {} services in {} seconds ({} logs/second)",
                    accepted, serviceCount, String.format("%.3f", elapsedSeconds),
                    String.format("%.0f", accepted / elapsedSeconds));
        } finally {
            server.shutdown();
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        }
    }

    private static void runConcurrentStreams(int port, int serviceCount, int logsPerService) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(serviceCount);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < serviceCount; i++) {
                String serviceName = "mock-service-" + i;
                results.add(executor.submit(
                        () -> MockLogGenerator.streamLogs("localhost", port, serviceName, logsPerService)));
            }
            for (Future<Long> result : results) {
                if (result.get() != logsPerService) {
                    throw new IllegalStateException("Server returned an unexpected streaming accepted count");
                }
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    private static void verifyBatchIngestion(int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        try {
            IngestBatchRequest batch = IngestBatchRequest.newBuilder()
                    .addLogs(MockLogGenerator.createLog("batch-service", 1))
                    .addLogs(MockLogGenerator.createLog("batch-service", 2))
                    .build();
            IngestResponse response = LogIngestionServiceGrpc.newBlockingStub(channel).ingestBatchLogs(batch);
            if (response.getAcceptedCount() != 2) {
                throw new IllegalStateException("Batch RPC returned an unexpected accepted count");
            }
        } finally {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException exception) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing batch test channel", exception);
            }
        }
    }
}
