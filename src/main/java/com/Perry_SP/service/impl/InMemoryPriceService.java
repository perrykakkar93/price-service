package com.Perry_SP.service.impl;

import com.Perry_SP.constants.BatchState;
import com.Perry_SP.exceptions.BatchExceptions.ChunkSizeExceededException;
import com.Perry_SP.exceptions.BatchExceptions.BatchNotFoundException;
import com.Perry_SP.exceptions.BatchExceptions.InvalidBatchStateException;
import com.Perry_SP.model.BatchInfo;
import com.Perry_SP.model.PriceRecord;
import com.Perry_SP.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Simple in-memory implementation:
 * - producers stage records in batches
 * - completeBatch merges staged records into a snapshot visible to consumers atomically
 * <p>
 * Merge rule: later asOf wins; if asOf equal, the batch with larger sequence (started later) wins.
 */
public class InMemoryPriceService implements PriceService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPriceService.class);
    private static final int DEFAULT_MAX_CHUNK = 1000;

    private final int maxChunk;
    private final AtomicReference<Map<String, PublishedEntry>> published = new AtomicReference<>(Collections.emptyMap());
    private final ConcurrentHashMap<String, BatchRun> batches = new ConcurrentHashMap<>();
    private final AtomicLong seqGenerator = new AtomicLong();

    public InMemoryPriceService() {
        this(DEFAULT_MAX_CHUNK);
    }

    public InMemoryPriceService(int maxChunk) {
        if (maxChunk <= 0) throw new IllegalArgumentException("maxChunk must be > 0");
        this.maxChunk = maxChunk;
    }

    @Override
    public String startBatch() {
        String batchId = UUID.randomUUID().toString();
        long seq = seqGenerator.incrementAndGet();
        BatchRun run = new BatchRun(batchId, seq, Instant.now());
        batches.put(batchId, run);
        log.debug("startBatch id={} seq={}", batchId, seq);
        return batchId;
    }

    @Override
    public void uploadChunk(String batchId, List<PriceRecord> chunk) {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(chunk, "chunk");
        if (chunk.size() > maxChunk) throw new ChunkSizeExceededException(chunk.size(), maxChunk);

        BatchRun run = batches.get(batchId);
        if (run == null) throw new BatchNotFoundException(batchId);
        run.addChunk(chunk);
        log.debug("uploadChunk batch={} size={}", batchId, chunk.size());
    }

    @Override
    public void completeBatch(String batchId) {
        Objects.requireNonNull(batchId, "batchId");
        BatchRun run = batches.get(batchId);
        if (run == null) throw new BatchNotFoundException(batchId);

        Map<String, Staged> staged = run.complete(); // throws if invalid state
        if (staged.isEmpty()) {
            batches.remove(batchId);
            log.debug("completeBatch {} - nothing to publish", batchId);
            return;
        }

        // Merge staged into published snapshot atomically.
        // Using updateAndGet is simple and ensures atomic visibility.
        published.updateAndGet(current -> {
            Map<String, PublishedEntry> next = new HashMap<>(current);
            for (Map.Entry<String, Staged> e : staged.entrySet()) {
                String id = e.getKey();
                Staged s = e.getValue();
                PublishedEntry existing = next.get(id);
                if (shouldReplace(existing, s)) {
                    next.put(id, new PublishedEntry(s.record, s.seq));
                }
            }
            return Collections.unmodifiableMap(next);
        });

        batches.remove(batchId);
        log.debug("completeBatch {} published {} entries", batchId, staged.size());
    }

    @Override
    public void cancelBatch(String batchId) {
        Objects.requireNonNull(batchId, "batchId");
        BatchRun run = batches.get(batchId);
        if (run == null) throw new BatchNotFoundException(batchId);
        run.cancel();
        batches.remove(batchId);
        log.debug("cancelBatch {}", batchId);
    }

    @Override
    public Optional<PriceRecord> getLastPrice(String id) {
        Objects.requireNonNull(id, "id");
        PublishedEntry e = published.get().get(id);
        return e == null ? Optional.empty() : Optional.of(e.record);
    }

    @Override
    public Map<String, PriceRecord> getAllPricesSnapshot() {
        // Build a new map and return an unmodifiable view so callers cannot mutate internal state.
        Map<String, PriceRecord> out = published.get().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().record));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public BatchInfo getBatchInfo(String batchId) {
        Objects.requireNonNull(batchId, "batchId");
        BatchRun run = batches.get(batchId);
        if (run == null) throw new BatchNotFoundException(batchId);
        return run.info();
    }

    private boolean shouldReplace(PublishedEntry existing, Staged staged) {
        if (existing == null) return true;
        int cmp = staged.record.getAsOf().compareTo(existing.record.getAsOf());
        if (cmp > 0) return true;
        if (cmp < 0) return false;
        return staged.seq > existing.sourceSeq;
    }

    // Published entry stores the record and the source batch sequence used for tie-breaks.
    private static class PublishedEntry {
        final PriceRecord record;
        final long sourceSeq;

        PublishedEntry(PriceRecord record, long sourceSeq) {
            this.record = record;
            this.sourceSeq = sourceSeq;
        }
    }

    // Staged item (record + originating batch sequence)
    private static class Staged {
        final PriceRecord record;
        final long seq;

        Staged(PriceRecord record, long seq) {
            this.record = record;
            this.seq = seq;
        }
    }

    // Batch state - staged records are kept per-batch; synchronized for safety when multiple uploads occur.
    private static class BatchRun {
        private final String id;
        private final long seq;
        private final Instant created;
        private BatchState state = BatchState.STARTED;
        private final Map<String, Staged> staged = new HashMap<>();

        BatchRun(String id, long seq, Instant created) {
            this.id = Objects.requireNonNull(id);
            this.seq = seq;
            this.created = Objects.requireNonNull(created);
        }

        synchronized void addChunk(List<PriceRecord> chunk) {
            if (state != BatchState.STARTED) {
                throw new InvalidBatchStateException("batch " + id + " not accepting uploads (state=" + state + ")");
            }
            for (PriceRecord r : chunk) {
                Staged cur = staged.get(r.getId());
                if (cur == null || r.getAsOf().isAfter(cur.record.getAsOf())) {
                    staged.put(r.getId(), new Staged(r, seq));
                }
            }
        }

        synchronized Map<String, Staged> complete() {
            if (state == BatchState.COMPLETED) {
                throw new InvalidBatchStateException("batch " + id + " already completed");
            }
            if (state == BatchState.CANCELLED) {
                throw new InvalidBatchStateException("batch " + id + " was cancelled");
            }
            state = BatchState.COMPLETED;
            return new HashMap<>(staged);
        }

        synchronized void cancel() {
            if (state == BatchState.COMPLETED) {
                throw new InvalidBatchStateException("batch " + id + " already completed, cannot cancel");
            }
            if (state == BatchState.CANCELLED) return;
            staged.clear();
            state = BatchState.CANCELLED;
        }

        synchronized BatchInfo info() {
            return new BatchInfo(id, state, created, seq);
        }
    }
}