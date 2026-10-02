package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.tests.support.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceRecoveryTest {

    @Test
    void batchesBaselinesAndHistorySurviveRestart(@TempDir Path tempDir) {
        Path dataDir = tempDir.resolve("data");
        Path srcDir = tempDir.resolve("src");
        TestSupport.Environment first = TestSupport.newEnvironment(dataDir, srcDir);
        SourceManifest versionOne = first.analyze();
        List<CoverableElement> lines = TestSupport.elements(versionOne, ElementType.LINE);

        first.coverageService().createBatch("open-batch");
        first.coverageService().uploadEvents("open-batch",
                List.of(new CoverageEvent(lines.get(0).id())));
        first.coverageService().createBatch("done-batch");
        first.coverageService().uploadEvents("done-batch",
                List.of(new CoverageEvent(lines.get(1).id())));
        first.coverageService().finishBatch("done-batch");
        first.baselineService().saveBaseline("release-1");

        TestSupport.Environment restarted = TestSupport.newEnvironment(dataDir, srcDir);

        CoverageBatch open = restarted.coverageService().getBatch("open-batch");
        CoverageBatch done = restarted.coverageService().getBatch("done-batch");
        assertEquals(BatchStatus.OPEN, open.status(), "未完成批次重启后仍可被识别");
        assertEquals(BatchStatus.FINISHED, done.status());
        assertEquals(1, open.hitCount());
        assertNotNull(restarted.baselineService().getBaseline("release-1"));
        assertNotNull(restarted.manifestService().getManifest(versionOne.versionId()));
        assertEquals(versionOne.versionId(),
                restarted.manifestService().currentManifest().orElseThrow().versionId());

        CoverageReport report = restarted.coverageService().batchReport("open-batch");
        assertEquals(1, report.coveredLines());
    }

    @Test
    void historicalBatchesStayQueryableAfterSourceEvolution(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path srcDir = tempDir.resolve("src");
        TestSupport.Environment first = TestSupport.newEnvironment(dataDir, srcDir);
        SourceManifest versionOne = first.analyze();
        List<CoverableElement> oldLines = TestSupport.elements(versionOne, ElementType.LINE);
        first.coverageService().createBatch("historical");
        first.coverageService().uploadEvents("historical",
                List.of(new CoverageEvent(oldLines.get(0).id())));
        first.coverageService().finishBatch("historical");

        Files.writeString(srcDir.resolve("Added.java"), """
                package demo;
                public class Added {
                    public int v() { return 7; }
                }
                """);
        TestSupport.Environment restarted = TestSupport.newEnvironment(dataDir, srcDir);
        SourceManifest versionTwo = restarted.analyze();
        assertFalse(versionOne.versionId().equals(versionTwo.versionId()));

        CoverageReport historicalReport = restarted.coverageService().batchReport("historical");
        assertEquals(versionOne.versionId(), historicalReport.manifestVersion(),
                "历史批次仍绑定旧清单版本，旧事件不计入新元素");
        assertEquals(1, historicalReport.coveredLines());

        CoverageReport oldAggregate =
                restarted.coverageService().aggregateReport(versionOne.versionId());
        assertEquals(versionOne.versionId(), oldAggregate.manifestVersion());
        assertEquals(1, oldAggregate.coveredLines());
        assertTrue(restarted.coverageService().getBatch("historical").frozen() == false);
    }
}
