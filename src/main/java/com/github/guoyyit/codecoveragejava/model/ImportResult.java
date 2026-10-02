package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record ImportResult(
        String sourceRoot,
        String batchId,
        int eventCount,
        int distinctElementCount,
        List<String> elementIds
) {
}
