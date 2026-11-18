package com.logstream.ingestion.fixtures;

import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Thread-safe test observer that records all stream notifications.
 *
 * @param <T> response value type
 */
public final class RecordingStreamObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private int completionCount;

    @Override
    public synchronized void onNext(T value) {
        values.add(value);
    }

    @Override
    public synchronized void onError(Throwable throwable) {
        errors.add(throwable);
    }

    @Override
    public synchronized void onCompleted() {
        completionCount++;
    }

    /**
     * Returns an immutable snapshot of received response values.
     *
     * @return response values received so far
     */
    public synchronized List<T> getValues() {
        return List.copyOf(values);
    }

    /**
     * Returns an immutable snapshot of received errors.
     *
     * @return errors received so far
     */
    public synchronized List<Throwable> getErrors() {
        return List.copyOf(errors);
    }

    /**
     * Returns the first received error, or {@code null} if none was received.
     *
     * @return the first error or {@code null}
     */
    public synchronized Throwable getError() {
        return errors.isEmpty() ? null : errors.get(0);
    }

    /**
     * Returns how many times the observer received {@link #onCompleted()}.
     *
     * @return completion notification count
     */
    public synchronized int getCompletionCount() {
        return completionCount;
    }

    /**
     * Asserts successful completion with exactly one response and returns that response.
     *
     * @return the single response value
     */
    public synchronized T assertSingleSuccessfulValue() {
        assertNull(getError(), "Expected the observer not to receive an error");
        assertEquals(1, completionCount, "Expected exactly one completion notification");
        assertEquals(1, values.size(), "Expected exactly one response value");
        return values.get(0);
    }

    /**
     * Asserts exactly one error and no successful response or completion, then returns the error.
     *
     * @return the single received error
     */
    public synchronized Throwable assertSingleError() {
        assertEquals(1, errors.size(), "Expected exactly one error notification");
        assertEquals(0, completionCount, "Expected no completion after an error");
        assertEquals(0, values.size(), "Expected no response value after an error");
        return errors.get(0);
    }
}
