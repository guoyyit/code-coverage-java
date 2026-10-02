package com.github.guoyyit.codecoveragejava.coverage;

import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.FileCoverage;
import com.github.guoyyit.codecoveragejava.model.FileManifest;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ReportGenerator {

    private ReportGenerator() {
    }

    public static CoverageReport forBatches(SourceManifest manifest, List<CoverageBatch> batches) {
        Set<String> hit = new TreeSet<>();
        List<String> batchIds = new ArrayList<>();
        for (CoverageBatch batch : batches) {
            if (!batch.manifestVersion().equals(manifest.versionId())) {
                throw new IllegalArgumentException(
                        "批次 " + batch.batchId() + " 属于清单版本 " + batch.manifestVersion()
                                + "，不能并入版本 " + manifest.versionId() + " 的报告");
            }
            hit.addAll(batch.hitElementIds());
            batchIds.add(batch.batchId());
        }
        batchIds.sort(String::compareTo);

        List<FileCoverage> fileCoverages = new ArrayList<>();
        int totalLines = 0;
        int coveredLines = 0;
        int totalBranches = 0;
        int coveredBranches = 0;

        for (FileManifest file : manifest.files()) {
            List<CoverableElement> lineElements = new ArrayList<>();
            List<CoverableElement> branchElements = new ArrayList<>();
            for (CoverableElement element : file.elements()) {
                if (element.type() == ElementType.LINE) {
                    lineElements.add(element);
                } else {
                    branchElements.add(element);
                }
            }
            List<CoverableElement> uncoveredLines = new ArrayList<>();
            List<CoverableElement> uncoveredBranches = new ArrayList<>();
            int fileCoveredLines = 0;
            int fileCoveredBranches = 0;
            for (CoverableElement element : lineElements) {
                if (hit.contains(element.id())) {
                    fileCoveredLines++;
                } else {
                    uncoveredLines.add(element);
                }
            }
            for (CoverableElement element : branchElements) {
                if (hit.contains(element.id())) {
                    fileCoveredBranches++;
                } else {
                    uncoveredBranches.add(element);
                }
            }
            fileCoverages.add(new FileCoverage(
                    file.sourcePath(),
                    lineElements.size(),
                    fileCoveredLines,
                    branchElements.size(),
                    fileCoveredBranches,
                    List.copyOf(uncoveredLines),
                    List.copyOf(uncoveredBranches)));
            totalLines += lineElements.size();
            coveredLines += fileCoveredLines;
            totalBranches += branchElements.size();
            coveredBranches += fileCoveredBranches;
        }

        double linePercent = percent(coveredLines, totalLines);
        double branchPercent = percent(coveredBranches, totalBranches);
        int total = totalLines + totalBranches;
        int covered = coveredLines + coveredBranches;
        double overallPercent = percent(covered, total);

        return new CoverageReport(
                manifest.versionId(),
                List.copyOf(batchIds),
                totalLines,
                coveredLines,
                totalBranches,
                coveredBranches,
                linePercent,
                branchPercent,
                overallPercent,
                List.copyOf(fileCoverages));
    }

    private static double percent(int covered, int total) {
        if (total == 0) {
            return 100.0d;
        }
        return BigDecimal.valueOf(covered * 100.0d / total)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
