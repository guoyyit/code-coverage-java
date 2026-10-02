package com.github.guoyyit.codecoveragejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class CoverageStore {

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public CoverageStore(CoverageProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataDir = Paths.get(properties.getDataDir());
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dataDir.resolve("manifests"));
            Files.createDirectories(dataDir.resolve("batches"));
            Files.createDirectories(dataDir.resolve("baselines"));
            Files.createDirectories(dataDir.resolve("latest"));
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to create data directory: " + dataDir, ex);
        }
    }

    public ReadWriteLock lock() {
        return lock;
    }

    public void saveManifest(Manifest manifest) {
        writeJson(dataDir.resolve("manifests").resolve(manifest.version() + ".json"), manifest);
    }

    public Manifest loadManifest(String version) {
        Path file = dataDir.resolve("manifests").resolve(version + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return readJson(file, Manifest.class);
    }

    public boolean manifestExists(String version) {
        return Files.exists(dataDir.resolve("manifests").resolve(version + ".json"));
    }

    public List<Manifest> listManifests() {
        List<Manifest> manifests = new ArrayList<>();
        Path dir = dataDir.resolve("manifests");
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> manifests.add(readJson(path, Manifest.class)));
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to list manifests", ex);
        }
        return manifests;
    }

    public void markLatestForRoot(String sourceRoot, String version) {
        writeString(dataDir.resolve("latest").resolve(rootKey(sourceRoot) + ".txt"), version);
    }

    public Manifest loadLatestManifestForRoot(String sourceRoot) {
        Path file = dataDir.resolve("latest").resolve(rootKey(sourceRoot) + ".txt");
        if (!Files.exists(file)) {
            return null;
        }
        String version = readString(file).trim();
        return loadManifest(version);
    }

    public void saveBatch(BatchRecord batch) {
        writeJson(dataDir.resolve("batches").resolve(batch.batchId() + ".json"), batch);
    }

    public BatchRecord loadBatch(String batchId) {
        Path file = dataDir.resolve("batches").resolve(batchId + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return readJson(file, BatchRecord.class);
    }

    public List<BatchRecord> listBatches() {
        List<BatchRecord> batches = new ArrayList<>();
        Path dir = dataDir.resolve("batches");
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.toString().endsWith(".json")
                            && !path.getFileName().toString().endsWith(".hits.json"))
                    .sorted()
                    .forEach(path -> batches.add(readJson(path, BatchRecord.class)));
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to list batches", ex);
        }
        return batches;
    }

    public void saveBatchHits(String batchId, String manifestVersion, List<String> elementIds) {
        HitsDocument document = new HitsDocument(manifestVersion, List.copyOf(elementIds));
        writeJson(dataDir.resolve("batches").resolve(batchId + ".hits.json"), document);
    }

    public List<String> loadBatchHits(String batchId) {
        Path file = dataDir.resolve("batches").resolve(batchId + ".hits.json");
        if (!Files.exists(file)) {
            return List.of();
        }
        return readJson(file, HitsDocument.class).elementIds();
    }

    public String loadBatchHitsVersion(String batchId) {
        Path file = dataDir.resolve("batches").resolve(batchId + ".hits.json");
        if (!Files.exists(file)) {
            return null;
        }
        return readJson(file, HitsDocument.class).manifestVersion();
    }

    public void saveBaseline(Baseline baseline) {
        writeJson(dataDir.resolve("baselines").resolve(baseline.name() + ".json"), baseline);
    }

    public Baseline loadBaseline(String name) {
        Path file = dataDir.resolve("baselines").resolve(name + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return readJson(file, Baseline.class);
    }

    public List<Baseline> listBaselines() {
        List<Baseline> baselines = new ArrayList<>();
        Path dir = dataDir.resolve("baselines");
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> baselines.add(readJson(path, Baseline.class)));
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to list baselines", ex);
        }
        return baselines;
    }

    private String rootKey(String sourceRoot) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sourceRoot.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private <T> void writeJson(Path target, T value) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to write " + target, ex);
        }
    }

    private <T> T readJson(Path source, Class<T> type) {
        try {
            return objectMapper.readValue(source.toFile(), type);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read " + source, ex);
        }
    }

    private void writeString(Path target, String value) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to write " + target, ex);
        }
    }

    private String readString(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read " + source, ex);
        }
    }

    public record HitsDocument(String manifestVersion, List<String> elementIds) {
    }
}
