# Axlero Project 2

## LogStream Log Ingestion

This repository contains a Java 17 gRPC-based log ingestion service and a React/Vite dashboard for exploring log data. The backend accepts streaming and batch log payloads, stores accepted records in SQLite, and exposes ingestion through generated protobuf/gRPC interfaces.

The Java sources are generated from `src/main/proto/log_ingestion.proto` during Maven builds, so you do not need to maintain stubs manually.

## Project overview

- Backend: Java + gRPC + Protobuf + SQLite
- Frontend: React + Vite
- Main server entry point: `com.logstream.ingestion.LogIngestionServer`
- Local verification utility: `com.logstream.ingestion.IngestionTestRunner`

## Prerequisites

- Java 17+
- Maven
- Node.js 18+
- npm

## Quick start

### 1) Start the backend

Run the server on the default gRPC port (`50051`):

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

To run the server on a custom port, pass the port as the first application argument:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer" "-Dexec.args=50052"
```

### 2) Run the local ingestion test

The included load/verification runner starts mock services, streams logs concurrently, exercises batch ingestion, and checks accepted totals.

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner"
```

Override the number of mock services and log messages:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner" "-Dexec.args=8 25000"
```

The default configuration uses four mock services and 10,000 logs per service.

## Database

The gRPC server writes accepted logs to a local SQLite database at `data/logstream.db` by default. The schema stores log events and related tags in separate tables, with indexes for timestamp, level, service, and tag-based queries. SQLite creates the database and schema automatically on startup and enables WAL mode for reliable concurrent reads and writes.

To set a custom database path, use the `LOGSTREAM_DB_PATH` environment variable:

```powershell
$env:LOGSTREAM_DB_PATH = "C:\logstream\logs.db"
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

## Run the frontend dashboard

The dashboard runs independently of the Java backend and is not currently connected to the gRPC service or SQLite database.

From the repository root:

```powershell
cd frontend
npm install
npm run dev
```

Then open:

- http://localhost:5173

The explorer supports local mock-data filtering such as `level:ERROR AND service:billing-api`.

## Repository structure

```text
.
├── src/                 # Java backend sources and protobuf definitions
├── frontend/            # React + Vite dashboard
├── data/                # Local database output directory
├── pom.xml              # Maven build configuration
├── README.md            # Project documentation
└── target/              # Maven build artifacts
```

## Notes

- The backend is designed for streaming and batch ingestion workflows.
- The frontend currently uses synthetic/mock data for local dashboard exploration.
- Protobuf and gRPC Java classes are generated automatically during the Maven build lifecycle.
