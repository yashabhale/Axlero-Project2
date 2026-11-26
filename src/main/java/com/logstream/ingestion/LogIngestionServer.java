package com.logstream.ingestion;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class LogIngestionServer {
    private static final Logger logger = LoggerFactory.getLogger(LogIngestionServer.class);
    private static final int DEFAULT_PORT = 50051;
    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final String DEFAULT_CORS_ORIGINS =
            "http://localhost:5173,http://127.0.0.1:5173";

    private LogIngestionServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int httpPort = configuredHttpPort();
        if (httpPort == port) {
            throw new IllegalArgumentException("LOGSTREAM_HTTP_PORT must differ from the gRPC port");
        }
        String databasePath = System.getenv().getOrDefault("LOGSTREAM_DB_PATH", "data/logstream.db");
        SqliteLogSink logSink;
        try {
            logSink = new SqliteLogSink(Path.of(databasePath));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize SQLite log database", exception);
        }
        Server server;
        try {
            server = ServerBuilder.forPort(port)
                    .addService(new LogIngestionServiceImpl(logSink))
                    .build()
                    .start();
        } catch (IOException | RuntimeException exception) {
            closeLogSinkAfterStartupFailure(logSink, exception);
            throw exception;
        }

        LogQueryHttpServer httpServer;
        try {
            httpServer = new LogQueryHttpServer(httpPort, logSink, configuredCorsOrigins());
        } catch (IOException | RuntimeException exception) {
            shutdownServerAfterStartupFailure(server, exception);
            closeLogSinkAfterStartupFailure(logSink, exception);
            throw exception;
        }

        logger.info("Log ingestion gRPC server listening on port {}", server.getPort());
        logger.info("Log query HTTP server listening on port {} with database {}", httpServer.getPort(), databasePath);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down log ingestion server");
            server.shutdown();
            httpServer.close();
            try {
                if (!server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException exception) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
                logger.error("Interrupted while shutting down log ingestion server", exception);
            }
            try {
                logSink.close();
            } catch (RuntimeException exception) {
                logger.error("Failed to close SQLite log database", exception);
            }
        }));
        server.awaitTermination();
    }

    private static int configuredHttpPort() {
        String configuredPort = System.getenv("LOGSTREAM_HTTP_PORT");
        if (configuredPort == null) {
            return DEFAULT_HTTP_PORT;
        }

        int port;
        try {
            port = Integer.parseInt(configuredPort);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("LOGSTREAM_HTTP_PORT must be an integer", exception);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("LOGSTREAM_HTTP_PORT must be between 1 and 65535");
        }
        return port;
    }

    private static Set<String> configuredCorsOrigins() {
        String configuredOrigins = System.getenv().getOrDefault(
                "LOGSTREAM_CORS_ORIGINS", DEFAULT_CORS_ORIGINS);
        return Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void closeLogSinkAfterStartupFailure(SqliteLogSink logSink, Throwable failure) {
        try {
            logSink.close();
        } catch (RuntimeException closeException) {
            failure.addSuppressed(closeException);
        }
    }

    private static void shutdownServerAfterStartupFailure(Server server, Throwable failure) {
        server.shutdownNow();
        try {
            server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(exception);
        }
    }
}
