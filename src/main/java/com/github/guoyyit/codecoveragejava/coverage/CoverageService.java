package com.github.guoyyit.codecoveragejava.coverage;

import com.github.guoyyit.codecoveragejava.analysis.ManifestService;
import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.storage.CoverageStorage;
import com.github.guoyyit.codecoveragejava.util.Ids;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class CoverageService {

    private final ManifestService manifestService;
    private final CoverageStorage storage;

    private final Map<String, CoverageBatch> batches = new TreeMap<>();

    public CoverageService(ManifestService manifestService, CoverageStorage storage) {
        this.manifestService = manifestService;
        this.storage = storage;
        for (CoverageBatch batch : storage.loadBatches()) {
            batches.put(batch.batchId(), batch);
        }
    }

    public synchronized CoverageBatch createBatch(String requestedBatchId) {
        SourceManifest manifest = manifestService.requireCurrentManifest();
        String batchId = requestedBatchId == null || requestedBatchId.isBlank()
                ? Ids.shortHash("batch|" + manifest.versionId() + "|" + System.nanoTime())
                : requestedBatchId.trim();
        validateBatchId(batchId);
        if (batches.containsKey(batchId)) {
            throw CoverageApiException.conflict("批次已存在: " + batchId);
        }
        CoverageBatch batch = new CoverageBatch(
                batchId, manifest.versionId(), System.currentTimeMillis(),
                BatchStatus.OPEN, 0L, new java.util.TreeSet<>());
        batches.put(batchId, batch);
        storage.saveBatch(batch);
        return batch;
    }

    public synchronized CoverageBatch getBatch(String batchId) {
        CoverageBatch batch = batches.get(batchId);
        if (batch == null) {
            throw CoverageApiException.notFound("批次不存在: " + batchId);
        }
        return batch;
    }

    public synchronized List<CoverageBatch> listBatches() {
        return batches.values().stream()
                .sorted(Comparator.comparingLong(CoverageBatch::createdAt))
                .toList();
    }

    public synchronized CoverageBatch finishBatch(String batchId) {
        CoverageBatch batch = getBatch(batchId);
        batch.finish(System.currentTimeMillis());
        storage.saveBatch(batch);
        return batch;
    }

    public synchronized int uploadEvents(String batchId, List<CoverageEvent> events) {
        CoverageBatch batch = getBatch(batchId);
        if (batch.status() == BatchStatus.FINISHED) {
            throw CoverageApiException.conflict("批次已结束，不能再上报事件: " + batchId);
        }
        if (events == null || events.isEmpty()) {
            throw CoverageApiException.badRequest("事件列表不能为空");
        }
        SourceManifest manifest = manifestService.getManifest(batch.manifestVersion());
        int added = 0;
        for (CoverageEvent event : events) {
            if (event == null || event.elementId() == null || event.elementId().isBlank()) {
                throw CoverageApiException.badRequest("存在缺少 elementId 的覆盖事件");
            }
            String elementId = event.elementId().trim();
            if (manifest.findElement(elementId) == null) {
                throw CoverageApiException.badRequest(
                        "元素不属于批次绑定的清单版本 " + batch.manifestVersion() + ": " + elementId);
            }
            if (batch.markHit(elementId)) {
                added++;
            }
        }
        if (added > 0) {
            storage.saveBatch(batch);
        }
        return added;
    }

    public synchronized CoverageReport batchReport(String batchId) {
        CoverageBatch batch = getBatch(batchId);
        SourceManifest manifest = manifestService.getManifest(batch.manifestVersion());
        return ReportGenerator.forBatches(manifest, List.of(batch));
    }

    public synchronized CoverageReport aggregateReport(String manifestVersion) {
        String version = manifestVersion == null
                ? manifestService.requireCurrentManifest().versionId()
                : manifestVersion;
        SourceManifest manifest = manifestService.getManifest(version);
        List<CoverageBatch> matching = batches.values().stream()
                .filter(batch -> batch.manifestVersion().equals(version))
                .filter(batch -> !batch.frozen())
                .sorted(Comparator.comparingLong(CoverageBatch::createdAt))
                .toList();
        return ReportGenerator.forBatches(manifest, matching);
    }

    public synchronized java.util.SortedSet<String> coveredElementIds(String manifestVersion) {
        String version = manifestVersion == null
                ? manifestService.requireCurrentManifest().versionId()
                : manifestVersion;
        return coveredElementIds(manifestVersion, false);
    }

    public synchronized java.util.SortedSet<String> allCoveredElementIds(String manifestVersion) {
        return coveredElementIds(manifestVersion, true);
    }

    private java.util.SortedSet<String> coveredElementIds(String manifestVersion, boolean includeFrozen) {
        String version = manifestVersion == null
                ? manifestService.requireCurrentManifest().versionId()
                : manifestVersion;
        java.util.SortedSet<String> hit = new java.util.TreeSet<>();
        for (CoverageBatch batch : batches.values()) {
            if (batch.manifestVersion().equals(version) && (includeFrozen || !batch.frozen())) {
                hit.addAll(batch.hitElementIds());
            }
        }
        return hit;
    }

    public synchronized void freezeBatchesOfVersion(String manifestVersion) {
        for (CoverageBatch batch : batches.values()) {
            if (batch.manifestVersion().equals(manifestVersion) && !batch.frozen()) {
                batch.freeze();
                storage.saveBatch(batch);
            }
        }
    }

    public synchronized boolean hasUnfrozenBatches(String manifestVersion) {
        return batches.values().stream()
                .anyMatch(batch -> batch.manifestVersion().equals(manifestVersion) && !batch.frozen());
    }

    synchronized CoverageBatch importBatch(String batchId, String manifestVersion,
                                           java.util.SortedSet<String> hitElementIds) {
        validateBatchId(batchId);
        if (batches.containsKey(batchId)) {
            throw CoverageApiException.conflict("批次已存在，导入被拒绝: " + batchId);
        }
        CoverageBatch batch = new CoverageBatch(
                batchId, manifestVersion, System.currentTimeMillis(), "IMPORT",
                BatchStatus.FINISHED, System.currentTimeMillis(), false,
                new java.util.TreeSet<>(hitElementIds));
        batches.put(batchId, batch);
        storage.saveBatch(batch);
        return batch;
    }

    private void validateBatchId(String batchId) {
        if (batchId.contains("/") || batchId.contains("\\") || batchId.contains("..")
                || batchId.length() > 128) {
            throw CoverageApiException.badRequest("非法批次 ID: " + batchId);
        }
    }
}
