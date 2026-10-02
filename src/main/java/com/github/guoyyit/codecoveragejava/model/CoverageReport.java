package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record CoverageReport(
        String manifestVersion,
        List<String> batchIds,
        int totalLines,
        int coveredLines,
        int totalBranches,
        int coveredBranches,
        double lineCoveragePercent,
        double branchCoveragePercent,
        double overallCoveragePercent,
        List<FileCoverage> files
) {
}
