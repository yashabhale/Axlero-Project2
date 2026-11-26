package com.logstream.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LogQueryHttpServer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(LogQueryHttpServer.class);
    private static final int SHUTDOWN_DELAY_SECONDS = 5;
    private static final int DEFAULT_LIMIT = 100;
    private static final Set<String> QUERY_PARAMETERS =
            Set.of("level", "service", "startTime", "endTime", "limit", "offset");

    private final HttpServer server;
    private final LogSink logSink;
    private final Set<String> allowedOrigins;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final AtomicBoolean closed = new AtomicBoolean();

    public LogQueryHttpServer(int port, LogSink logSink, Set<String> allowedOrigins) throws IOException {
        this.logSink = Objects.requireNonNull(logSink, "logSink");
        this.allowedOrigins = Set.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handleRequest);
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        server.stop(SHUTDOWN_DELAY_SECONDS);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while stopping HTTP request workers", exception);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            if (!"/api/logs".equals(exchange.getRequestURI().getPath())) {
                applyCorsHeaders(exchange);
                sendError(exchange, 404, "Not found");
                return;
            }
            if (handlePreflight(exchange)) {
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            LogQuery query = parseQuery(parseParameters(exchange.getRequestURI().getRawQuery()));
            List<LogMessage> messages = logSink.queryLogs(query);
            long totalCount = logSink.countLogs(query);
            List<ApiLog> logs = new ArrayList<>(messages.size());
            for (LogMessage message : messages) {
                logs.add(new ApiLog(
                        message.getTimestamp(),
                        message.getLevel().name(),
                        message.getService(),
                        message.getMessage(),
                        message.getResponseTime(),
                        message.getTagsMap()));
            }
            sendJson(exchange, 200, new ApiResponse(logs, query.limit(), query.offset(), totalCount));
        } catch (BadRequestException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (RuntimeException exception) {
            logger.error("Unexpected failure handling log query request", exception);
            sendError(exchange, 500, "Internal server error");
        } finally {
            exchange.close();
        }
    }

    private boolean handlePreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            applyCorsHeaders(exchange);
            return false;
        }

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            sendError(exchange, 403, "Origin not allowed");
            return true;
        }
        applyCorsHeaders(exchange);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Allow", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Access-Control-Max-Age", "600");
        exchange.sendResponseHeaders(204, -1);
        return true;
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, new ErrorResponse(message));
    }

    private void sendJson(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(response);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static Map<String, String> parseParameters(String rawQuery) throws BadRequestException {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : rawQuery.split("&", -1)) {
            int separator = part.indexOf('=');
            String rawName = separator < 0 ? part : part.substring(0, separator);
            String rawValue = separator < 0 ? "" : part.substring(separator + 1);
            String name;
            String value;
            try {
                name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("Malformed query parameter encoding");
            }
            if (!QUERY_PARAMETERS.contains(name)) {
                throw new BadRequestException("Unknown query parameter: " + name);
            }
            if (parameters.putIfAbsent(name, value) != null) {
                throw new BadRequestException("Query parameter may only be specified once: " + name);
            }
        }
        return parameters;
    }

    private static LogQuery parseQuery(Map<String, String> parameters) throws BadRequestException {
        LogLevel level = null;
        String rawLevel = parameters.get("level");
        if (rawLevel != null) {
            try {
                level = LogLevel.valueOf(rawLevel.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("Invalid value for parameter 'level'");
            }
        }

        try {
            return new LogQuery(
                    level,
                    parameters.get("service"),
                    parseLong(parameters, "startTime"),
                    parseLong(parameters, "endTime"),
                    parseInteger(parameters, "limit", DEFAULT_LIMIT),
                    parseInteger(parameters, "offset", 0));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
    }

    private static Long parseLong(Map<String, String> parameters, String name) throws BadRequestException {
        String value = parameters.get(name);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Parameter '" + name + "' must be an integer");
        }
    }

    private static int parseInteger(Map<String, String> parameters, String name, int defaultValue)
            throws BadRequestException {
        String value = parameters.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Parameter '" + name + "' must be an integer");
        }
    }

    public record ApiResponse(List<ApiLog> logs, int limit, int offset, long totalCount) {
    }

    public record ApiLog(
            long timestamp,
            String level,
            String service,
            String message,
            int responseTimeMs,
            Map<String, String> tags) {
    }

    public record ErrorResponse(String error) {
    }

    private static final class BadRequestException extends Exception {
        private BadRequestException(String message) {
            super(message);
        }
    }
}
