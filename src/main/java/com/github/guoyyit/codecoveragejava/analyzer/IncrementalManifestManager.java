package com.github.guoyyit.codecoveragejava.analyzer;

import com.github.guoyyit.codecoveragejava.model.AnalysisResult;
import com.github.guoyyit.codecoveragejava.model.FileManifest;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class IncrementalManifestManager {

    private final JavaSourceAnalyzer analyzer;
    private final CoverageStore store;

    public IncrementalManifestManager(JavaSourceAnalyzer analyzer, CoverageStore store) {
        this.analyzer = analyzer;
        this.store = store;
    }

    public synchronized AnalysisResult analyze(String sourceRoot) {
        Path root = Paths.get(sourceRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("source root is not a directory: " + root);
        }

        Manifest previous = store.loadLatestManifestForRoot(root.toString());
        Map<String, FileManifest> previousByPath = new LinkedHashMap<>();
        if (previous != null) {
            for (FileManifest file : previous.files()) {
                previousByPath.put(file.sourcePath(), file);
            }
        }

        List<Path> javaFiles = listJavaFiles(root);
        Map<String, FileManifest> nextByPath = new TreeMap<>();
        List<String> rebuiltFiles = new ArrayList<>();
        List<String> unchangedFiles = new ArrayList<>();

        for (Path javaFile : javaFiles) {
            String relativePath = root.relativize(javaFile).toString().replace('\\', '/');
            String content = readFile(javaFile);
            String contentHash = sha256Hex(content);
            FileManifest cached = previousByPath.get(relativePath);
            if (cached != null && contentHash.equals(cached.contentHash())) {
                nextByPath.put(relativePath, cached);
                unchangedFiles.add(relativePath);
            } else {
                FileManifest rebuilt = new FileManifest(relativePath, contentHash,
                        analyzer.analyze(relativePath, content));
                nextByPath.put(relativePath, rebuilt);
                rebuiltFiles.add(relativePath);
            }
        }

        String version = manifestVersion(root.toString(), nextByPath);
        Manifest manifest = new Manifest(version, root.toString(), List.copyOf(nextByPath.values()));
        store.saveManifest(manifest);
        store.markLatestForRoot(root.toString(), version);
        return new AnalysisResult(manifest, List.copyOf(rebuiltFiles), List.copyOf(unchangedFiles));
    }

    public Manifest getOrAnalyze(String sourceRoot) {
        Path root = Paths.get(sourceRoot).toAbsolutePath().normalize();
        Manifest existing = store.loadLatestManifestForRoot(root.toString());
        if (existing != null) {
            return existing;
        }
        return analyze(sourceRoot).manifest();
    }

    private List<Path> listJavaFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to scan source root: " + root, ex);
        }
    }

    private String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read source file: " + file, ex);
        }
    }

    private String manifestVersion(String sourceRoot, Map<String, FileManifest> filesByPath) {
        StringBuilder builder = new StringBuilder(sourceRoot).append('\n');
        for (FileManifest file : filesByPath.values()) {
            builder.append(file.sourcePath()).append(':').append(file.contentHash()).append('\n');
        }
        return "m" + sha256Hex(builder.toString()).substring(0, 24);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
