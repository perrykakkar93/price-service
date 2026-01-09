package com.Perry_SP.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable price record.
 */
public final class PriceRecord {
    private final String id;
    private final Instant asOf;
    private final JsonNode payload;

    public PriceRecord(String id, Instant asOf, JsonNode payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public String getId() {
        return id;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public JsonNode getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "PriceRecord{" +
                "id='" + id + '\'' +
                ", asOf=" + asOf +
                ", payload=" + payload +
                '}';
    }
}