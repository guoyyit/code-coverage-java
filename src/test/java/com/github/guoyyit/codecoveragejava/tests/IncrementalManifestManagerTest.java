package com.github.guoyyit.codecoveragejava.tests;

import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.AnalysisResult;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalManifestManagerTest {

    private IncrementalManifestManager newManager(Path dataDir) {
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(dataDir.toString());
        CoverageStore store = new CoverageStore(properties,
                new com.fasterxml.jackson.databind.ObjectMapper());
        store.init();
        return new IncrementalManifestManager(new JavaSourceAnalyzer(), store);
    }

    @Test
    void unchangedFilesAreNotRebuilt(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot.resolve("pkg"));
        Path alpha = sourceRoot.resolve("pkg/Alpha.java");
        Path beta = sourceRoot.resolve("pkg/Beta.java");
        Files.writeString(alpha, "package pkg; class Alpha { int v() { return 1; } }");
        Files.writeString(beta, "package pkg; class Beta { int v() { return 2; } }");

        IncrementalManifestManager manager = newManager(tempDir.resolve("data"));

        AnalysisResult first = manager.analyze(sourceRoot.toString());
        assertEquals(2, first.rebuiltFiles().size());
        assertTrue(first.unchangedFiles().isEmpty());

        AnalysisResult second = manager.analyze(sourceRoot.toString());
        assertTrue(second.rebuiltFiles().isEmpty());
        assertEquals(2, second.unchangedFiles().size());
        assertEquals(first.manifest().version(), second.manifest().version());
    }

    @Test
    void onlyChangedFilesAreRebuiltAndVersionChanges(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Path alpha = sourceRoot.resolve("Alpha.java");
        Path beta = sourceRoot.resolve("Beta.java");
        Files.writeString(alpha, "class Alpha { int v() { return 1; } }");
        Files.writeString(beta, "class Beta { int v() { return 2; } }");

        Path dataDir = tempDir.resolve("data");
        IncrementalManifestManager manager = newManager(dataDir);

        AnalysisResult first = manager.analyze(sourceRoot.toString());

        Files.writeString(alpha, "class Alpha { int v() { return 1 + 1; } }");
        AnalysisResult second = manager.analyze(sourceRoot.toString());

        assertEquals(1, second.rebuiltFiles().size());
        assertEquals("Alpha.java", second.rebuiltFiles().get(0));
        assertEquals(1, second.unchangedFiles().size());
        assertNotEquals(first.manifest().version(), second.manifest().version());

        IncrementalManifestManager restartedManager = newManager(dataDir);
        AnalysisResult afterRestart = restartedManager.analyze(sourceRoot.toString());
        assertTrue(afterRestart.rebuiltFiles().isEmpty(),
                "manifests must survive restart so files stay unchanged: " + afterRestart.rebuiltFiles());
        assertEquals(second.manifest().version(), afterRestart.manifest().version());
    }
}
