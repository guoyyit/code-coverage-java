package com.github.guoyyit.codecoveragejava.analysis;

import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.FileManifest;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.storage.CoverageStorage;
import com.github.guoyyit.codecoveragejava.util.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class ManifestService {

    private final CoverageStorage storage;
    private final SourceAnalyzer analyzer;

    private final Map<String, SourceManifest> manifestsById = new TreeMap<>();
    private final Map<String, FileManifest> fileCache = new LinkedHashMap<>();
    private String currentVersionId;

    public ManifestService(CoverageStorage storage, SourceAnalyzer analyzer) {
        this.storage = storage;
        this.analyzer = analyzer;
        storage.initialize();
        for (SourceManifest manifest : storage.loadManifests()) {
            manifestsById.put(manifest.versionId(), manifest);
        }
        this.currentVersionId = storage.loadCurrentVersion();
        this.fileCache.putAll(storage.loadAnalysisCache());
    }

    public synchronized SourceManifest analyzeDirectory(Path sourceRoot) {
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw CoverageApiException.badRequest("源码目录不存在或不是目录: " + normalizedRoot);
        }
        analyzer.resetStats();
        Map<String, FileManifest> newCache = new LinkedHashMap<>();
        List<Path> javaFiles = listJavaFiles(normalizedRoot);
        for (Path file : javaFiles) {
            String relativePath = normalizedRoot.relativize(file).toString().replace('\\', '/');
            String contentHash = hashFile(relativePath, file);
            FileManifest cached = fileCache.get(relativePath);
            if (cached != null && cached.contentHash().equals(contentHash)) {
                newCache.put(relativePath, cached);
            } else {
                FileManifest analyzed;
                try {
                    analyzed = analyzer.analyzeFile(relativePath, file);
                } catch (IOException e) {
                    throw new UncheckedIOException("读取源文件失败: " + relativePath, e);
                }
                newCache.put(relativePath, analyzed);
            }
        }

        fileCache.clear();
        fileCache.putAll(newCache);
        storage.saveAnalysisCache(fileCache);

        SourceManifest manifest = SourceManifest.of(new ArrayList<>(newCache.values()));
        if (!manifestsById.containsKey(manifest.versionId())) {
            storage.saveManifest(manifest);
            manifestsById.put(manifest.versionId(), manifest);
        }
        currentVersionId = manifest.versionId();
        storage.saveCurrentVersion(currentVersionId, normalizedRoot);
        return manifest;
    }

    public synchronized Optional<SourceManifest> currentManifest() {
        return Optional.ofNullable(currentVersionId == null ? null : manifestsById.get(currentVersionId));
    }

    public synchronized SourceManifest getManifest(String versionId) {
        SourceManifest manifest = manifestsById.get(versionId);
        if (manifest == null) {
            throw CoverageApiException.notFound("清单版本不存在: " + versionId);
        }
        return manifest;
    }

    public synchronized SourceManifest requireCurrentManifest() {
        return currentManifest()
                .orElseThrow(() -> CoverageApiException.badRequest("尚无清单版本，请先分析源码目录"));
    }

    public synchronized List<FileManifest> cachedFileManifests() {
        return List.copyOf(fileCache.values());
    }

    public int parsedFileCount() {
        return analyzer.getParsedFileCount();
    }

    private List<Path> listJavaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历源码目录失败: " + root, e);
        }
    }

    private String hashFile(String relativePath, Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return Ids.sha256Hex("content|" + relativePath + "|" + new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取源文件失败: " + file, e);
        }
    }
}
