package com.github.guoyyit.codecoveragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.ImportResult;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.service.CoverageImportService;
import com.github.guoyyit.codecoveragejava.service.ImportException;
import com.github.guoyyit.codecoveragejava.service.ReportService;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageImportServiceTest {

    @TempDir
    Path tempDir;

    private CoverageStore store;
    private CoverageImportService importService;
    private ReportService reportService;
    private Manifest manifest;
    private Path sourceRoot;

    @BeforeEach
    void setUp() throws IOException {
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(tempDir.resolve("data").toString());
        store = new CoverageStore(properties, new ObjectMapper());
        store.init();
        importService = new CoverageImportService(store);
        reportService = new ReportService(store);

        sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Calc.java"), """
                public class Calc {
                    int add(int a, int b) {
                        int sum = a + b;
                        return sum;
                    }
                }
                """);
        manifest = new IncrementalManifestManager(new JavaSourceAnalyzer(), store)
                .analyze(sourceRoot.toString()).manifest();
    }

    private String statementId(int line) {
        return manifest.files().get(0).elements().stream()
                .filter(element -> element.type() == ElementType.STATEMENT)
                .filter(element -> element.line() == line)
                .map(CoverableElement::id)
                .findFirst().orElseThrow();
    }

    private String validDocument(String batchId) {
        return """
                {
                  "formatVersion": "1.0",
                  "sourceRoot": "%s",
                  "batchId": "%s",
                  "manifestVersion": "%s",
                  "events": [
                    {"timestamp": 1, "elementIds": ["%s"]},
                    {"timestamp": 2, "elementIds": ["%s", "%s"]}
                  ],
                  "batchStatus": "COMPLETED"
                }
                """.formatted(sourceRoot.toAbsolutePath().normalize(), batchId, manifest.version(),
                statementId(3), statementId(3), statementId(4));
    }

    @Test
    void importsValidFileAndDeduplicatesAcrossEvents() {
        ImportResult result = importService.importData(
                new ByteArrayInputStream(validDocument("hist-1").getBytes(StandardCharsets.UTF_8)));

        assertEquals("hist-1", result.batchId());
        assertEquals(2, result.eventCount());
        assertEquals(2, result.distinctElementCount());
        assertEquals(100.0, reportService.batchReport("hist-1").lineCoverage(), 0.001);
    }

    @Test
    void rejectsMalformedJsonWithoutPartialImport() {
        String truncated = validDocument("hist-bad").substring(0, 120);
        ImportException error = assertThrows(ImportException.class, () -> importService.importData(
                new ByteArrayInputStream(truncated.getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().toLowerCase().contains("malformed json"));
        assertNull(store.loadBatch("hist-bad"));
        assertTrue(store.loadBatchHits("hist-bad").isEmpty());
    }

    @Test
    void rejectsUnknownElementWithoutPartialImport() {
        String document = validDocument("hist-mismatch")
                .replace(statementId(4), "STMT:Calc.java:99:deadbeef00");
        ImportException error = assertThrows(ImportException.class, () -> importService.importData(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().contains("does not match current manifest"));
        assertNull(store.loadBatch("hist-mismatch"));
    }

    @Test
    void rejectsWrongManifestVersion() {
        String document = validDocument("hist-version")
                .replace(manifest.version(), "m000000000000000000000000");
        ImportException error = assertThrows(ImportException.class, () -> importService.importData(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().contains("does not match any current manifest"));
        assertNull(store.loadBatch("hist-version"));
    }

    @Test
    void rejectsMissingFieldsAndDuplicateBatch() {
        String noEvents = """
                {"formatVersion": "1.0", "sourceRoot": "x", "batchId": "b", "manifestVersion": "%s"}
                """.formatted(manifest.version());
        ImportException error = assertThrows(ImportException.class, () -> importService.importData(
                new ByteArrayInputStream(noEvents.getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().contains("'events'"));

        importService.importData(new ByteArrayInputStream(
                validDocument("dup").getBytes(StandardCharsets.UTF_8)));
        ImportException duplicate = assertThrows(ImportException.class, () -> importService.importData(
                new ByteArrayInputStream(validDocument("dup").getBytes(StandardCharsets.UTF_8))));
        assertTrue(duplicate.getMessage().contains("batch already exists"));
    }
}
