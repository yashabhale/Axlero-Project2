# LogStream Log Ingestion

Java 17 gRPC backend for streaming and batch log ingestion. Protobuf and gRPC Java source files are generated from `src/main/proto/log_ingestion.proto` during Maven builds.

## Run locally

Start the server on the default port (`50051`):

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

Or supply a port as the first application argument:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer" "-Dexec.args=50052"
```

Run the local load/verification runner (defaults to four mock services sending 10,000 logs each):

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner"
```

Override service and log counts with application arguments:

```powershell
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner" "-Dexec.args=8 25000"
```

The runner starts an ephemeral-port gRPC server, streams logs concurrently from separate mock services, exercises batch ingestion, and verifies all accepted counts.

## Database

The gRPC server stores accepted logs in a local SQLite database at `data/logstream.db` by default. The schema stores log events and their tags in related tables, with indexes for timestamp, level, service, and tag searches. SQLite creates the database and schema automatically on startup; WAL mode is enabled for reliable concurrent reads and writes.

Set `LOGSTREAM_DB_PATH` to use a different database file. For example, in PowerShell:

```powershell
$env:LOGSTREAM_DB_PATH = "C:\logstream\logs.db"
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

The dashboard still uses mock data and is not connected to the gRPC service or database.

## Run the frontend dashboard

The React/Vite dashboard runs independently of the Java backend. From the repository root:

```powershell
cd frontend
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173) to view the dashboard. The log explorer uses mock data and supports local filtering such as `level:ERROR AND service:billing-api`; it does not yet connect to the ingestion service.
