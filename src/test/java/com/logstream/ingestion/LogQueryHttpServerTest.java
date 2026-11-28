package com.logstream.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logstream.ingestion.v1.LogLevel;
import com.logstream.ingestion.v1.LogMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogQueryHttpServerTest {
    private static final long BASE_TIMESTAMP = 1_730_000_000_000L;
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LogQueryHttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        InMemoryLogSink sink = new InMemoryLogSink();
        sink.accept(log("api", "first", LogLevel.ERROR, BASE_TIMESTAMP).toBuilder()
                .putTags("environment", "test")
                .build());
        sink.accept(log("api", "second", LogLevel.INFO, BASE_TIMESTAMP + 100));
        sink.accept(log("worker", "third", LogLevel.ERROR, BASE_TIMESTAMP + 200));
        sink.accept(log("api", "fourth", LogLevel.ERROR, BASE_TIMESTAMP + 300));
        server = new LogQueryHttpServer(0, sink, Set.of(ALLOWED_ORIGIN));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void returnsJsonLogsAndPaginationMetadata() throws Exception {
        HttpResponse<String> response = get("");
        JsonNode body = objectMapper.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(100, body.get("limit").asInt());
        assertEquals(0, body.get("offset").asInt());
        assertEquals(4, body.get("totalCount").asLong());
        assertEquals(4, body.get("logs").size());
        assertTrue(body.get("logs").isArray());
        assertEquals(4, body.size());
        assertTrue(body.has("logs"));
        assertTrue(body.has("limit"));
        assertTrue(body.has("offset"));
        assertTrue(body.has("totalCount"));
        assertEquals(6, body.get("logs").get(0).size());
        assertEquals("first", body.get("logs").get(0).get("message").asText());
        assertEquals(BASE_TIMESTAMP, body.get("logs").get(0).get("timestamp").asLong());
        assertEquals("ERROR", body.get("logs").get(0).get("level").asText());
        assertEquals("api", body.get("logs").get(0).get("service").asText());
        assertEquals(42, body.get("logs").get(0).get("responseTimeMs").asInt());
        assertEquals("test", body.get("logs").get(0).get("tags").get("environment").asText());
    }

    @Test
    void filtersByLevelServiceAndTimestampRange() throws Exception {
        JsonNode levelResponse = json(get("?level=ERROR"));
        assertEquals(3, levelResponse.get("totalCount").asLong());

        JsonNode serviceResponse = json(get("?service=api"));
        assertEquals(3, serviceResponse.get("totalCount").asLong());

        JsonNode timestampResponse = json(get(
                "?startTime=" + (BASE_TIMESTAMP + 100) + "&endTime=" + (BASE_TIMESTAMP + 300)));
        assertEquals(2, timestampResponse.get("totalCount").asLong());
        assertEquals("second", timestampResponse.get("logs").get(0).get("message").asText());
        assertEquals("third", timestampResponse.get("logs").get(1).get("message").asText());
    }

    @Test
    void combinesFiltersWithPaginationAndHalfOpenTimestamps() throws Exception {
        JsonNode response = json(get(
                "?level=ERROR&service=api&startTime=" + BASE_TIMESTAMP
                        + "&endTime=" + (BASE_TIMESTAMP + 301)
                        + "&limit=1&offset=1"));

        assertEquals(2, response.get("totalCount").asLong());
        assertEquals(1, response.get("limit").asInt());
        assertEquals(1, response.get("offset").asInt());
        assertEquals(1, response.get("logs").size());
        assertEquals("fourth", response.get("logs").get(0).get("message").asText());
    }

    @Test
    void paginatesAndReturnsEmptyResults() throws Exception {
        JsonNode page = json(get("?limit=1&offset=1"));
        assertEquals(1, page.get("logs").size());
        assertEquals(4, page.get("totalCount").asLong());
        assertEquals("second", page.get("logs").get(0).get("message").asText());

        JsonNode empty = json(get("?service=missing"));
        assertEquals(0, empty.get("totalCount").asLong());
        assertTrue(empty.get("logs").isEmpty());
    }

    @Test
    void acceptsMaximumLimitAndReturnsEmptyPagesBeyondAvailableResults() throws Exception {
        JsonNode maximumLimit = json(get("?limit=1000"));
        assertEquals(1000, maximumLimit.get("limit").asInt());
        assertEquals(4, maximumLimit.get("logs").size());
        assertEquals(4, maximumLimit.get("totalCount").asLong());

        JsonNode atEnd = json(get("?offset=4"));
        assertTrue(atEnd.get("logs").isEmpty());
        assertEquals(4, atEnd.get("totalCount").asLong());

        JsonNode beyondEnd = json(get("?offset=100"));
        assertTrue(beyondEnd.get("logs").isEmpty());
        assertEquals(4, beyondEnd.get("totalCount").asLong());
    }

    @Test
    void decodesUrlEncodedServiceNamesAndSpecialCharacters() throws Exception {
        String service = "checkout + billing &%東京";
        InMemoryLogSink sink = new InMemoryLogSink();
        sink.accept(log(service, "encoded service", LogLevel.INFO, BASE_TIMESTAMP));

        try (LogQueryHttpServer specialServiceServer =
                     new LogQueryHttpServer(0, sink, Set.of())) {
            String encodedService = URLEncoder.encode(service, StandardCharsets.UTF_8);
            JsonNode response = json(get(specialServiceServer, "?service=" + encodedService));

            assertEquals(1, response.get("totalCount").asLong());
            assertEquals(service, response.get("logs").get(0).get("service").asText());
        }
    }

    @Test
    void returnsStructuredEmptyResponseForEmptyDatabase() throws Exception {
        try (LogQueryHttpServer emptyServer =
                     new LogQueryHttpServer(0, new InMemoryLogSink(), Set.of())) {
            HttpResponse<String> httpResponse = get(emptyServer, "");
            JsonNode response = json(httpResponse);

            assertEquals(100, response.get("limit").asInt());
            assertEquals(0, response.get("offset").asInt());
            assertEquals(0, response.get("totalCount").asLong());
            assertTrue(response.get("logs").isArray());
            assertTrue(response.get("logs").isEmpty());
        }
    }

    @Test
    void returnsBadRequestForInvalidParameters() throws Exception {
        List<String> invalidQueries = List.of(
                "?level=NOT_A_LEVEL",
                "?level=LOG_LEVEL_UNSPECIFIED",
                "?level=UNRECOGNIZED",
                "?level=",
                "?limit=1001",
                "?limit=-1",
                "?limit=0",
                "?limit=abc",
                "?limit=",
                "?offset=-1",
                "?offset=abc",
                "?offset=2147483648",
                "?offset=",
                "?service=%20",
                "?service=",
                "?startTime=not-a-timestamp",
                "?startTime=9223372036854775808",
                "?startTime=-1",
                "?startTime=",
                "?endTime=-1",
                "?endTime=not-a-timestamp",
                "?endTime=9223372036854775808",
                "?endTime=",
                "?startTime=200&endTime=200",
                "?startTime=300&endTime=200",
                "?unknown=value",
                "?limit=1&limit=2",
                "?service=%");

        for (String query : invalidQueries) {
            HttpResponse<String> response = get(query);
            assertEquals(400, response.statusCode(), query);
            JsonNode error = objectMapper.readTree(response.body());
            assertEquals(1, error.size(), query);
            assertTrue(error.has("error"), query);
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"), query);
        }
    }

    @Test
    void returnsStructuredNotFoundResponseForUnknownApiPath() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/api/unknown"))
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json"));
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(1, body.size());
        assertEquals("Not found", body.get("error").asText());
    }

    @Test
    void returnsGenericInternalErrorWithoutLeakingDetails() throws Exception {
        LogSink failingSink = new LogSink() {
            @Override
            public void accept(LogMessage logMessage) {
            }

            @Override
            public List<LogMessage> queryLogs(LogQuery query) {
                throw new IllegalStateException("database credentials must not be exposed");
            }

            @Override
            public long countLogs(LogQuery query) {
                return 0;
            }

            @Override
            public List<TimeSeriesBucket> countLogsByMinute(long startTimestampMs, long endTimestampMs) {
                return List.of();
            }
        };

        try (LogQueryHttpServer failingServer =
                     new LogQueryHttpServer(0, failingSink, Set.of())) {
            HttpResponse<String> response = get(failingServer, "");

            assertEquals(500, response.statusCode());
            assertEquals("Internal server error", objectMapper.readTree(response.body()).get("error").asText());
            assertFalse(response.body().contains("database credentials"));
            assertFalse(response.body().contains("IllegalStateException"));
            assertFalse(response.body().contains("at com.logstream"));
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"));
        }
    }

    @Test
    void returnsGenericInternalErrorWhenCountingFails() throws Exception {
        LogSink failingSink = new LogSink() {
            @Override
            public void accept(LogMessage logMessage) {
            }

            @Override
            public List<LogMessage> queryLogs(LogQuery query) {
                return List.of();
            }

            @Override
            public long countLogs(LogQuery query) {
                throw new IllegalStateException("private database failure details");
            }

            @Override
            public List<TimeSeriesBucket> countLogsByMinute(long startTimestampMs, long endTimestampMs) {
                return List.of();
            }
        };

        try (LogQueryHttpServer failingServer =
                     new LogQueryHttpServer(0, failingSink, Set.of())) {
            HttpResponse<String> response = get(failingServer, "");

            assertEquals(500, response.statusCode());
            assertEquals("Internal server error", objectMapper.readTree(response.body()).get("error").asText());
            assertFalse(response.body().contains("private database failure details"));
            assertFalse(response.body().contains("IllegalStateException"));
            assertFalse(response.body().contains("at com.logstream"));
        }
    }

    @Test
    void allowsConfiguredBrowserOriginAndHandlesPreflight() throws Exception {
        HttpResponse<String> response = get("", ALLOWED_ORIGIN);
        assertEquals(ALLOWED_ORIGIN,
                response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

        HttpRequest preflight = HttpRequest.newBuilder(uri(server, ""))
                .header("Origin", ALLOWED_ORIGIN)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> preflightResponse =
                httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflightResponse.statusCode());
        assertEquals("GET, OPTIONS",
                preflightResponse.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
    }

    @Test
    void rejectsDisallowedOriginsAndUnsupportedMethods() throws Exception {
        HttpResponse<String> disallowedGet = get("", "http://untrusted.example");
        assertEquals(200, disallowedGet.statusCode());
        assertFalse(disallowedGet.headers().firstValue("Access-Control-Allow-Origin").isPresent());

        HttpRequest preflight = HttpRequest.newBuilder(uri(server, ""))
                .header("Origin", "http://untrusted.example")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> preflightResponse =
                httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, preflightResponse.statusCode());
        assertEquals("Origin not allowed", objectMapper.readTree(preflightResponse.body()).get("error").asText());
        assertFalse(preflightResponse.headers().firstValue("Access-Control-Allow-Origin").isPresent());

        HttpRequest post = HttpRequest.newBuilder(uri(server, ""))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> postResponse =
                httpClient.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, postResponse.statusCode());
        assertEquals("GET, OPTIONS", postResponse.headers().firstValue("Allow").orElseThrow());
        assertEquals("Method not allowed", objectMapper.readTree(postResponse.body()).get("error").asText());
    }

    private HttpResponse<String> get(String query) throws Exception {
        return get(server, query);
    }

    private HttpResponse<String> get(String query, String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, query))
                .header("Origin", origin)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(LogQueryHttpServer targetServer, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(targetServer, query)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body());
    }

    private static URI uri(LogQueryHttpServer targetServer, String query) {
        return URI.create("http://localhost:" + targetServer.getPort() + "/api/logs" + query);
    }

    private static LogMessage log(String service, String message, LogLevel level, long timestamp) {
        return LogMessage.newBuilder()
                .setTimestamp(timestamp)
                .setLevel(level)
                .setService(service)
                .setMessage(message)
                .setResponseTime(42)
                .build();
    }
}
