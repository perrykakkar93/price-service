package service;

import com.Perry_SP.model.PriceRecord;
import com.Perry_SP.service.impl.InMemoryPriceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that demonstrate core behavior and a few edge cases.
 */
class InMemoryPriceServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void basicPublishFlow() {
        InMemoryPriceService svc = new InMemoryPriceService();

        String b1 = svc.startBatch();
        PriceRecord p1 = new PriceRecord("ABC", Instant.parse("2025-01-01T10:00:00Z"), json("{\"px\":100}"));
        svc.uploadChunk(b1, Collections.singletonList(p1));
        svc.completeBatch(b1);

        assertThat(svc.getLastPrice("ABC")).isPresent();
    }

    @Test
    void batchVisibilityIsAtomic() {
        InMemoryPriceService svc = new InMemoryPriceService();

        String seed = svc.startBatch();
        svc.uploadChunk(seed, Collections.singletonList(
                new PriceRecord("S", Instant.parse("2025-01-01T09:00:00Z"), json("{\"v\":1}"))));
        svc.completeBatch(seed);

        String b = svc.startBatch();
        PriceRecord newer = new PriceRecord("S", Instant.parse("2025-01-01T10:00:00Z"), json("{\"v\":2}"));
        svc.uploadChunk(b, Collections.singletonList(newer));

        // before complete, published should be old value
        assertThat(svc.getLastPrice("S")).get().satisfies(r -> assertThat(r.getPayload().get("v").intValue()).isEqualTo(1));

        svc.completeBatch(b);
        assertThat(svc.getLastPrice("S")).get().satisfies(r -> assertThat(r.getPayload().get("v").intValue()).isEqualTo(2));
    }

    @Test
    void equalAsOfTieBreaksByBatchSequence() {
        InMemoryPriceService svc = new InMemoryPriceService();

        String a = svc.startBatch();
        svc.uploadChunk(a, Collections.singletonList(new PriceRecord("X", Instant.parse("2025-01-01T10:00:00Z"), json("{\"i\":1}"))));
        svc.completeBatch(a);

        String b = svc.startBatch();
        svc.uploadChunk(b, Collections.singletonList(new PriceRecord("X", Instant.parse("2025-01-01T10:00:00Z"), json("{\"i\":2}"))));
        svc.completeBatch(b);

        assertThat(svc.getLastPrice("X")).isPresent()
                .get().satisfies(r -> assertThat(r.getPayload().get("i").intValue()).isEqualTo(2));
    }

    @Test
    void cancelPreventsPublish() {
        InMemoryPriceService svc = new InMemoryPriceService();
        String b = svc.startBatch();
        svc.uploadChunk(b, Collections.singletonList(new PriceRecord("C", Instant.now(), json("{\"v\":1}"))));
        svc.cancelBatch(b);
        assertThat(svc.getLastPrice("C")).isEmpty();
    }

    @Test
    void chunkLimitEnforced() {
        InMemoryPriceService svc = new InMemoryPriceService();
        String b = svc.startBatch();
        List<PriceRecord> list = new ArrayList<>();
        for (int i=0;i<1001;i++) list.add(new PriceRecord("L"+i, Instant.now(), json("{\"v\":1}")));
        assertThatThrownBy(() -> svc.uploadChunk(b, list)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Chunk size exceeded");
    }

    @Test
    void concurrentCompletes_resolveToLatest() throws Exception {
        InMemoryPriceService svc = new InMemoryPriceService();

        String b1 = svc.startBatch();
        String b2 = svc.startBatch();
        String b3 = svc.startBatch();

        svc.uploadChunk(b1, Collections.singletonList(new PriceRecord("Z", Instant.parse("2025-01-01T10:00:00Z"), json("{\"v\":10}"))));
        svc.uploadChunk(b2, Collections.singletonList(new PriceRecord("Z", Instant.parse("2025-01-01T11:00:00Z"), json("{\"v\":11}"))));
        svc.uploadChunk(b3, Collections.singletonList(new PriceRecord("Z", Instant.parse("2025-01-01T11:00:00Z"), json("{\"v\":12}"))));

        ExecutorService ex = Executors.newFixedThreadPool(3);
        List<Callable<Void>> jobs = Arrays.asList(
                () -> { svc.completeBatch(b1); return null; },
                () -> { svc.completeBatch(b2); return null; },
                () -> { svc.completeBatch(b3); return null; }
        );
        ex.invokeAll(jobs);
        ex.shutdown();

        assertThat(svc.getLastPrice("Z")).isPresent()
                .get().satisfies(r -> assertThat(r.getPayload().get("v").intValue()).isEqualTo(12));
    }

    // helper
    private JsonNode json(String s) {
        try { return mapper.readTree(s); } catch (Exception ex) { throw new RuntimeException(ex); }
    }
}