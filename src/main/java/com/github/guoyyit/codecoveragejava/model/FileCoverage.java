package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record FileCoverage(
        String sourcePath,
        int totalLines,
        int coveredLines,
        int totalBranches,
        int coveredBranches,
        List<CoverableElement> uncoveredLines,
        List<CoverableElement> uncoveredBranches
) {
}
