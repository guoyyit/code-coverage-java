package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.tests.support.TestSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrentUploadTest {

    @Test
    void concurrentUploadsAndQueriesStayConsistent(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        List<CoverableElement> lines = TestSupport.elements(manifest, ElementType.LINE);
        String batchId = "concurrent";
        env.coverageService().createBatch(batchId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, threads).<Callable<Void>>mapToObj(t -> () -> {
                for (int i = 0; i < lines.size(); i++) {
                    if (i % threads == t) {
                        env.coverageService().uploadEvents(batchId, List.of(
                                new CoverageEvent(lines.get(i).id()),
                                new CoverageEvent(lines.get(i).id())));
                    }
                }
                return null;
            }).toList();
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> env.coverageService().batchReport(batchId)));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        CoverageReport report = env.coverageService().batchReport(batchId);
        assertEquals(lines.size(), report.coveredLines(), "并发重复上报，每个元素仍只计一次");
        assertEquals(0, report.coveredBranches());
    }

    @Test
    void concurrentBatchCreationWithGeneratedIdsNeverCollides(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        env.analyze();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = IntStream.range(0, 16)
                    .<Future<String>>mapToObj(ignored ->
                            pool.submit(() -> env.coverageService().createBatch(null).batchId()))
                    .toList();
            List<String> ids = new java.util.ArrayList<>();
            for (Future<String> future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(16, ids.stream().distinct().count());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
