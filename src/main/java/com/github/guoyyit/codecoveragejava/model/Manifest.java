package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record Manifest(
        String version,
        String sourceRoot,
        List<FileManifest> files
) {
}
