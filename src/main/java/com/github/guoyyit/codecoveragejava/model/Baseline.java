package com.github.guoyyit.codecoveragejava.model;

public record Baseline(
        String name,
        String manifestVersion,
        CoverageReport report,
        long createdAt
) {
}
