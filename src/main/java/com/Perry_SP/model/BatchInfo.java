package com.Perry_SP.model;

import com.Perry_SP.constants.BatchState;

import java.time.Instant;
import java.util.Objects;

/**
 * Lightweight batch metadata for clients.
 */
public final class BatchInfo {
    private final String batchId;
    private final BatchState state;
    private final Instant createdAt;
    private final long sequence;

    public BatchInfo(String batchId, BatchState state, Instant createdAt, long sequence) {
        this.batchId = Objects.requireNonNull(batchId);
        this.state = Objects.requireNonNull(state);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.sequence = sequence;
    }

    public String getBatchId() { return batchId; }
    public BatchState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public long getSequence() { return sequence; }

    @Override
    public String toString() {
        return "BatchInfo{" +
                "batchId='" + batchId + '\'' +
                ", state=" + state +
                ", createdAt=" + createdAt +
                ", sequence=" + sequence +
                '}';
    }
}