package com.github.guoyyit.codecoveragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.service.BaselineService;
import com.github.guoyyit.codecoveragejava.service.BatchService;
import com.github.guoyyit.codecoveragejava.service.EventIngestionService;
import com.github.guoyyit.codecoveragejava.service.ReportService;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestartRecoveryTest {

    @Test
    void batchesHitsAndBaselinesSurviveRestart(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Calc.java"), """
                public class Calc {
                    int add(int a, int b) {
                        int sum = a + b;
                        return sum;
                    }
                }
                """);

        CoverageStore firstStore = newStore(dataDir);
        IncrementalManifestManager firstManager =
                new IncrementalManifestManager(new JavaSourceAnalyzer(), firstStore);
        BatchService firstBatches = new BatchService(firstManager, firstStore);
        EventIngestionService firstIngest = new EventIngestionService(firstStore);
        ReportService firstReports = new ReportService(firstStore);
        BaselineService firstBaselines =
                new BaselineService(firstStore, firstReports, new CoverageProperties());

        firstBatches.openBatch("finished", sourceRoot.toString());
        firstBatches.openBatch("unfinished", sourceRoot.toString());
        List<String> ids = firstManager.analyze(sourceRoot.toString()).manifest()
                .files().get(0).elements().stream()
                .filter(element -> element.type() == ElementType.STATEMENT)
                .map(CoverableElement::id)
                .toList();
        firstIngest.ingest(new CoverageEvent("finished", ids, null));
        firstIngest.ingest(new CoverageEvent("unfinished", ids.subList(0, 1), null));
        firstBatches.completeBatch("finished");
        CoverageReport reportBeforeRestart = firstReports.batchReport("finished");
        firstBaselines.saveBaseline("release-1", List.of("finished"));

        CoverageStore restartedStore = newStore(dataDir);
        IncrementalManifestManager restartedManager =
                new IncrementalManifestManager(new JavaSourceAnalyzer(), restartedStore);
        BatchService restartedBatches = new BatchService(restartedManager, restartedStore);
        ReportService restartedReports = new ReportService(restartedStore);
        BaselineService restartedBaselines = new BaselineService(restartedStore,
                restartedReports, new CoverageProperties());

        BatchRecord finished = restartedBatches.getBatch("finished");
        BatchRecord unfinished = restartedBatches.getBatch("unfinished");
        assertEquals(BatchStatus.COMPLETED, finished.status());
        assertEquals(BatchStatus.OPEN, unfinished.status(), "unfinished batch must remain identifiable");

        List<BatchRecord> openBatches = restartedBatches.listBatches().stream()
                .filter(batch -> batch.status() == BatchStatus.OPEN)
                .toList();
        assertEquals(1, openBatches.size());
        assertEquals("unfinished", openBatches.get(0).batchId());

        assertEquals(reportBeforeRestart, restartedReports.batchReport("finished"),
                "historical report must be reproducible after restart");

        Baseline baseline = restartedBaselines.getBaseline("release-1");
        assertNotNull(baseline);
        assertEquals(reportBeforeRestart, baseline.report());

        firstIngest = new EventIngestionService(restartedStore);
        firstIngest.ingest(new CoverageEvent("unfinished", ids.subList(1, 2), null));
        assertEquals(ids.size(), restartedStore.loadBatchHits("unfinished").size(),
                "open batch keeps accepting events after restart");
    }

    private CoverageStore newStore(Path dataDir) {
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(dataDir.toString());
        CoverageStore store = new CoverageStore(properties, new ObjectMapper());
        store.init();
        return store;
    }
}
