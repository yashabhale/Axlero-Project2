package com.logstream.ingestion;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public final class LogIngestionServer {
    private static final Logger logger = LoggerFactory.getLogger(LogIngestionServer.class);
    private static final int DEFAULT_PORT = 50051;

    private LogIngestionServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String databasePath = System.getenv().getOrDefault("LOGSTREAM_DB_PATH", "data/logstream.db");
        SqliteLogSink logSink;
        try {
            logSink = new SqliteLogSink(Path.of(databasePath));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize SQLite log database", exception);
        }
        Server server = ServerBuilder.forPort(port)
                .addService(new LogIngestionServiceImpl(logSink))
                .build()
                .start();

        logger.info("Log ingestion gRPC server listening on port {} with database {}", server.getPort(), databasePath);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down log ingestion server");
            server.shutdown();
            try {
                if (!server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
                logSink.close();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while shutting down log ingestion server", exception);
            } catch (RuntimeException exception) {
                logger.error("Failed to close SQLite log database", exception);
            }
        }));
        server.awaitTermination();
    }
}
