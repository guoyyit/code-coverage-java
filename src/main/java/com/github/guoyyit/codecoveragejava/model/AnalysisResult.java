package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record AnalysisResult(
        Manifest manifest,
        List<String> rebuiltFiles,
        List<String> unchangedFiles
) {
}
