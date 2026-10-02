package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record Baseline(
        String name,
        String manifestVersion,
        long createdAt,
        List<String> coveredElementIds,
        int totalLines,
        int coveredLines,
        int totalBranches,
        int coveredBranches
) {
}
