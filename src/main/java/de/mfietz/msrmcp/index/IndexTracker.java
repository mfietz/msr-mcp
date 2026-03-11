package de.mfietz.msrmcp.index;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe state machine tracking the lifecycle of the background index run.
 *
 * <p>Transitions: {@code NOT_STARTED → INDEXING → READY | ERROR}
 *
 * <p>Created in {@code Main} and passed to tools so they can guard against returning incomplete
 * data while the index is still being built.
 */
public final class IndexTracker {

    public enum State {
        NOT_STARTED,
        INDEXING,
        READY,
        ERROR
    }

    private record Snapshot(State state, long startedAtMs, long elapsedMs, String errorMessage) {}

    private final AtomicReference<Snapshot> ref =
            new AtomicReference<>(new Snapshot(State.NOT_STARTED, 0L, 0L, null));

    /** Transitions to {@code INDEXING}, recording the current timestamp. */
    public void markIndexing() {
        ref.set(new Snapshot(State.INDEXING, System.currentTimeMillis(), 0L, null));
    }

    /** Transitions to {@code READY}. {@code elapsedMs} is the total indexing duration. */
    public void markReady(long elapsedMs) {
        Snapshot prev = ref.get();
        ref.set(new Snapshot(State.READY, prev.startedAtMs(), elapsedMs, null));
    }

    /** Transitions to {@code ERROR}. */
    public void markError(String errorMessage) {
        Snapshot prev = ref.get();
        ref.set(new Snapshot(State.ERROR, prev.startedAtMs(), 0L, errorMessage));
    }

    /** Returns {@code true} only when the index is fully built and ready for queries. */
    public boolean isReady() {
        return ref.get().state() == State.READY;
    }

    public State state() {
        return ref.get().state();
    }

    public long startedAtMs() {
        return ref.get().startedAtMs();
    }

    public long elapsedMs() {
        return ref.get().elapsedMs();
    }

    public String errorMessage() {
        return ref.get().errorMessage();
    }
}
