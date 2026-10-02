package com.github.guoyyit.codecoveragejava.analysis;

import com.github.guoyyit.codecoveragejava.model.FileManifest;
import java.io.IOException;
import java.nio.file.Path;

public interface SourceAnalyzer {

    FileManifest analyzeFile(String relativePath, Path file) throws IOException;

    int getParsedFileCount();

    void resetStats();
}
