package com.github.guoyyit.codecoveragejava.service;

import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.BranchStat;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.FileCoverage;
import com.github.guoyyit.codecoveragejava.model.LineStat;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class ReportService {

    private final CoverageStore store;

    public ReportService(CoverageStore store) {
        this.store = store;
    }

    public CoverageReport batchReport(String batchId) {
        store.lock().readLock().lock();
        try {
            BatchRecord batch = store.loadBatch(batchId);
            if (batch == null) {
                throw new IllegalArgumentException("unknown batch: " + batchId);
            }
            return buildReport(List.of(batch));
        } finally {
            store.lock().readLock().unlock();
        }
    }

    public CoverageReport mergedReport(String manifestVersion) {
        store.lock().readLock().lock();
        try {
            List<BatchRecord> batches = store.listBatches().stream()
                    .filter(batch -> batch.manifestVersion().equals(manifestVersion))
                    .sorted(java.util.Comparator.comparing(BatchRecord::batchId))
                    .toList();
            if (batches.isEmpty()) {
                throw new IllegalArgumentException("no batches for manifest version: " + manifestVersion);
            }
            return buildReport(batches);
        } finally {
            store.lock().readLock().unlock();
        }
    }

    public CoverageReport mergedReport(List<String> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            throw new IllegalArgumentException("batchIds must not be empty");
        }
        store.lock().readLock().lock();
        try {
            List<BatchRecord> batches = new ArrayList<>();
            for (String batchId : new TreeSet<>(batchIds)) {
                BatchRecord batch = store.loadBatch(batchId);
                if (batch == null) {
                    throw new IllegalArgumentException("unknown batch: " + batchId);
                }
                batches.add(batch);
            }
            return buildReport(batches);
        } finally {
            store.lock().readLock().unlock();
        }
    }

    /**
     * Aggregated, unioned hit element ids across the given batches.
     * All batches must belong to the same manifest version.
     */
    public java.util.TreeSet<String> hitsOfBatches(List<String> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            throw new IllegalArgumentException("batchIds must not be empty");
        }
        store.lock().readLock().lock();
        try {
            TreeSet<String> hits = new TreeSet<>();
            String version = null;
            for (String batchId : new TreeSet<>(batchIds)) {
                var batch = store.loadBatch(batchId);
                if (batch == null) {
                    throw new IllegalArgumentException("unknown batch: " + batchId);
                }
                if (version == null) {
                    version = batch.manifestVersion();
                } else if (!version.equals(batch.manifestVersion())) {
                    throw new IllegalArgumentException(
                            "cannot merge batches across different manifest versions");
                }
                hits.addAll(store.loadBatchHits(batchId));
            }
            return hits;
        } finally {
            store.lock().readLock().unlock();
        }
    }

    private CoverageReport buildReport(List<BatchRecord> batches) {
        String manifestVersion = batches.get(0).manifestVersion();
        for (BatchRecord batch : batches) {
            if (!batch.manifestVersion().equals(manifestVersion)) {
                throw new IllegalArgumentException(
                        "cannot merge batches across different manifest versions: "
                                + batch.batchId() + " uses " + batch.manifestVersion());
            }
        }
        Manifest manifest = store.loadManifest(manifestVersion);
        if (manifest == null) {
            throw new IllegalStateException("manifest missing: " + manifestVersion);
        }

        TreeSet<String> hits = new TreeSet<>();
        TreeSet<String> batchIds = new TreeSet<>();
        long generatedAt = 0L;
        for (BatchRecord batch : batches) {
            batchIds.add(batch.batchId());
            hits.addAll(store.loadBatchHits(batch.batchId()));
            generatedAt = Math.max(generatedAt, batch.lastUpdatedAt());
        }

        List<FileCoverage> fileCoverages = new ArrayList<>();
        int totalStatements = 0;
        int coveredStatements = 0;
        int totalBranches = 0;
        int coveredBranches = 0;

        for (var fileManifest : manifest.files()) {
            TreeMap<Integer, List<CoverableElement>> statementsByLine = new TreeMap<>();
            TreeMap<Integer, List<CoverableElement>> branchesByLine = new TreeMap<>();
            for (CoverableElement element : fileManifest.elements()) {
                if (element.type() == ElementType.BRANCH) {
                    branchesByLine.computeIfAbsent(element.line(), key -> new ArrayList<>()).add(element);
                } else {
                    statementsByLine.computeIfAbsent(element.line(), key -> new ArrayList<>()).add(element);
                }
            }

            List<LineStat> lineStats = new ArrayList<>();
            int fileStatements = 0;
            int fileCoveredStatements = 0;
            for (var entry : statementsByLine.entrySet()) {
                List<String> ids = entry.getValue().stream().map(CoverableElement::id).sorted().toList();
                boolean covered = ids.stream().anyMatch(hits::contains);
                lineStats.add(new LineStat(entry.getKey(), covered, ids));
                fileStatements += ids.size();
                fileCoveredStatements += (int) ids.stream().filter(hits::contains).count();
            }

            List<BranchStat> branchStats = new ArrayList<>();
            int fileBranches = 0;
            int fileCoveredBranches = 0;
            for (var entry : branchesByLine.entrySet()) {
                for (CoverableElement element : entry.getValue()) {
                    boolean covered = hits.contains(element.id());
                    branchStats.add(new BranchStat(element.id(), element.line(),
                            element.branchIndex(), covered, element.description()));
                    fileBranches++;
                    if (covered) {
                        fileCoveredBranches++;
                    }
                }
            }

            fileCoverages.add(new FileCoverage(fileManifest.sourcePath(), fileStatements,
                    fileCoveredStatements, fileBranches, fileCoveredBranches, lineStats, branchStats));
            totalStatements += fileStatements;
            coveredStatements += fileCoveredStatements;
            totalBranches += fileBranches;
            coveredBranches += fileCoveredBranches;
        }

        double lineCoverage = totalStatements == 0 ? 0.0
                : roundPercentage(coveredStatements * 100.0 / totalStatements);
        double branchCoverage = totalBranches == 0 ? 0.0
                : roundPercentage(coveredBranches * 100.0 / totalBranches);
        String reportId = reportId(manifestVersion, batchIds, hits);
        return new CoverageReport(reportId, manifestVersion, List.copyOf(batchIds), batches.size() > 1,
                generatedAt, totalStatements, coveredStatements, totalBranches,
                coveredBranches, lineCoverage, branchCoverage, List.copyOf(fileCoverages));
    }

    private double roundPercentage(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String reportId(String manifestVersion, Set<String> batchIds, Set<String> hits) {
        String input = manifestVersion + "|" + String.join(",", batchIds) + "|" + String.join(",", hits);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return "r" + HexFormat.of().formatHex(hash).substring(0, 20);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
