package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.tests.support.TestSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventAggregationTest {

    @Test
    void outOfOrderDuplicateAndLateEventsAreDeduplicated(@TempDir Path tempDir) {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        List<CoverableElement> lines = TestSupport.elements(manifest, ElementType.LINE);
        List<CoverableElement> branches = TestSupport.elements(manifest, ElementType.BRANCH);
        assertTrue(lines.size() >= 5);

        CoverageBatch batch = env.coverageService().createBatch("run-1");

        List<CoverageEvent> shuffled = new ArrayList<>(lines.stream()
                .map(element -> new CoverageEvent(element.id())).toList());
        shuffled.add(new CoverageEvent(branches.get(0).id()));
        shuffled.add(new CoverageEvent(branches.get(0).id()));
        shuffled.add(new CoverageEvent(lines.get(0).id()));
        Collections.shuffle(shuffled);
        int firstAdded = env.coverageService().uploadEvents("run-1", shuffled);

        int repeated = env.coverageService().uploadEvents("run-1", List.of(
                new CoverageEvent(lines.get(0).id()),
                new CoverageEvent(branches.get(1).id())));

        CoverageReport report = env.coverageService().batchReport("run-1");
        assertEquals(lines.size() + 2, report.coveredLines() + report.coveredBranches());
        assertEquals(firstAdded, lines.size() + 1, "乱序 + 重复只计一次");
        assertEquals(1, repeated, "迟到的重复事件不计入，新元素只计一次");

        CoverageReport again = env.coverageService().batchReport("run-1");
        assertEquals(report, again, "聚合结果必须确定可复现");
    }

    @Test
    void eventsAfterFinishAreRejected(@TempDir Path tempDir) {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        CoverageBatch batch = env.coverageService().createBatch("run-2");
        CoverableElement any = TestSupport.elements(manifest, ElementType.LINE).get(0);
        env.coverageService().uploadEvents("run-2", List.of(new CoverageEvent(any.id())));
        env.coverageService().finishBatch("run-2");

        assertEquals(BatchStatus.FINISHED, env.coverageService().getBatch("run-2").status());
        CoverageApiException error = assertThrows(CoverageApiException.class, () ->
                env.coverageService().uploadEvents("run-2",
                        List.of(new CoverageEvent(any.id()))));
        assertEquals(409, error.getStatus());
    }

    @Test
    void unknownElementIsRejected(@TempDir Path tempDir) {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        env.analyze();
        env.coverageService().createBatch("run-3");

        CoverageApiException error = assertThrows(CoverageApiException.class, () ->
                env.coverageService().uploadEvents("run-3",
                        List.of(new CoverageEvent("L-deadbeef"))));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不属于批次绑定的清单版本"));
    }

    @Test
    void multipleBatchesMergeIntoAggregateAndBatchReportsStaySeparate(@TempDir Path tempDir) {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        List<CoverableElement> lines = TestSupport.elements(manifest, ElementType.LINE);

        env.coverageService().createBatch("a");
        env.coverageService().createBatch("b");
        env.coverageService().uploadEvents("a", List.of(new CoverageEvent(lines.get(0).id())));
        env.coverageService().uploadEvents("b", List.of(new CoverageEvent(lines.get(1).id())));

        CoverageReport reportA = env.coverageService().batchReport("a");
        CoverageReport reportB = env.coverageService().batchReport("b");
        CoverageReport aggregate = env.coverageService().aggregateReport(null);

        assertEquals(1, reportA.coveredLines());
        assertEquals(1, reportB.coveredLines());
        assertEquals(2, aggregate.coveredLines());
        assertEquals(List.of("a", "b"), aggregate.batchIds());
        assertNotEquals(reportA, aggregate);
    }
}
