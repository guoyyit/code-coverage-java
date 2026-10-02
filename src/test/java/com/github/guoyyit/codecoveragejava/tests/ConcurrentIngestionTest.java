package com.github.guoyyit.codecoveragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.service.BatchService;
import com.github.guoyyit.codecoveragejava.service.EventIngestionService;
import com.github.guoyyit.codecoveragejava.service.ReportService;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentIngestionTest {

    @Test
    void concurrentWritersDeduplicateAndReadersSeeConsistentState(@TempDir Path tempDir) throws Exception {
        CoverageProperties properties = new CoverageProperties();
        properties.setDataDir(tempDir.resolve("data").toString());
        CoverageStore store = new CoverageStore(properties, new ObjectMapper());
        store.init();
        IncrementalManifestManager manifestManager =
                new IncrementalManifestManager(new JavaSourceAnalyzer(), store);
        BatchService batchService = new BatchService(manifestManager, store);
        EventIngestionService ingestionService = new EventIngestionService(store);
        ReportService reportService = new ReportService(store);

        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        StringBuilder source = new StringBuilder("public class Big {\n    int run(int x) {\n");
        for (int i = 0; i < 50; i++) {
            source.append("        x = x + ").append(i).append(";\n");
        }
        source.append("        return x;\n    }\n}\n");
        Files.writeString(sourceRoot.resolve("Big.java"), source.toString());

        batchService.openBatch("c1", sourceRoot.toString());
        batchService.openBatch("c2", sourceRoot.toString());

        List<String> elementIds = manifestManager.analyze(sourceRoot.toString()).manifest()
                .files().get(0).elements().stream()
                .filter(element -> element.type() == ElementType.STATEMENT)
                .map(CoverableElement::id)
                .sorted()
                .toList();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acceptedTotal = new AtomicInteger();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int thread = 0; thread < threads; thread++) {
            String batchId = thread % 2 == 0 ? "c1" : "c2";
            pool.submit(() -> {
                try {
                    start.await();
                    for (String elementId : elementIds) {
                        int accepted = ingestionService.ingest(
                                new CoverageEvent(batchId, List.of(elementId), null));
                        acceptedTotal.addAndGet(accepted);
                        CoverageReport snapshot = reportService.batchReport(batchId);
                        assertTrue(snapshot.coveredStatements() <= elementIds.size());
                    }
                } catch (Throwable ex) {
                    failures.add(ex);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "concurrent failures: " + failures);

        assertEquals(elementIds.size() * 2, acceptedTotal.get(),
                "each element counted exactly once per batch despite duplicate concurrent events");
        assertEquals(elementIds.size(), ingestionService.totalDistinctHits("c1"));
        assertEquals(elementIds.size(), ingestionService.totalDistinctHits("c2"));
        assertEquals(100.0, reportService.mergedReport(List.of("c1", "c2")).lineCoverage(), 0.001);
    }
}
