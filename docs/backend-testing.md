# Backend Testing

## 1. Introduction

This document describes the existing LogStream backend testing setup, its current test coverage, and how to run the tests. It reflects the test and implementation files present in the project; it does not imply that the tests were executed as part of this documentation task.

## 2. Backend Testing Overview

The backend tests use three complementary approaches:

- **Service tests** instantiate `LogIngestionServiceImpl` directly and use in-memory fixtures to inspect persistence calls and gRPC observer notifications without starting a network server.
- **SQLite tests** create temporary database files and exercise `SqliteLogSink` against the real SQLite JDBC driver. They also call the aggregation RPC directly for an end-to-end check of aggregation through the SQLite sink.
- **gRPC integration tests** start a real gRPC server on an ephemeral port, connect generated client stubs, and verify RPC behavior and SQLite persistence over the transport.

The current tests cover batch and client-streaming ingestion, request validation, persistence, error handling, and aggregation. Direct service tests are complemented by transport-level tests in the integration package.

## 3. Testing Frameworks and Dependencies

The project is configured for Java 17 and uses:

- **JUnit Jupiter 5.11.3** (`junit-jupiter`, test scope) for test discovery, `@Test`, `@TempDir`, and assertions.
- **Maven Surefire 3.5.2** to run tests in Maven's `test` phase.
- **SQLite JDBC 3.47.1.0** for the file-backed persistence tests.
- **gRPC 1.68.1** and **Protobuf 3.25.5** for the service and generated request/response types used by the tests.
- **protobuf-maven-plugin 0.6.1** to generate Java and gRPC sources from the protocol definition during the Maven build.

No separate mocking library is configured. Service tests use hand-written fixtures instead. The tests have not been executed for this documentation task.

## 4. Project Testing Structure

Relevant backend files are organized as follows:

```text
src/main/java/com/logstream/ingestion/
  LogIngestionServiceImpl.java
  LogSink.java
  SqliteLogSink.java
  InMemoryLogSink.java
  TimeSeriesBucket.java
src/main/proto/
  log_ingestion.proto
src/main/resources/db/
  schema.sql
src/test/java/com/logstream/ingestion/
  SqliteLogSinkTest.java
  batch/LogIngestionBatchTest.java
  fixtures/
    LogMessageFixtures.java
    RecordingLogSink.java
    RecordingStreamObserver.java
  integration/
    LogIngestionGrpcIntegrationTest.java
  service/LogIngestionServiceImplTest.java
  validation/LogIngestionValidationTest.java
```

The protocol defines unary batch ingestion and aggregation RPCs, plus a client-streaming ingestion RPC. The service delegates storage and counting to the `LogSink` interface. Service tests use recording fixtures; SQLite and gRPC integration tests use the real SQLite sink.

## 5. Reusable Test Fixtures

### `LogMessageFixtures`

`LogMessageFixtures` provides deterministic protobuf `LogMessage` instances. `validLog()` supplies a valid default message; `validLogAt(long)` and `validLogWithLevel(LogLevel)` vary common fields; and `create(...)` builds a message from explicit values, including values that may intentionally be invalid.

Using one known-good default reduces test setup duplication. Tests can use protobuf builders to change only the field relevant to a scenario.

### `RecordingLogSink`

`RecordingLogSink` is a thread-safe in-memory `LogSink` used in service tests. It records successfully accepted messages, records every attempted `acceptAll` batch (including failed attempts), and exposes batch sizes and invocation counts. Its configurable persistence failure lets service tests check error mapping without inducing an actual database failure. It also implements minute-count aggregation over accepted messages.

### `RecordingStreamObserver`

`RecordingStreamObserver<T>` is a thread-safe gRPC `StreamObserver` test double. It records response values, errors, and completion notifications, and provides accessors and assertion helpers for common success and error outcomes. This allows service tests to verify the complete RPC response lifecycle without a network connection.

## 6. Service-Level Testing

`LogIngestionServiceImplTest` directly invokes the service with `RecordingLogSink` and `RecordingStreamObserver`. It covers successful batch responses and tag preservation; aggregation counts, chronological buckets, zero-count minutes, and invalid ranges; batch persistence failures and retry; and client-streaming completion, empty streams, invalid messages, sink failures, and the 256-message flush threshold with a final remainder. These tests verify service behavior and observer notifications without starting a network server.

## 7. Log Validation Testing

`LogIngestionValidationTest` checks the validation behavior applied to batch records:

- Rejects unspecified and unrecognized log levels.
- Rejects blank service names and blank messages.
- Rejects negative response times, while accepting response times of zero and `Integer.MAX_VALUE`.
- Rejects zero and negative timestamps.
- Rejects a mixed batch containing a valid record and an invalid record before calling the sink, so none of that batch is persisted.

For invalid requests, the tests verify gRPC `INVALID_ARGUMENT`, no success response or completion, and no accepted records. The tests use `LogMessageFixtures`, `RecordingLogSink`, and `RecordingStreamObserver`.

## 8. Batch Ingestion Testing

`LogIngestionBatchTest` focuses on batch-specific behavior. It verifies that an empty batch succeeds with an accepted count of zero, valid single- and multi-record batches preserve their messages and report the correct count, an invalid record rejects the entire batch before persistence, and a simulated persistence failure returns `INTERNAL` without a success response. The integration test also sends a valid multi-record batch through the blocking gRPC stub and checks its accepted count and persisted rows.

