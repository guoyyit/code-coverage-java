package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record ImportCoverageData(
        String formatVersion,
        String manifestVersion,
        String batchId,
        Integer hitCount,
        List<String> hitElementIds
) {
    public static final String FORMAT_VERSION = "1.0";
}
