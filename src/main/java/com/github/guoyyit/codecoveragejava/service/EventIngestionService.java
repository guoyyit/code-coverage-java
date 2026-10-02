package com.github.guoyyit.codecoveragejava.service;

import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class EventIngestionService {

    private final CoverageStore store;

    public EventIngestionService(CoverageStore store) {
        this.store = store;
    }

    /**
     * Ingest one event. Events may arrive out of order, duplicated or late.
     * Returns the number of newly covered elements added by this event.
     */
    public int ingest(CoverageEvent event) {
        if (event == null || event.batchId() == null || event.batchId().isBlank()) {
            throw new IllegalArgumentException("event.batchId must not be blank");
        }
        List<String> incoming = event.elementIds() == null ? List.of() : event.elementIds();
        store.lock().writeLock().lock();
        try {
            BatchRecord batch = store.loadBatch(event.batchId());
            if (batch == null) {
                throw new IllegalArgumentException("unknown batch: " + event.batchId());
            }
            if (batch.status() == BatchStatus.COMPLETED) {
                throw new IllegalStateException("batch already completed, late events rejected: "
                        + event.batchId());
            }
            Manifest manifest = store.loadManifest(batch.manifestVersion());
            if (manifest == null) {
                throw new IllegalStateException("manifest missing for batch: " + batch.manifestVersion());
            }
            Set<String> knownIds = collectElementIds(manifest);
            TreeSet<String> hits = new TreeSet<>(store.loadBatchHits(event.batchId()));
            int before = hits.size();
            for (String elementId : incoming) {
                if (elementId == null || elementId.isBlank()) {
                    throw new IllegalArgumentException("elementId must not be blank");
                }
                if (!knownIds.contains(elementId)) {
                    throw new IllegalArgumentException(
                            "element does not belong to batch manifest version "
                                    + batch.manifestVersion() + ": " + elementId);
                }
                hits.add(elementId);
            }
            if (hits.size() != before) {
                store.saveBatchHits(event.batchId(), batch.manifestVersion(), List.copyOf(hits));
                store.saveBatch(new BatchRecord(batch.batchId(), batch.manifestVersion(), batch.status(),
                        batch.createdAt(), System.currentTimeMillis()));
            }
            return hits.size() - before;
        } finally {
            store.lock().writeLock().unlock();
        }
    }

    public int totalDistinctHits(String batchId) {
        store.lock().readLock().lock();
        try {
            BatchRecord batch = store.loadBatch(batchId);
            if (batch == null) {
                throw new IllegalArgumentException("unknown batch: " + batchId);
            }
            return store.loadBatchHits(batchId).size();
        } finally {
            store.lock().readLock().unlock();
        }
    }

    private Set<String> collectElementIds(Manifest manifest) {
        Set<String> ids = new TreeSet<>();
        manifest.files().forEach(file -> file.elements().stream()
                .map(CoverableElement::id)
                .forEach(ids::add));
        return ids;
    }
}
