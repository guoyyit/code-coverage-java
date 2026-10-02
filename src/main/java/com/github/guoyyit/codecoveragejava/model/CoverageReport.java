package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record CoverageReport(
        String reportId,
        String manifestVersion,
        List<String> batchIds,
        boolean merged,
        long generatedAt,
        int totalStatements,
        int coveredStatements,
        int totalBranches,
        int coveredBranches,
        double lineCoverage,
        double branchCoverage,
        List<FileCoverage> files
) {
}
