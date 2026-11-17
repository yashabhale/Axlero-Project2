package com.logstream.ingestion.fixtures;

import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

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

    public synchronized List<T> getValues() {
        return List.copyOf(values);
    }

    public synchronized List<Throwable> getErrors() {
        return List.copyOf(errors);
    }

    public synchronized Throwable getError() {
        return errors.isEmpty() ? null : errors.get(0);
    }

    public synchronized int getCompletionCount() {
        return completionCount;
    }
}
