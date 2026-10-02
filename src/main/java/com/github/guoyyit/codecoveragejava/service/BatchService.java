package com.github.guoyyit.codecoveragejava.service;

import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class BatchService {

    private final IncrementalManifestManager manifestManager;
    private final CoverageStore store;

    public BatchService(IncrementalManifestManager manifestManager, CoverageStore store) {
        this.manifestManager = manifestManager;
        this.store = store;
    }

    public BatchRecord openBatch(String batchId, String sourceRoot) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        store.lock().writeLock().lock();
        try {
            BatchRecord existing = store.loadBatch(batchId);
            if (existing != null) {
                throw new IllegalStateException("batch already exists: " + batchId
                        + " (status=" + existing.status() + ")");
            }
            Manifest manifest = manifestManager.analyze(sourceRoot).manifest();
            long now = System.currentTimeMillis();
            BatchRecord batch = new BatchRecord(batchId, manifest.version(), BatchStatus.OPEN, now, now);
            store.saveBatch(batch);
            store.saveBatchHits(batchId, manifest.version(), List.of());
            return batch;
        } finally {
            store.lock().writeLock().unlock();
        }
    }

    public BatchRecord completeBatch(String batchId) {
        store.lock().writeLock().lock();
        try {
            BatchRecord batch = requireBatch(batchId);
            if (batch.status() == BatchStatus.COMPLETED) {
                throw new IllegalStateException("batch already completed: " + batchId);
            }
            BatchRecord updated = new BatchRecord(batch.batchId(), batch.manifestVersion(),
                    BatchStatus.COMPLETED, batch.createdAt(), System.currentTimeMillis());
            store.saveBatch(updated);
            return updated;
        } finally {
            store.lock().writeLock().unlock();
        }
    }

    public List<BatchRecord> listBatches() {
        store.lock().readLock().lock();
        try {
            return store.listBatches().stream()
                    .sorted(Comparator.comparing(BatchRecord::batchId))
                    .toList();
        } finally {
            store.lock().readLock().unlock();
        }
    }

    public BatchRecord getBatch(String batchId) {
        store.lock().readLock().lock();
        try {
            return requireBatch(batchId);
        } finally {
            store.lock().readLock().unlock();
        }
    }

    private BatchRecord requireBatch(String batchId) {
        BatchRecord batch = store.loadBatch(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("unknown batch: " + batchId);
        }
        return batch;
    }
}
