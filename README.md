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

The runner starts an ephemeral-port gRPC server, streams logs concurrently from separate mock services, exercises batch ingestion, and verifies all accepted counts. The included in-memory sink is a replaceable boundary for durable storage.

## Run the frontend dashboard

The React/Vite dashboard runs independently of the Java backend. From the repository root:

```powershell
cd frontend
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173) to view the dashboard. The log explorer uses mock data and supports local filtering such as `level:ERROR AND service:billing-api`; it does not yet connect to the ingestion service.
