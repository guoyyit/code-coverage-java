package com.github.guoyyit.codecoveragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.BaselineCompareResult;
import com.github.guoyyit.codecoveragejava.model.CompareConclusion;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.service.BaselineService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineComparisonTest {

    @TempDir
    Path tempDir;

    private CoverageStore store;
    private IncrementalManifestManager manifestManager;
    private BatchService batchService;
    private EventIngestionService ingestionService;
    private ReportService reportService;
    private BaselineService baselineService;
    private Path sourceRoot;

    private static final String INITIAL_SOURCE = """
            public class Work {
                int run(int x) {
                    int result = x + 1;
                    result = result * 2;
                    result = result - 1;
                    return result;
                }
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(tempDir.resolve("data").toString());
        properties.setRegressionThresholdPercent(5.0);
        store = new CoverageStore(properties, new ObjectMapper());
        store.init();
        manifestManager = new IncrementalManifestManager(new JavaSourceAnalyzer(), store);
        batchService = new BatchService(manifestManager, store);
        ingestionService = new EventIngestionService(store);
        reportService = new ReportService(store);
        baselineService = new BaselineService(store, reportService, properties);

        sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Work.java"), INITIAL_SOURCE);
    }

    @Test
    void matchedImprovedAndReproducible() {
        batchService.openBatch("base", sourceRoot.toString());
        ingestAllStatements("base");
        Baseline baseline = baselineService.saveBaseline("gold", List.of("base"));
        assertEquals(100.0, baseline.report().lineCoverage(), 0.001);

        BaselineCompareResult same = baselineService.compare("gold", List.of("base"));
        assertEquals(CompareConclusion.MATCHED, same.conclusion());

        batchService.openBatch("more", sourceRoot.toString());
        BaselineCompareResult improved = baselineService.compare("gold", List.of("more"));
        assertEquals(0.0, improved.currentLineCoverage(), 0.001);

        ingestAllStatements("more");
        BaselineCompareResult fullAgain = baselineService.compare("gold", List.of("more"));
        assertEquals(CompareConclusion.MATCHED, fullAgain.conclusion());
        assertEquals(fullAgain, baselineService.compare("gold", List.of("more")),
                "comparison must be reproducible");
    }

    @Test
    void newUncoveredCodeIsDistinguishedFromRegression() throws IOException {
        batchService.openBatch("base", sourceRoot.toString());
        ingestStatementsAtLines("base", 3, 4, 5);
        baselineService.saveBaseline("gold", List.of("base"));

        // baseline covers 3 of the original 4 statements => 75%.
        // Add one statement; the next batch covers the same 3 original ones plus
        // the other original statement (4 of 5 => 80%), leaving only the new line
        // uncovered. Overall coverage improves, so the conclusion must be about
        // new uncovered code rather than regression.
        Files.writeString(sourceRoot.resolve("Work.java"), """
                public class Work {
                    int run(int x) {
                        int result = x + 1;
                        result = result * 2;
                        result = result - 1;
                        int extra = result + 99;
                        return result;
                    }
                }
                """);
        batchService.openBatch("next", sourceRoot.toString());
        ingestStatementsAtLines("next", 3, 4, 5, 7);

        BaselineCompareResult result = baselineService.compare("gold", List.of("next"));
        assertEquals(CompareConclusion.NEW_CODE_UNCOVERED, result.conclusion());
        assertEquals(1, result.newUncoveredElements().size());
        assertEquals(6, result.newUncoveredElements().get(0).line());
        assertTrue(result.newUncoveredElements().get(0).id().startsWith("STMT:Work.java:6:"));
        assertEquals(5.0, result.lineCoverageDelta(), 0.001);
    }

    @Test
    void regressionBeyondThresholdIsFlagged() throws IOException {
        batchService.openBatch("base", sourceRoot.toString());
        ingestAllStatements("base");
        baselineService.saveBaseline("gold", List.of("base"));

        batchService.openBatch("partial", sourceRoot.toString());
        ingestStatementsAtLines("partial", 3);

        BaselineCompareResult result = baselineService.compare("gold", List.of("partial"));
        assertEquals(CompareConclusion.REGRESSED_OVER_THRESHOLD, result.conclusion());
        assertEquals(25.0, result.currentLineCoverage(), 0.001);
        assertEquals(-75.0, result.lineCoverageDelta(), 0.001);
        assertTrue(result.thresholdPercent() == 5.0);
    }

    @Test
    void oldBatchesKeepTheirManifestAndHistoricalReports() throws IOException {
        batchService.openBatch("old", sourceRoot.toString());
        ingestAllStatements("old");
        CoverageReport oldReport = reportService.batchReport("old");

        Files.writeString(sourceRoot.resolve("Work.java"), INITIAL_SOURCE.replace(
                "result = result * 2;", "result = result * 3;"));
        batchService.openBatch("new", sourceRoot.toString());
        ingestAllStatements("new");

        CoverageReport historical = reportService.batchReport("old");
        assertEquals(oldReport, historical, "source evolution must not rewrite historical reports");
        CoverageReport currentMerged = reportService.mergedReport(
                store.listBatches().stream().filter(batch -> batch.manifestVersion()
                                .equals(historical.manifestVersion())).findFirst().orElseThrow()
                        .manifestVersion());
        assertEquals(100.0, currentMerged.lineCoverage(), 0.001);
        assertEquals(List.of("old"), currentMerged.batchIds(),
                "new-version batches must not merge into old-version reports");
    }

    private void ingestAllStatements(String batchId, String... onlyIds) {
        var manifest = manifestManager.analyze(sourceRoot.toString()).manifest();
        List<String> ids = manifest.files().get(0).elements().stream()
                .filter(element -> element.type() == ElementType.STATEMENT)
                .map(CoverableElement::id)
                .sorted()
                .toList();
        if (onlyIds.length > 0) {
            ids = ids.stream().filter(List.of(onlyIds)::contains).toList();
        }
        ingestionService.ingest(new CoverageEvent(batchId, ids, null));
    }

    private void ingestStatementsAtLines(String batchId, int... lines) {
        var manifest = manifestManager.analyze(sourceRoot.toString()).manifest();
        List<Integer> wanted = java.util.Arrays.stream(lines).boxed().toList();
        List<String> ids = manifest.files().get(0).elements().stream()
                .filter(element -> element.type() == ElementType.STATEMENT)
                .filter(element -> wanted.contains(element.line()))
                .map(CoverableElement::id)
                .sorted()
                .toList();
        ingestionService.ingest(new CoverageEvent(batchId, ids, null));
    }
}
