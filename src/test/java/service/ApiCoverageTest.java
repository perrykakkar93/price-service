package service;

import com.Perry_SP.constants.BatchState;
import com.Perry_SP.model.BatchInfo;
import com.Perry_SP.model.PriceRecord;
import com.Perry_SP.service.PriceService;
import com.Perry_SP.service.impl.InMemoryPriceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Small test that exercises the public API surface so static-analysis/IDE warnings about "unused" methods go away.
 * <p>
 * Updated: also asserts batch metadata accessors (createdAt, sequence).
 */
class ApiCoverageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiSurfaceIsExercised() {
        PriceService svc = new InMemoryPriceService();

        // start a batch and check batch info is available
        String batch = svc.startBatch();
        BatchInfo info = svc.getBatchInfo(batch);
        assertThat(info).isNotNull();
        assertThat(info.getBatchId()).isEqualTo(batch);
        assertThat(info.getState()).isEqualTo(BatchState.STARTED);

        // Use the metadata accessors so static analysis sees they're used
        assertThat(info.getSequence()).isGreaterThan(0);
        assertThat(info.getCreatedAt()).isBeforeOrEqualTo(Instant.now());

        // derive a non-constant px value from the batch id (deterministic but not a literal)
        int px = Math.abs(batch.hashCode()) % 10_000 + 1;

        // upload a chunk (producer API) with a dynamic payload
        PriceRecord rec = new PriceRecord("SYM-API", Instant.parse("2026-01-01T00:00:00Z"), jsonWithPx(px));
        svc.uploadChunk(batch, Collections.singletonList(rec));

        // snapshot before completion should not include staged record
        Map<String, PriceRecord> before = svc.getAllPricesSnapshot();
        assertThat(before).doesNotContainKey("SYM-API");

        // complete and then validate published APIs
        svc.completeBatch(batch);

        // getLastPrice (consumer API) — assert the px we set earlier
        assertThat(svc.getLastPrice("SYM-API")).isPresent()
                .get().satisfies(r -> assertThat(r.getPayload().get("px").intValue()).isEqualTo(px));

        // getAllPricesSnapshot now contains the entry (and returns immutable Map)
        Map<String, PriceRecord> after = svc.getAllPricesSnapshot();
        assertThat(after).containsKey("SYM-API");
        assertThatThrownBy(() -> after.put("x", rec)).isInstanceOf(UnsupportedOperationException.class);

        // getBatchInfo for removed/unknown batch should throw
        assertThatThrownBy(() -> svc.getBatchInfo(batch)).isInstanceOf(RuntimeException.class);
    }

    // Build a small JSON payload dynamically to avoid constant-string inspection warnings.
    private JsonNode jsonWithPx(int px) {
        ObjectNode node = mapper.createObjectNode();
        node.put("px", px);
        return node;
    }
}