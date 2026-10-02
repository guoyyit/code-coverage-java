package com.github.guoyyit.codecoveragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.service.BatchService;
import com.github.guoyyit.codecoveragejava.service.EventIngestionService;
import com.github.guoyyit.codecoveragejava.service.ReportService;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventAggregationTest {

    @TempDir
    Path tempDir;

    private IncrementalManifestManager manifestManager;
    private BatchService batchService;
    private EventIngestionService ingestionService;
    private ReportService reportService;
    private CoverageStore store;
    private Path sourceRoot;

    @BeforeEach
    void setUp() throws IOException {
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(tempDir.resolve("data").toString());
        store = new CoverageStore(properties, new ObjectMapper());
        store.init();
        manifestManager = new IncrementalManifestManager(new JavaSourceAnalyzer(), store);
        batchService = new BatchService(manifestManager, store);
        ingestionService = new EventIngestionService(store);
        reportService = new ReportService(store);

        sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Calc.java"), """
                public class Calc {
                    int add(int a, int b) {
                        if (a > 0 && b > 0) {
                            return a + b;
                        }
                        return 0;
                    }
                }
                """);
    }

    private List<String> idsOf(ElementType type) {
        Manifest manifest = manifestManager.analyze(sourceRoot.toString()).manifest();
        return manifest.files().get(0).elements().stream()
                .filter(element -> element.type() == type)
                .map(CoverableElement::id)
                .sorted()
                .toList();
    }

    @Test
    void outOfOrderAndDuplicateEventsCountEachElementOnce() {
        BatchRecord batch = batchService.openBatch("b1", sourceRoot.toString());
        assertEquals(BatchStatus.OPEN, batch.status());

        List<String> statements = idsOf(ElementType.STATEMENT);
        List<String> branches = idsOf(ElementType.BRANCH);

        CoverageEvent lateFirst = new CoverageEvent("b1",
                List.of(statements.get(2), branches.get(3)), 100L);
        CoverageEvent early = new CoverageEvent("b1",
                List.of(statements.get(0), branches.get(0)), 1L);
        CoverageEvent duplicate = new CoverageEvent("b1",
                List.of(statements.get(2), branches.get(3)), 50L);

        assertEquals(2, ingestionService.ingest(lateFirst));
        assertEquals(2, ingestionService.ingest(early));
        assertEquals(0, ingestionService.ingest(duplicate));

        CoverageReport report = reportService.batchReport("b1");
        assertEquals(2, report.coveredStatements());
        assertEquals(3, report.totalStatements());
        assertEquals(2, report.coveredBranches());
        assertEquals(4, report.totalBranches());
        assertEquals(66.67, report.lineCoverage(), 0.001);
        assertEquals(50.0, report.branchCoverage(), 0.001);

        CoverageReport again = reportService.batchReport("b1");
        assertEquals(report, again, "report must be deterministic for the same state");
    }

    @Test
    void lateEventsAfterCompletionAreRejected() {
        batchService.openBatch("b-done", sourceRoot.toString());
        String statementId = idsOf(ElementType.STATEMENT).get(0);

        batchService.completeBatch("b-done");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ingestionService.ingest(
                new CoverageEvent("b-done", List.of(statementId), 999L)));
        assertTrue(error.getMessage().contains("late events rejected"));
    }

    @Test
    void unknownElementIsRejected() {
        batchService.openBatch("b2", sourceRoot.toString());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ingestionService.ingest(
                new CoverageEvent("b2", List.of("STMT:Other.java:99"), 1L)));
        assertTrue(error.getMessage().contains("does not belong"));
        assertTrue(store.loadBatchHits("b2").isEmpty(), "rejected event must not persist partial hits");
    }

    @Test
    void mergedReportCombinesMultipleBatches() {
        List<String> statements = idsOf(ElementType.STATEMENT);
        batchService.openBatch("m1", sourceRoot.toString());
        batchService.openBatch("m2", sourceRoot.toString());

        ingestionService.ingest(new CoverageEvent("m1", List.of(statements.get(0)), null));
        ingestionService.ingest(new CoverageEvent("m2", List.of(statements.get(1)), null));

        CoverageReport merged = reportService.mergedReport(List.of("m2", "m1"));
        assertTrue(merged.merged());
        assertEquals(List.of("m1", "m2"), merged.batchIds(), "batch ids must be sorted for reproducibility");
        assertEquals(2, merged.coveredStatements());

        assertThrows(IllegalArgumentException.class, () -> reportService.mergedReport(List.of("m1", "missing")));
    }
}
