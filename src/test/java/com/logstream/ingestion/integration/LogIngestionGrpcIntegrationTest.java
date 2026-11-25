package com.logstream.ingestion.integration;

import com.logstream.ingestion.LogIngestionServiceImpl;
import com.logstream.ingestion.SqliteLogSink;
import com.logstream.ingestion.fixtures.LogMessageFixtures;
import com.logstream.ingestion.v1.IngestBatchRequest;
import com.logstream.ingestion.v1.IngestResponse;
import com.logstream.ingestion.v1.LogCountRequest;
import com.logstream.ingestion.v1.LogCountResponse;
import com.logstream.ingestion.v1.LogIngestionServiceGrpc;
import com.logstream.ingestion.v1.LogMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogIngestionGrpcIntegrationTest {
    private static final long RPC_TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDirectory;

    private Path databasePath;
    private SqliteLogSink logSink;
    private Server server;
    private ManagedChannel channel;
    private LogIngestionServiceGrpc.LogIngestionServiceBlockingStub blockingStub;
    private LogIngestionServiceGrpc.LogIngestionServiceStub asyncStub;

    @BeforeEach
    void startGrpcServer() throws Exception {
        databasePath = tempDirectory.resolve("integration.db");
        logSink = new SqliteLogSink(databasePath);
        server = ServerBuilder.forPort(0)
                .addService(new LogIngestionServiceImpl(logSink))
                .build()
                .start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        blockingStub = LogIngestionServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        asyncStub = LogIngestionServiceGrpc.newStub(channel)
                .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopGrpcServerAndCloseDatabase() throws Exception {
        boolean channelTerminated = true;
        boolean serverTerminated = true;
        try {
            if (channel != null) {
                channel.shutdown();
                channelTerminated = channel.awaitTermination(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!channelTerminated) {
                    channel.shutdownNow();
                    channelTerminated = channel.awaitTermination(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            }
            if (server != null) {
                server.shutdown();
                serverTerminated = server.awaitTermination(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!serverTerminated) {
                    server.shutdownNow();
                    serverTerminated = server.awaitTermination(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            }
        } finally {
            if (logSink != null) {
                logSink.close();
            }
        }

        assertTrue(channelTerminated, "gRPC channel did not terminate");
        assertTrue(serverTerminated, "gRPC server did not terminate");
    }

    @Test
    void serverStartsAndAcceptsClientConnection() {
        long firstMinute = 1_730_000_000_000L / 60_000 * 60_000;

        LogCountResponse response = blockingStub.getLogCountsByMinute(
                LogCountRequest.newBuilder()
                        .setStartTimestampMs(firstMinute)
                        .setEndTimestampMs(firstMinute + 120_000)
                        .build());

        assertEquals(2, response.getBucketsCount());
        assertEquals(firstMinute, response.getBuckets(0).getMinuteStartMs());
        assertEquals(0, response.getBuckets(0).getCount());
        assertEquals(firstMinute + 60_000, response.getBuckets(1).getMinuteStartMs());
        assertEquals(0, response.getBuckets(1).getCount());
    }

    @Test
    void batchIngestionPersistsLogsInSqlite() throws SQLException {
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog().toBuilder()
                        .setService("api")
                        .setMessage("first persisted log")
                        .build(),
                LogMessageFixtures.validLog().toBuilder()
                        .setService("worker")
                        .setMessage("second persisted log")
                        .build());

        IngestResponse response = blockingStub.ingestBatchLogs(
                IngestBatchRequest.newBuilder().addAllLogs(logs).build());

        assertEquals(2, response.getAcceptedCount());
        assertEquals(
                List.of("api:first persisted log", "worker:second persisted log"),
                readPersistedLogs());
    }

    @Test
    void batchIngestionPreservesTagAssociationsOverGrpc() throws SQLException {
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog().toBuilder()
                        .setService("api")
                        .putTags("environment", "test")
                        .putTags("team", "platform")
                        .build(),
                LogMessageFixtures.validLog().toBuilder()
                        .setService("worker")
                        .putTags("environment", "staging")
                        .build());

        IngestResponse response = blockingStub.ingestBatchLogs(
                IngestBatchRequest.newBuilder().addAllLogs(logs).build());

        assertEquals(2, response.getAcceptedCount());
        List<String> persistedTags = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement();
             ResultSet tags = statement.executeQuery(
                     "SELECT log_events.service, log_tags.tag_key, log_tags.tag_value "
                             + "FROM log_tags JOIN log_events ON log_tags.log_id = log_events.id "
                             + "ORDER BY log_events.service, log_tags.tag_key")) {
            while (tags.next()) {
                persistedTags.add(tags.getString("service") + ":"
                        + tags.getString("tag_key") + "=" + tags.getString("tag_value"));
            }
        }

        assertEquals(List.of(
                "api:environment=test",
                "api:team=platform",
                "worker:environment=staging"), persistedTags);
    }

    @Test
    void clientStreamingIngestionPersistsLogsAndReturnsAcceptedCount() throws InterruptedException, SQLException {
        List<LogMessage> logs = List.of(
                LogMessageFixtures.validLog().toBuilder().setService("stream-a").build(),
                LogMessageFixtures.validLog().toBuilder().setService("stream-b").build());
        AtomicReference<IngestResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger completionCount = new AtomicInteger();
        CountDownLatch responseTerminated = new CountDownLatch(1);
        StreamObserver<IngestResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(IngestResponse value) {
                response.set(value);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                responseTerminated.countDown();
            }

            @Override
            public void onCompleted() {
                completionCount.incrementAndGet();
                responseTerminated.countDown();
            }
        };
        StreamObserver<LogMessage> requestObserver = asyncStub.ingestLog(responseObserver);

        logs.forEach(requestObserver::onNext);
        requestObserver.onCompleted();

        assertTrue(responseTerminated.await(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(error.get());
        assertNotNull(response.get());
        assertEquals(2, response.get().getAcceptedCount());
        assertEquals(1, completionCount.get());
        assertEquals(
                List.of("stream-a:A deterministic test log", "stream-b:A deterministic test log"),
                readPersistedLogs());
    }

    @Test
    void emptyClientStreamReturnsZeroAcceptedAndPersistsNothing() throws InterruptedException, SQLException {
        AtomicReference<IngestResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger completionCount = new AtomicInteger();
        CountDownLatch responseTerminated = new CountDownLatch(1);
        StreamObserver<IngestResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(IngestResponse value) {
                response.set(value);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                responseTerminated.countDown();
            }

            @Override
            public void onCompleted() {
                completionCount.incrementAndGet();
                responseTerminated.countDown();
            }
        };
        StreamObserver<LogMessage> requestObserver = asyncStub.ingestLog(responseObserver);
        requestObserver.onCompleted();

        assertTrue(responseTerminated.await(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(error.get());
        assertNotNull(response.get());
        assertEquals(0, response.get().getAcceptedCount());
        assertEquals(1, completionCount.get());
        assertEquals(0, readPersistedLogCount());
    }

    @Test
    void invalidClientStreamingLogReturnsInvalidArgumentAndPersistsOnlyEarlierValidLogs()
            throws InterruptedException, SQLException {
        LogMessage validLog = LogMessageFixtures.validLog().toBuilder()
                .setService("stream-before-invalid")
                .setMessage("valid log before invalid input")
                .build();
        LogMessage invalidLog = LogMessageFixtures.validLog().toBuilder()
                .setService(" ")
                .build();
        AtomicReference<IngestResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger completionCount = new AtomicInteger();
        CountDownLatch responseTerminated = new CountDownLatch(1);
        StreamObserver<IngestResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(IngestResponse value) {
                response.set(value);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                responseTerminated.countDown();
            }

            @Override
            public void onCompleted() {
                completionCount.incrementAndGet();
                responseTerminated.countDown();
            }
        };
        StreamObserver<LogMessage> requestObserver = asyncStub.ingestLog(responseObserver);

        requestObserver.onNext(validLog);
        requestObserver.onNext(invalidLog);

        assertTrue(responseTerminated.await(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(error.get()).getCode());
        assertNull(response.get());
        assertEquals(0, completionCount.get());
        assertEquals(List.of("stream-before-invalid:valid log before invalid input"), readPersistedLogs());
    }

    @Test
    void invalidBatchReturnsInvalidArgumentAndPersistsNothing() throws SQLException {
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addLogs(LogMessageFixtures.validLog().toBuilder().setService(" ").build())
                .build();

        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> blockingStub.ingestBatchLogs(request));

        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(0, readPersistedLogCount());
    }

    @Test
    void mixedValidAndInvalidBatchReturnsInvalidArgumentWithoutPartialPersistence() throws SQLException {
        IngestBatchRequest request = IngestBatchRequest.newBuilder()
                .addLogs(LogMessageFixtures.validLog().toBuilder().setService("valid-first").build())
                .addLogs(LogMessageFixtures.validLog().toBuilder().setMessage(" ").build())
                .build();

        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> blockingStub.ingestBatchLogs(request));

        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(0, readPersistedLogCount());
    }

    @Test
    void emptyBatchReturnsZeroAcceptedAndPersistsNothing() throws SQLException {
        IngestResponse response = blockingStub.ingestBatchLogs(IngestBatchRequest.getDefaultInstance());

        assertEquals(0, response.getAcceptedCount());
        assertEquals(0, readPersistedLogCount());
    }

    @Test
    void aggregationReturnsMinuteWiseCountsForPersistedLogs() {
        long firstMinute = 1_730_000_000_000L / 60_000 * 60_000;
        blockingStub.ingestBatchLogs(IngestBatchRequest.newBuilder()
                .addLogs(LogMessageFixtures.validLogAt(firstMinute + 1_000))
                .addLogs(LogMessageFixtures.validLogAt(firstMinute + 59_999))
                .addLogs(LogMessageFixtures.validLogAt(firstMinute + 60_000))
                .build());

        LogCountResponse response = blockingStub.getLogCountsByMinute(
                LogCountRequest.newBuilder()
                        .setStartTimestampMs(firstMinute)
                        .setEndTimestampMs(firstMinute + 180_000)
                        .build());

        assertEquals(3, response.getBucketsCount());
        assertEquals(firstMinute, response.getBuckets(0).getMinuteStartMs());
        assertEquals(2, response.getBuckets(0).getCount());
        assertEquals(firstMinute + 60_000, response.getBuckets(1).getMinuteStartMs());
        assertEquals(1, response.getBuckets(1).getCount());
        assertEquals(firstMinute + 120_000, response.getBuckets(2).getMinuteStartMs());
        assertEquals(0, response.getBuckets(2).getCount());
    }

    @Test
    void invalidAggregationRangeReturnsInvalidArgument() {
        long timestamp = 1_730_000_000_000L;
        LogCountRequest request = LogCountRequest.newBuilder()
                .setStartTimestampMs(timestamp)
                .setEndTimestampMs(timestamp)
                .build();

        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> blockingStub.getLogCountsByMinute(request));

        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
    }

    private List<String> readPersistedLogs() throws SQLException {
        List<String> persistedLogs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(
                     "SELECT service, message FROM log_events ORDER BY id")) {
            while (results.next()) {
                persistedLogs.add(results.getString("service") + ":" + results.getString("message"));
            }
        }
        return persistedLogs;
    }

    private long readPersistedLogCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM log_events")) {
            assertTrue(results.next());
            return results.getLong(1);
        }
    }
}
