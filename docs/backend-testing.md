# Backend Testing

## 1. Introduction

This document describes the existing LogStream backend testing setup, its current test coverage, and how to run the tests. It reflects the test and implementation files present in the project; it does not imply that the tests were executed as part of this documentation task.

## 2. Backend Testing Overview

The backend tests use two complementary approaches:

- **Service tests** instantiate `LogIngestionServiceImpl` directly and use in-memory fixtures to inspect persistence calls and gRPC observer notifications without starting a network server.
- **SQLite tests** create temporary database files and exercise `SqliteLogSink` against the real SQLite JDBC driver. They also call the aggregation RPC directly for an end-to-end check of aggregation through the SQLite sink.

The current tests cover batch ingestion, request validation, persistence and aggregation. The `ingestLog` client-streaming RPC is implemented, but no existing test class exercises it.

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
  service/LogIngestionServiceImplTest.java
  validation/LogIngestionValidationTest.java
```

The protocol defines unary batch ingestion and aggregation RPCs, plus a client-streaming ingestion RPC. The service delegates storage and counting to the `LogSink` interface. Its production implementations include SQLite-backed and in-memory sinks.

## 5. Reusable Test Fixtures

### `LogMessageFixtures`

`LogMessageFixtures` provides deterministic protobuf `LogMessage` instances. `validLog()` supplies a valid default message; `validLogAt(long)` and `validLogWithLevel(LogLevel)` vary common fields; and `create(...)` builds a message from explicit values, including values that may intentionally be invalid.

Using one known-good default reduces test setup duplication. Tests can use protobuf builders to change only the field relevant to a scenario.

### `RecordingLogSink`

`RecordingLogSink` is a thread-safe in-memory `LogSink` used in service tests. It records successfully accepted messages, records every attempted `acceptAll` batch (including failed attempts), and exposes batch sizes and invocation counts. Its configurable persistence failure lets service tests check error mapping without inducing an actual database failure. It also implements minute-count aggregation over accepted messages.

### `RecordingStreamObserver`

`RecordingStreamObserver<T>` is a thread-safe gRPC `StreamObserver` test double. It records response values, errors, and completion notifications, and provides accessors and assertion helpers for common success and error outcomes. This allows service tests to verify the complete RPC response lifecycle without a network connection.

## 6. Service-Level Testing

`LogIngestionServiceImplTest` covers successful batch ingestion and response completion, preservation of multiple tags on separate messages, minute aggregation with a zero-count minute, rejection of an empty aggregation range, and mapping a simulated sink failure to gRPC `INTERNAL`.

The class uses `LogMessageFixtures`, `RecordingLogSink`, and `RecordingStreamObserver`. These are direct service tests: they construct `LogIngestionServiceImpl` with a fixture sink and invoke its methods without starting the gRPC server.

## 7. Log Validation Testing

`LogIngestionValidationTest` checks the validation behavior applied to batch records:

- Rejects an unspecified log level.
- Rejects blank service names and blank messages.
- Rejects negative response times, while accepting response times of zero and `Integer.MAX_VALUE`.
- Rejects zero and negative timestamps.
- Rejects a mixed batch containing a valid record and an invalid record before calling the sink, so none of that batch is persisted.

For invalid requests, the tests verify gRPC `INVALID_ARGUMENT`, no success response or completion, and no accepted records. The tests use `LogMessageFixtures`, `RecordingLogSink`, and `RecordingStreamObserver`. Although the service also rejects an unrecognized enum value, there is no explicit test for that case.

## 8. Batch Ingestion Testing

`LogIngestionBatchTest` focuses on batch-specific behavior. It verifies that an empty batch succeeds with an accepted count of zero, valid single- and multi-record batches preserve their messages and report the correct count, an invalid record rejects the entire batch before persistence, and a simulated persistence failure returns `INTERNAL` without a success response.

The tests also inspect attempted batch sizes and `acceptAll` invocation counts, distinguishing validation rejection (no sink call) from a persistence failure (one attempted sink call). The class uses all three reusable fixtures.

## 9. Streaming Ingestion Testing

`LogIngestionServiceImpl.ingestLog` implements client-streaming ingestion. It validates each incoming message, accumulates valid messages in batches of up to 256, flushes a full batch to the sink, and flushes remaining pending messages on normal stream completion. A successful completion reports the accepted count.

If a message is invalid, the service attempts to flush earlier valid pending messages and then reports `INVALID_ARGUMENT`; a failure during that flush reports `INTERNAL`. Sink failures while processing or completing the stream also result in `INTERNAL`. When the inbound stream is cancelled or fails, pending messages are flushed on a best-effort basis and failures are logged.

These are implemented behaviors, not current test coverage: none of the existing tests calls `ingestLog`. Buffer thresholds, final flushes, accepted counts, cancellation, validation failures, and sink failures for the streaming RPC should therefore be treated as unverified by the current test suite.

## 10. SQLite Persistence Testing

`SqliteLogSinkTest` uses JUnit's `@TempDir` to create isolated file-backed databases. It verifies that log fields and tag key/value pairs can be read through new JDBC connections after the sink is closed, and that a batch containing a schema-invalid record is rolled back rather than leaving earlier records in that batch persisted.

The class also tests minute counts from the SQLite sink for a requested time range and calls the gRPC aggregation method with the SQLite sink to check that an empty minute is represented by a zero-count bucket. These tests exercise the actual SQLite schema and JDBC implementation rather than an in-memory substitute.

## 11. Error Handling and Edge Cases

Covered error and boundary scenarios include invalid batch fields, mixed-validity batches, an empty batch, simulated sink failures, an aggregation request with equal start and end times, and a SQLite constraint failure partway through a batch. Tests assert relevant gRPC status codes and verify that error paths do not produce success responses.

Coverage is not exhaustive. For example, there are no explicit tests for negative aggregation start times, reversed ranges, ranges longer than 31 days, unrecognized log-level enum values, SQLite connection/configuration failures, or the streaming RPC's error paths.

## 12. Aggregation Testing

The service validates that an aggregation start time is non-negative, the end is greater than the start, and the range does not exceed 31 days. It requests counts from the sink and returns a bucket for each minute intersecting the requested half-open interval, filling missing minutes with zero.

Existing tests verify a service response with populated and empty minutes, rejection of an equal-boundary range, SQLite grouping within a requested interval, and zero-filled minutes when aggregation is requested through the service backed by SQLite. There is no separate test class for `InMemoryLogSink` aggregation, and the 31-day limit and other invalid range boundaries are not explicitly tested.

## 13. How to Run Tests

Run commands from the `Axlero-Project2` project root. With Java 17 and Maven available, run the complete backend test suite with:

```bash
mvn test
```

Maven's test lifecycle also compiles the Protobuf and gRPC sources required by the backend. To run one test class, use Surefire's `-Dtest` selector, for example:

```bash
mvn -Dtest=LogIngestionServiceImplTest test
mvn -Dtest=LogIngestionValidationTest test
mvn -Dtest=LogIngestionBatchTest test
mvn -Dtest=SqliteLogSinkTest test
```

The repository does not contain a Maven wrapper. Neither the full test suite nor these targeted commands were run for this documentation task.

## 14. Current Limitations

- The client-streaming ingestion RPC has no tests.
- Tests primarily exercise service behavior with fixtures; they do not start a gRPC server or test transport-level behavior.
- Several validation and aggregation boundaries are implemented but not explicitly tested, including unrecognized log levels and the 31-day aggregation maximum.
- SQLite tests cover basic persistence, batch rollback, and aggregation, but not concurrent access, connection setup failures, or broader schema constraints and tag relationships.
- The test suite has not been executed or verified in this task, so no passing status is claimed.

## 15. Future Testing Improvements

Potential additions, clearly outside current coverage, include:

- Focused streaming tests for batches below, at, and above the 256-message flush threshold; completion flushes; invalid messages; cancellation; and persistence failures.
- Aggregation boundary tests for negative starts, reversed or zero-length intervals, the 31-day maximum, interval edges, and large bucket ranges.
- Explicit validation coverage for unrecognized log levels and additional whitespace and field-boundary cases.
- SQLite tests for tag-to-log association, foreign-key behavior, and relevant connection or transaction failures.
- A gRPC integration test that starts an in-process or local test server to verify serialization and transport behavior in addition to direct service calls.

## 16. Conclusion

The existing backend suite combines isolated service tests and file-backed SQLite tests. It provides concrete coverage for batch validation, batch responses, persistence, transaction rollback, and aggregation. Streaming ingestion and several boundary and integration scenarios remain untested; those are opportunities for future work, not claims about current test behavior.
