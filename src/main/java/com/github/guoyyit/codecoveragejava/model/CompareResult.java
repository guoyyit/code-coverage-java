package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record CompareResult(
        String baselineName,
        String manifestVersion,
        CompareVerdict verdict,
        double baselineOverallPercent,
        double currentOverallPercent,
        double thresholdPercent,
        List<String> reasons,
        List<CoverableElement> uncoveredNewElements,
        int newElementCount,
        int uncoveredNewElementCount
) {
}
