package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record FileManifest(
        String sourcePath,
        String contentHash,
        List<CoverableElement> elements
) {
}
