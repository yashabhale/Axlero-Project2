# Axlero Project 2

## LogStream Log Ingestion

Axlero Project 2 is a full-stack log ingestion and dashboard demo built with a Java 17 gRPC backend and a React/Vite frontend. The backend accepts streaming and batch log payloads, validates them, stores accepted records in SQLite, and exposes ingestion through generated protobuf/gRPC interfaces.

The Java sources are generated from `src/main/proto/log_ingestion.proto` during Maven builds, so you do not need to maintain generated gRPC classes manually.

## Features

- Streaming log ingestion over gRPC
- Batch ingestion support for large log sets
- SQLite persistence with indexed lookup support
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

Run the service on the default gRPC port (`50051`):

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

To run it on a custom port, pass the port as the first application argument:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer" "-Dexec.args=50052"
```

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
