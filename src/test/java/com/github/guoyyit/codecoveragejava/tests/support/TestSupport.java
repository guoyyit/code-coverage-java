package com.github.guoyyit.codecoveragejava.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analysis.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.analysis.ManifestService;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.coverage.BaselineService;
import com.github.guoyyit.codecoveragejava.coverage.CoverageService;
import com.github.guoyyit.codecoveragejava.coverage.ImportService;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.storage.CoverageStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class TestSupport {

    private TestSupport() {
    }

    public static final String SAMPLE_SOURCE = """
            package demo;
            public class Sample {
                public String grade(int score) {
                    if (score >= 60) {
                        return "pass";
                    }
                    return "fail";
                }
                public int sum(int n) {
                    int total = 0;
                    for (int i = 0; i < n; i++) {
                        total += i;
                    }
                    return total;
                }
            }
            """;

    public static Environment newEnvironment(Path dataDir, Path sourceDir) {
        return newEnvironment(dataDir, sourceDir, 1.0d);
    }

    public static Environment newEnvironment(Path dataDir, Path sourceDir, double threshold) {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(sourceDir);
            Files.writeString(sourceDir.resolve("Sample.java"), SAMPLE_SOURCE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(dataDir.toString());
        properties.setRegressionThresholdPercent(threshold);
        ObjectMapper objectMapper = new ObjectMapper();
        CoverageStorage storage = new CoverageStorage(properties, objectMapper);
        JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();
        ManifestService manifestService = new ManifestService(storage, analyzer);
        CoverageService coverageService = new CoverageService(manifestService, storage);
        BaselineService baselineService =
                new BaselineService(manifestService, coverageService, storage, properties);
        ImportService importService = new ImportService(
                manifestService, coverageService, objectMapper);
        return new Environment(properties, storage, analyzer, manifestService,
                coverageService, baselineService, importService, sourceDir);
    }

    public static Environment newCustomEnvironment(Path dataDir, Path sourceDir,
                                                   String fileName, String content, double threshold) {
        Environment env = newEnvironment(dataDir, sourceDir, threshold);
        try {
            Files.deleteIfExists(sourceDir.resolve("Sample.java"));
            Files.writeString(sourceDir.resolve(fileName), content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return env;
    }

    public static List<CoverableElement> elements(SourceManifest manifest, ElementType type) {
        return manifest.files().stream()
                .flatMap(file -> file.elements().stream())
                .filter(element -> element.type() == type)
                .collect(Collectors.toList());
    }

    public record Environment(
            CoverageProperties properties,
            CoverageStorage storage,
            JavaSourceAnalyzer analyzer,
            ManifestService manifestService,
            CoverageService coverageService,
            BaselineService baselineService,
            ImportService importService,
            Path sourceDir) {

        public SourceManifest analyze() {
            return manifestService.analyzeDirectory(sourceDir);
        }
    }
}