The tests also inspect attempted batch sizes and `acceptAll` invocation counts, distinguishing validation rejection (no sink call) from a persistence failure (one attempted sink call). The class uses `LogMessageFixtures`, `RecordingLogSink`, and `RecordingStreamObserver`. Empty-batch behavior is also covered over gRPC by the integration test.

## 9. Streaming Ingestion Testing

`LogIngestionServiceImplTest` verifies streaming behavior directly: successful completion and accepted counts, an empty stream, flushing 256 messages plus a one-message remainder, persistence of earlier valid messages before an invalid message returns `INVALID_ARGUMENT`, and `INTERNAL` on a sink failure at completion.

`LogIngestionGrpcIntegrationTest` additionally sends multiple messages through the generated asynchronous client-streaming stub and checks the accepted count and persisted rows. Its invalid-stream test sends a valid log followed by an invalid one, waits with a bounded latch for the RPC error, and verifies `INVALID_ARGUMENT`, no successful response/completion, and persistence of only the earlier valid log.

For each test, `@BeforeEach` creates a `SqliteLogSink` using a database file in JUnit's `@TempDir`, starts `ServerBuilder.forPort(0)`, and creates generated blocking and asynchronous client stubs with five-second RPC deadlines. `@AfterEach` shuts down and awaits the channel and server, escalating to `shutdownNow()` only if needed, then closes the sink. JUnit removes the temporary directory after the test.

The service also attempts a best-effort flush when the inbound stream is cancelled or fails. That `onError` path is not explicitly tested.

## 10. SQLite Persistence Testing

`SqliteLogSinkTest` uses JUnit's `@TempDir` for isolated file-backed databases and try-with-resources for sinks and JDBC connections. It verifies that log fields and tag key/value pairs remain readable through new JDBC connections after the sink closes (persistence across connections), that special characters are stored intact, and that an empty write does not prevent later writes.

The rollback test submits a valid record followed by one that violates the SQLite schema and checks that neither the record nor its tags remain persisted. Aggregation tests verify SQLite minute grouping, chronological ordering, and half-open `[start, end)` boundaries: records before `start` and at or after `end` are excluded. A direct service call backed by SQLite also verifies that aggregation includes empty minutes as zero-count buckets.

## 11. Error Handling and Edge Cases

Service tests cover invalid batch fields, mixed-validity batches, sink failures, invalid streaming messages, and invalid aggregation ranges. The gRPC integration tests verify `INVALID_ARGUMENT` over the transport for an invalid batch, an invalid streamed log, and an aggregation request whose start equals its end. The streaming integration test also checks the service contract of flushing an earlier valid record before rejecting the invalid one. SQLite constraint failure and transaction rollback are covered by `SqliteLogSinkTest`.

The integration test verifies that an empty batch succeeds with accepted count zero and persists no rows. The unit batch test separately checks its observer completion and sink invocation behavior.

## 12. Aggregation Testing

The service validates that an aggregation start time is non-negative, the end is greater than the start, and the range does not exceed 31 days. It returns a bucket for each minute intersecting the half-open interval `[start, end)`, fills missing minutes with zero, and orders response buckets chronologically.

`LogIngestionServiceImplTest` checks exact counts, multiple minutes, zero-filled buckets, and invalid ranges including a negative start and a range longer than 31 days. `SqliteLogSinkTest` verifies grouped counts and half-open interval boundaries, including exclusion of a record at `end`. The gRPC integration test inserts deterministic logs through the client, checks exact counts and chronological buckets over gRPC, and separately verifies an equal-boundary request returns `INVALID_ARGUMENT`.

## 13. How to Run Tests

Run commands from the `Axlero-Project2` project root. With Java 17 and Maven installed, run the complete backend test suite with:

```bash
mvn test
```

Maven's test lifecycle also compiles the Protobuf and gRPC sources required by the backend. To run one test class, use Surefire's `-Dtest` selector, for example:

```bash
mvn -Dtest=LogIngestionServiceImplTest test
mvn -Dtest=LogIngestionValidationTest test
mvn -Dtest=LogIngestionBatchTest test
mvn -Dtest=SqliteLogSinkTest test
mvn -Dtest=LogIngestionGrpcIntegrationTest test
```

The repository does not contain a Maven wrapper. The test code has not been executed as part of this documentation task; no passing status is claimed.

## 14. Current Limitations

- The integration test starts a real gRPC server with `ServerBuilder.forPort(0)` and the production service, but does not invoke `LogIngestionServer.main` or verify its environment-variable/default-port startup behavior.
- The streaming cancellation/transport failure callback and some persistence failure paths are not explicitly tested over gRPC.
- The integration suite checks equal aggregation bounds; a reversed range is not tested over the transport. Service-level tests cover negative starts and ranges longer than 31 days.
- SQLite tests do not cover concurrent access, connection setup failures, or every schema and tag relationship constraint.
- The test code has not been executed or verified as part of this documentation task, so no passing status is claimed.

## 15. Future Testing Improvements

Potential additions, clearly outside current coverage, include:

- Streaming tests for cancellation and additional transport/persistence failure paths.
- A gRPC integration test for `LogIngestionServer.main` startup configuration, if that lifecycle is made testable without relying on fixed ports or production database files.
- A reversed-range aggregation integration case and broader SQLite concurrency, connection-failure, and schema relationship tests.

## 16. Conclusion

The existing backend suite combines direct service tests, file-backed SQLite tests, and real gRPC transport integration tests. It covers batch and client-streaming behavior, validation and error statuses, persistence and transaction rollback, and time-series aggregation. Remaining gaps are listed above. The tests have not been executed as part of this documentation task.
