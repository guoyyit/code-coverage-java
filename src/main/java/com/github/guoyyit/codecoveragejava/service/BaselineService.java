package com.github.guoyyit.codecoveragejava.service;

import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.BaselineCompareResult;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.CompareConclusion;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class BaselineService {

    private final CoverageStore store;
    private final ReportService reportService;
    private final CoverageProperties properties;

    public BaselineService(CoverageStore store, ReportService reportService, CoverageProperties properties) {
        this.store = store;
        this.reportService = reportService;
        this.properties = properties;
    }

    public Baseline saveBaseline(String name, List<String> batchIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("baseline name must not be blank");
        }
        store.lock().writeLock().lock();
        try {
            CoverageReport report = (batchIds == null || batchIds.isEmpty())
                    ? latestMergedReport()
                    : reportService.mergedReport(batchIds);
            Baseline baseline = new Baseline(name, report.manifestVersion(), report, System.currentTimeMillis());
            store.saveBaseline(baseline);
            return baseline;
        } finally {
            store.lock().writeLock().unlock();
        }
    }

    public Baseline getBaseline(String name) {
        store.lock().readLock().lock();
        try {
            Baseline baseline = store.loadBaseline(name);
            if (baseline == null) {
                throw new IllegalArgumentException("unknown baseline: " + name);
            }
            return baseline;
        } finally {
            store.lock().readLock().unlock();
        }
    }

    public List<Baseline> listBaselines() {
        store.lock().readLock().lock();
        try {
            return store.listBaselines().stream()
                    .sorted(Comparator.comparing(Baseline::name))
                    .toList();
        } finally {
            store.lock().readLock().unlock();
        }
    }

    public BaselineCompareResult compare(String baselineName, List<String> currentBatchIds) {
        store.lock().readLock().lock();
        try {
            Baseline baseline = store.loadBaseline(baselineName);
            if (baseline == null) {
                throw new IllegalArgumentException("unknown baseline: " + baselineName);
            }
            List<String> resolvedBatchIds = resolveCurrentBatchIds(baseline, currentBatchIds);
            CoverageReport current = resolvedBatchIds.isEmpty()
                    ? emptyReport(baseline.manifestVersion())
                    : reportService.mergedReport(resolvedBatchIds);
            Set<String> currentHits = resolvedBatchIds.isEmpty()
                    ? new TreeSet<>()
                    : reportService.hitsOfBatches(resolvedBatchIds);

            Set<String> baselineElementIds = elementIdsOf(baseline.report().manifestVersion());

            List<CoverableElement> newUncovered = new ArrayList<>();
            Manifest currentManifest = store.loadManifest(current.manifestVersion());
            if (currentManifest != null) {
                for (var file : currentManifest.files()) {
                    for (CoverableElement element : file.elements()) {
                        if (!baselineElementIds.contains(element.id())
                                && !currentHits.contains(element.id())) {
                            newUncovered.add(element);
                        }
                    }
                }
            }
            newUncovered.sort(Comparator
                    .comparing(CoverableElement::sourceFile)
                    .thenComparingInt(CoverableElement::line)
                    .thenComparingInt(element -> element.type() == ElementType.BRANCH
                            ? element.branchIndex() : -1));

            double lineDelta = round(current.lineCoverage() - baseline.report().lineCoverage());
            double branchDelta = round(current.branchCoverage() - baseline.report().branchCoverage());
            double threshold = properties.getRegressionThresholdPercent();

            boolean regressed = lineDelta < -threshold
                    || (current.totalBranches() > 0 && branchDelta < -threshold);
            boolean addedUncovered = !newUncovered.isEmpty();
            CompareConclusion conclusion;
            if (regressed) {
                conclusion = CompareConclusion.REGRESSED_OVER_THRESHOLD;
            } else if (addedUncovered) {
                conclusion = CompareConclusion.NEW_CODE_UNCOVERED;
            } else if (lineDelta > 0 || branchDelta > 0) {
                conclusion = CompareConclusion.IMPROVED;
            } else {
                conclusion = CompareConclusion.MATCHED;
            }

            return new BaselineCompareResult(baselineName, current.manifestVersion(), conclusion,
                    baseline.report().lineCoverage(), current.lineCoverage(), lineDelta,
                    baseline.report().branchCoverage(), current.branchCoverage(), branchDelta,
                    threshold, List.copyOf(newUncovered));
        } finally {
            store.lock().readLock().unlock();
        }
    }

    private List<String> resolveCurrentBatchIds(Baseline baseline, List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().sorted().toList();
        }
        return store.listBatches().stream()
                .filter(batch -> batch.manifestVersion().equals(baseline.manifestVersion()))
                .map(BatchRecord::batchId)
                .sorted()
                .toList();
    }

    private CoverageReport latestMergedReport() {
        List<BatchRecord> batches = store.listBatches();
        if (batches.isEmpty()) {
            throw new IllegalStateException("no batches available to build a baseline");
        }
        TreeSet<String> versions = new TreeSet<>();
        batches.forEach(batch -> versions.add(batch.manifestVersion()));
        String latestVersion = versions.last();
        return reportService.mergedReport(latestVersion);
    }

    private CoverageReport emptyReport(String manifestVersion) {
        Manifest manifest = store.loadManifest(manifestVersion);
        List<com.github.guoyyit.codecoveragejava.model.FileCoverage> files = new ArrayList<>();
        int totalStatements = 0;
        int totalBranches = 0;
        if (manifest != null) {
            for (var file : manifest.files()) {
                int statements = 0;
                int branches = 0;
                for (CoverableElement element : file.elements()) {
                    if (element.type() == ElementType.BRANCH) {
                        branches++;
                    } else {
                        statements++;
                    }
                }
                files.add(new com.github.guoyyit.codecoveragejava.model.FileCoverage(
                        file.sourcePath(), statements, 0, branches, 0, List.of(), List.of()));
                totalStatements += statements;
                totalBranches += branches;
            }
        }
        return new CoverageReport("empty", manifestVersion, List.of(), false,
                0L, totalStatements, 0, totalBranches, 0, 0.0, 0.0,
                List.copyOf(files));
    }

    private Set<String> elementIdsOf(String manifestVersion) {
        Set<String> ids = new TreeSet<>();
        Manifest manifest = store.loadManifest(manifestVersion);
        if (manifest == null) {
            return ids;
        }
        for (var file : manifest.files()) {
            file.elements().stream().map(CoverableElement::id).forEach(ids::add);
        }
        return ids;
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
