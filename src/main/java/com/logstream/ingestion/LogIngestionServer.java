package com.logstream.ingestion;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class LogIngestionServer {
    private static final Logger logger = LoggerFactory.getLogger(LogIngestionServer.class);
    private static final int DEFAULT_PORT = 50051;

    private LogIngestionServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Server server = ServerBuilder.forPort(port)
                .addService(new LogIngestionServiceImpl(new InMemoryLogSink()))
                .build()
                .start();

        logger.info("Log ingestion gRPC server listening on port {}", server.getPort());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down log ingestion server");
            server.shutdown();
        }));
        server.awaitTermination();
    }
}
