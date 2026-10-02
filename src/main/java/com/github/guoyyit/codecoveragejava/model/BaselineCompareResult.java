package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record BaselineCompareResult(
        String baselineName,
        String currentManifestVersion,
        CompareConclusion conclusion,
        double baselineLineCoverage,
        double currentLineCoverage,
        double lineCoverageDelta,
        double baselineBranchCoverage,
        double currentBranchCoverage,
        double branchCoverageDelta,
        double thresholdPercent,
        List<CoverableElement> newUncoveredElements
) {
}
