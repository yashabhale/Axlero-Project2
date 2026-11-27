# LogStream: Distributed Log Analytics & Alerting Platform

A production-ready observability platform for high-throughput log ingestion, full-text search with Apache Lucene, real-time alerting, and a React/ECharts dashboard.

## Architecture Overview

```
┌─────────────┐     gRPC      ┌──────────────────┐     ┌─────────────┐
│  Microservices │────────────▶│  Log Ingestion   │────▶│   SQLite    │
│  (Producers)  │  (port 50051) │   (Java 21)      │     │  (Storage)  │
└─────────────┘               └────────┬─────────┘     └─────────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                  ▼
            ┌─────────────┐    ┌──────────────┐   ┌─────────────┐
            │   Lucene    │    │   Alerting   │   │  REST API   │
            │  (Index)    │    │  (Scheduler) │   │  (Gateway)  │
            └─────────────┘    └──────────────┘   └──────┬──────┘
                                                         │
                    ┌────────────────────────────────────┘
                    ▼
            ┌─────────────┐
            │   React     │
            │  Dashboard  │
            │ (port 5173) │
            └─────────────┘
```

## Core Modules

### 1. Log Ingestion (Java & gRPC)
- **High-throughput binary log ingestion** via gRPC streaming/batch APIs
- **Structured log fields**: `timestamp`, `level`, `service`, `response_time`, `message`, `tags` (metadata)
- **Thread-safe handoff** to indexing with dual-write to SQLite + Lucene
- **Validation**: Required fields, enum levels, numeric ranges

### 2. Search & Indexing (Apache Lucene 9.x)
- **Embedded Lucene** with proper field types:
  - `StringField` for exact matches (level, service, traceId)
  - `TextField` for full-text search (message)
  - `LongPoint` for numeric range queries (response_time, timestamp)
- **IndexWriter/SearcherManager** lifecycle management with NRT (near-real-time) search
- **Query parsing**: Boolean operators (`AND`, `OR`, `NOT`), field filters, numeric comparisons (`>`, `>=`, `<`, `<=`)
- **Sorting/pagination** with configurable page sizes
- **Histogram/bucket aggregations** for time-series visualizations

### 3. Alerting Engine (Java ScheduledExecutorService)
- **Scheduled task runner** with per-rule configurable intervals
- **Query/threshold evaluation** over sliding time windows
- **Cooldown/deduplication** logic to prevent alert spam
- **Webhook/email dispatchers** with configurable properties
- **Alert history** with acknowledgment workflow

### 4. Analytics Dashboard (React 18 + ECharts)
- **Query input bar** with LQL (Log Query Language) syntax highlighting
- **Time-range selector** with presets and custom ranges
- **ECharts time-series histogram** for log volume visualization
- **Paginated/searchable log table** with expandable details
- **Alert rule CRUD/status UI** connected to backend REST API

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+
- Node.js 18+
- npm 9+

### Option 1: Docker Compose (Recommended)

```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f logstream-backend

# Access dashboard
open http://localhost:5173
```

### Option 2: Local Development

#### Backend
```bash
# Build and run
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"

# Or with custom config
LOGSTREAM_DB_PATH=data/logstream.db \
LOGSTREAM_LUCENE_PATH=data/lucene-index \
LOGSTREAM_REST_PORT=8080 \
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.LogIngestionServer"
```

#### Frontend
```bash
cd frontend
npm install
npm run dev
# Open http://localhost:5173
```

## Configuration

### Backend Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LOGSTREAM_DB_PATH` | `data/logstream.db` | SQLite database path |
| `LOGSTREAM_LUCENE_PATH` | `data/lucene-index` | Lucene index directory |
| `LOGSTREAM_LUCENE_RAM_BUFFER_MB` | `256` | IndexWriter RAM buffer |
| `LOGSTREAM_LUCENE_MAX_BUFFERED_DOCS` | `1000` | Max buffered docs before flush |
| `LOGSTREAM_LUCENE_FLUSH_INTERVAL_DOCS` | `1000` | Auto-flush interval |
| `LOGSTREAM_SEARCH_DEFAULT_PAGE_SIZE` | `50` | Default search page size |
| `LOGSTREAM_SEARCH_MAX_PAGE_SIZE` | `500` | Maximum page size |
| `LOGSTREAM_ALERT_SCHEDULER_POOL_SIZE` | `4` | Alert scheduler thread pool |
| `LOGSTREAM_ALERT_WEBHOOK_TIMEOUT_SECONDS` | `10` | Webhook HTTP timeout |
| `LOGSTREAM_SMTP_HOST` | `` | SMTP server for email alerts |
| `LOGSTREAM_SMTP_PORT` | `587` | SMTP port |
| `LOGSTREAM_SMTP_USERNAME` | `` | SMTP username |
| `LOGSTREAM_SMTP_PASSWORD` | `` | SMTP password |
| `LOGSTREAM_EMAIL_FROM` | `alerts@logstream.local` | From address for emails |
| `LOGSTREAM_REST_PORT` | `8080` | REST gateway port |

### Frontend Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_BASE` | `http://localhost:8080/api/v1` | Backend REST API base URL |

## API Reference

### gRPC Services (Port 50051)

#### LogIngestionService
- `IngestLogs(stream LogBatch) returns (IngestResult)` - Streaming ingestion
- `IngestBatch(LogBatch) returns (IngestResult)` - Batch ingestion

