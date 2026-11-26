# Axlero Project 2

## LogStream Log Ingestion

Axlero Project 2 is a full-stack log ingestion and dashboard demo built with a Java 17 gRPC backend and a React/Vite frontend. The backend accepts streaming and batch log payloads, validates them, stores accepted records in SQLite, and exposes ingestion through generated protobuf/gRPC interfaces.

The Java sources are generated from `src/main/proto/log_ingestion.proto` during Maven builds, so you do not need to maintain generated gRPC classes manually.

## Features

- Streaming log ingestion over gRPC
- Batch ingestion support for large log sets
- SQLite persistence with indexed lookup support
- Minute-level log-count aggregation over a requested time range
- Local verification runner for mock service load testing
- Frontend dashboard for exploring log data in a browser

## Project overview

- Backend: Java + gRPC + Protobuf + SQLite
- Frontend: React + Vite
- Main server entry point: `com.logstream.ingestion.LogIngestionServer`
- Load verification utility: `com.logstream.ingestion.IngestionTestRunner`

## Prerequisites

- Java 17+
- Maven
- Node.js 18+
- npm

## Quick start

### 1) Start the backend

Run both backend servers with the default ports: gRPC on `50051` and HTTP on `8080`.

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

To run gRPC on a custom port, pass the port as the first application argument:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer" "-Dexec.args=50052"
```

Configure the HTTP API port with `LOGSTREAM_HTTP_PORT`:

```powershell
$env:LOGSTREAM_HTTP_PORT = "8081"
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

The HTTP server and gRPC server use separate ports and share the same SQLite database. Set `LOGSTREAM_DB_PATH` to change the database location. HTTP shutdown allows in-flight requests up to five seconds to finish before stopping its request workers; the gRPC server is also shut down gracefully.

## HTTP log query API

The browser-accessible API is served by the same `LogIngestionServer` process:

```text
GET http://localhost:8080/api/logs
```

Supported query parameters:

| Parameter | Optional | Description |
| --- | --- | --- |
| `level` | Yes | Log level: `INFO`, `WARN`, `ERROR`, or `DEBUG`. |
| `service` | Yes | Exact service name. |
| `startTime` | Yes | Inclusive Unix timestamp in milliseconds. |
| `endTime` | Yes | Exclusive Unix timestamp in milliseconds. |
| `limit` | Yes | Page size; defaults to `100`, maximum `1000`. |
| `offset` | Yes | Number of matching records to skip; defaults to `0`. |

Example request:

```powershell
curl.exe "http://localhost:8080/api/logs?level=ERROR&service=billing-api&limit=50&offset=0"
```

Successful responses are JSON, ordered chronologically (with insertion order used to break timestamp ties). `totalCount` is the count of all records matching the filters, before pagination:

```json
{
  "logs": [
    {
      "timestamp": 1730000000000,
      "level": "ERROR",
      "service": "billing-api",
      "message": "Payment provider request failed",
      "responseTimeMs": 2481,
      "tags": {
        "environment": "production"
      }
    }
  ],
  "limit": 50,
  "offset": 0,
  "totalCount": 1
}
```

Invalid query parameters return HTTP `400` with a JSON `error` message. Unexpected server errors return HTTP `500` with a generic message; details are logged server-side. The default CORS allowlist supports Vite at `http://localhost:5173` and `http://127.0.0.1:5173`. Set `LOGSTREAM_CORS_ORIGINS` to a comma-separated list of exact allowed origins for other browser deployments; setting it to an empty value disables cross-origin access.

The API does not provide authentication. CORS is a browser policy, not access control; keep the service on a trusted network or behind an authenticated reverse proxy before exposing it beyond local development.

### 2) Run the local ingestion test

The included verification runner starts mock services, streams logs concurrently, exercises batch ingestion, and validates the accepted totals.

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner"
```

Override the service count and log count with arguments:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner" "-Dexec.args=8 25000"
```

The default configuration uses four mock services and 10,000 logs per service.

## Database

The gRPC server stores accepted logs in a local SQLite database at `data/logstream.db` by default. The schema stores log events and related tags in separate tables, with indexes for timestamp, level, service, and tag-based events. SQLite creates the database automatically on startup and enables WAL mode for reliable concurrent reads and writes.

The gRPC `GetLogCountsByMinute` endpoint returns minute-start timestamps and log counts for a half-open millisecond range (`start_timestamp_ms <= timestamp < end_timestamp_ms`). Empty minute buckets are included with a zero count, and ranges are limited to 31 days. The SQLite implementation groups records by minute in the database, using the timestamp index before returning chart-ready buckets.

To use a different database file, set `LOGSTREAM_DB_PATH` before starting the server:

```powershell
$env:LOGSTREAM_DB_PATH = "C:\logstream\logs.db"
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

## Run the frontend dashboard

The dashboard runs independently of the Java backend and currently uses mock data for local exploration.

From the repository root:

```powershell
cd frontend
npm install
npm run dev
```

Then open:

- http://localhost:5173

The dashboard supports local filtering examples such as `level:ERROR AND service:billing-api`.

## Repository structure

```text
.
├── src/                 # Java backend source and generated gRPC/protobuf code
├── frontend/            # React + Vite dashboard
├── data/                # Local SQLite database output
├── pom.xml              # Maven configuration
├── README.md            # Project documentation
├── .gitignore           # Git ignore rules
├── target/              # Maven build output
└── out/                 # Additional generated or runtime output
```

## Notes..

- The backend is built for streaming and batch ingestion use cases.
- The frontend currently does not yet connect to the gRPC ingestion service or persisted database.
- Protobuf and Java gRPC classes are generated automatically during the Maven build lifecycle.
