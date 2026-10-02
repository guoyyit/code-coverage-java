package com.github.guoyyit.codecoveragejava.storage;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.FileManifest;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class CoverageStorage {

    private final Path dataDir;
    private final Path manifestsDir;
    private final Path batchesDir;
    private final Path baselinesDir;
    private final ObjectMapper objectMapper;

    public CoverageStorage(CoverageProperties properties, ObjectMapper objectMapper) {
        this.dataDir = Path.of(properties.getDataDir()).toAbsolutePath().normalize();
        this.manifestsDir = this.dataDir.resolve("manifests");
        this.batchesDir = this.dataDir.resolve("batches");
        this.baselinesDir = this.dataDir.resolve("baselines");
        this.objectMapper = objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
                .addMixIn(SourceManifest.class, SourceManifestMixin.class)
                .addMixIn(CoverageBatch.class, CoverageBatchMixin.class);
    }

    public synchronized void initialize() {
        try {
            Files.createDirectories(manifestsDir);
            Files.createDirectories(batchesDir);
            Files.createDirectories(baselinesDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化数据目录: " + dataDir, e);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public synchronized void saveManifest(SourceManifest manifest) {
        Path target = manifestsDir.resolve("manifest-" + manifest.versionId() + ".json");
        writeJson(target, manifest);
    }

    public synchronized List<SourceManifest> loadManifests() {
        return readAll(manifestsDir, SourceManifest.class);
    }

    public synchronized void saveCurrentVersion(String versionId, Path sourceRoot) {
        writeJson(dataDir.resolve("state.json"),
                new CurrentState(versionId, sourceRoot.toAbsolutePath().normalize().toString()));
    }

    public synchronized String loadCurrentVersion() {
        CurrentState state = readJsonIfExists(dataDir.resolve("state.json"), CurrentState.class);
        return state == null ? null : state.versionId();
    }

    public synchronized Path loadSourceRoot() {
        CurrentState state = readJsonIfExists(dataDir.resolve("state.json"), CurrentState.class);
        return state == null ? null : Path.of(state.sourceRoot());
    }

    public synchronized void saveBatch(CoverageBatch batch) {
        writeJson(batchesDir.resolve("batch-" + encode(batch.batchId()) + ".json"), batch);
    }

    public synchronized List<CoverageBatch> loadBatches() {
        return readAll(batchesDir, CoverageBatch.class);
    }

    public synchronized void saveBaseline(Baseline baseline) {
        writeJson(baselinesDir.resolve("baseline-" + encode(baseline.name()) + ".json"), baseline);
    }

    public synchronized List<Baseline> loadBaselines() {
        return readAll(baselinesDir, Baseline.class);
    }

    public synchronized void saveAnalysisCache(Map<String, FileManifest> cache) {
        writeJson(dataDir.resolve("cache.json"), new AnalysisCache(cache));
    }

    public synchronized Map<String, FileManifest> loadAnalysisCache() {
        AnalysisCache cache = readJsonIfExists(dataDir.resolve("cache.json"), AnalysisCache.class);
        if (cache == null || cache.files() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(cache.files());
    }

    private <T> List<T> readAll(Path directory, Class<T> type) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<T> results = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> files = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                results.add(objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取数据目录失败: " + directory, e);
        }
        return results;
    }

    private <T> T readJsonIfExists(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException("读取数据文件失败: " + file, e);
        }
    }

    private void writeJson(Path target, Object value) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入数据文件失败: " + target, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    record CurrentState(String versionId, String sourceRoot) {
    }

    record AnalysisCache(Map<String, FileManifest> files) {
    }

    abstract static class SourceManifestMixin {
        @JsonCreator
        SourceManifestMixin(@JsonProperty("versionId") String versionId,
                            @JsonProperty("createdAt") long createdAt,
                            @JsonProperty("files") List<FileManifest> files) {
        }
    }

    abstract static class CoverageBatchMixin {
        @JsonCreator
        CoverageBatchMixin(@JsonProperty("batchId") String batchId,
                           @JsonProperty("manifestVersion") String manifestVersion,
                           @JsonProperty("createdAt") long createdAt,
                           @JsonProperty("origin") String origin,
                           @JsonProperty("status") com.github.guoyyit.codecoveragejava.model.BatchStatus status,
                           @JsonProperty("finishedAt") long finishedAt,
                           @JsonProperty("frozen") boolean frozen,
                           @JsonProperty("hitElementIds") java.util.SortedSet<String> hitElementIds) {
        }
    }
}
