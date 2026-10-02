package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record CoverageEvent(
        String batchId,
        List<String> elementIds,
        Long timestamp
) {
}
