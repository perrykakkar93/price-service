package com.Perry_SP.service;

import com.Perry_SP.model.BatchInfo;
import com.Perry_SP.model.PriceRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-JVM API used by producers and consumers.
 */
public interface PriceService {

    String startBatch();

    void uploadChunk(String batchId, List<PriceRecord> chunk);

    void completeBatch(String batchId);

    void cancelBatch(String batchId);

    Optional<PriceRecord> getLastPrice(String id);

    Map<String, PriceRecord> getAllPricesSnapshot();

    BatchInfo getBatchInfo(String batchId);
}