#### LogSearchService
- `SearchLogs(SearchLogsRequest) returns (SearchLogsResponse)` - Full-text search
- `GetHistogram(GetHistogramRequest) returns (GetHistogramResponse)` - Time-series buckets

#### AlertManagementService
- `CreateAlertRule(CreateAlertRuleRequest) returns (CreateAlertRuleResponse)`
- `GetAlertRule(GetAlertRuleRequest) returns (GetAlertRuleResponse)`
- `ListAlertRules(ListAlertRulesRequest) returns (ListAlertRulesResponse)`
- `UpdateAlertRule(UpdateAlertRuleRequest) returns (UpdateAlertRuleResponse)`
- `DeleteAlertRule(DeleteAlertRuleRequest) returns (DeleteAlertRuleResponse)`
- `TriggerAlertRule(TriggerAlertRuleRequest) returns (TriggerAlertRuleResponse)`
- `GetAlertHistory(GetAlertHistoryRequest) returns (GetAlertHistoryResponse)`
- `AcknowledgeAlert(AcknowledgeAlertRequest) returns (AcknowledgeAlertResponse)`

### REST API (Port 8080)

All endpoints under `/api/v1/`:

#### Search
```
POST   /search              - Search logs
GET    /histogram           - Get time-series histogram
```

#### Alerts
```
GET    /alerts              - List alert rules
POST   /alerts              - Create alert rule
GET    /alerts/{id}         - Get alert rule
PUT    /alerts/{id}         - Update alert rule
DELETE /alerts/{id}         - Delete alert rule
GET    /alerts/{id}/history - Get alert history
POST   /alerts/{id}/trigger - Manually trigger alert
POST   /alerts/history/{id}/acknowledge - Acknowledge alert
```

#### Health
```
GET    /health              - Health check
```

## Log Query Language (LQL)

The platform supports a powerful query syntax:

```
# Field filters
level:ERROR
service:billing-api
response_time:>1000
response_time:>=500
response_time:<100
response_time:<=200

# Full-text search
"database connection timeout"
error OR exception

# Boolean operators
level:ERROR AND service:billing-api
level:ERROR OR level:WARN
level:ERROR AND NOT service:health-check

# Complex combinations
level:ERROR AND service:billing-api AND response_time:>1000
(service:billing-api OR service:payment-api) AND level:ERROR
```

## Alert Rule Configuration

```json
{
  "name": "Billing API Error Spike",
  "query": "level:ERROR AND service:billing-api",
  "thresholdCount": 10,
  "windowMinutes": 5,
  "scheduleSeconds": 60,
  "cooldownMinutes": 5,
  "enabled": true,
  "webhookUrl": "https://hooks.slack.com/services/...",
  "emailRecipients": "oncall@example.com,team@example.com"
}
```

- **thresholdCount**: Number of matching logs to trigger alert
- **windowMinutes**: Sliding time window for evaluation
- **scheduleSeconds**: How often to evaluate the rule
- **cooldownMinutes**: Minimum time between alert firings
- **webhookUrl**: Optional HTTP webhook for notifications
- **emailRecipients**: Optional comma-separated email addresses

## Testing

### Run Integration Tests
```bash
# Generates synthetic logs including ERROR spike for billing-api
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IntegrationTestRunner"
```

### Run Unit Tests
```bash
mvn test
```

### Load Testing
```bash
# 8 services, 25000 logs each
mvn compile exec:java "-Dexec.mainClass=com.logstream.ingestion.IngestionTestRunner" "-Dexec.args=8 25000"
```

## Project Structure

```
.
├── src/
│   ├── main/
│   │   ├── java/com/logstream/
│   │   │   ├── ingestion/          # gRPC ingestion service
│   │   │   ├── index/              # Lucene indexing & search
│   │   │   ├── alert/              # Alerting engine
│   │   │   └── gateway/            # REST API gateway
│   │   ├── proto/                  # Protocol Buffers definitions
│   │   └── resources/
│   │       └── db/schema.sql       # SQLite schema
│   └── test/                       # Unit & integration tests
├── frontend/
│   ├── src/
│   │   ├── api/                    # API client & endpoints
│   │   ├── components/             # React components
│   │   ├── hooks/                  # Custom React hooks
│   │   ├── pages/                  # Page components
│   │   └── styles.css              # Global styles
│   ├── package.json
│   └── vite.config.js
├── docker-compose.yml
├── Dockerfile.backend
├── frontend/Dockerfile.frontend
├── frontend/nginx.conf
├── webhook-test.conf
├── pom.xml
└── README.md
```

## Production Considerations

### Scaling
- **Horizontal scaling**: Run multiple ingestion nodes behind a load balancer
- **Lucene**: Use `SearcherManager` with `SearcherFactory` for NRT across nodes
- **Alerting**: Use distributed lock (e.g., Redis) for leader election in HA mode

### Reliability
- **Index durability**: Configure `IndexWriterConfig.setCommitOnClose(true)`
- **Backpressure**: gRPC flow control + bounded queues in `LuceneLogSink`
- **Monitoring**: Export metrics via Micrometer/Prometheus

### Security
- **TLS**: Enable gRPC TLS with mutual authentication
- **Authentication**: Add JWT validation in REST gateway
- **Authorization**: Role-based access for alert management

## License

MIT License - see LICENSE file for details.
