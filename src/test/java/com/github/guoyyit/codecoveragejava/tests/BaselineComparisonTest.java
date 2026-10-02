package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.codecoveragejava.model.CompareResult;
import com.github.guoyyit.codecoveragejava.model.CompareVerdict;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.tests.support.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineComparisonTest {

    private static final String VERSION_ONE = """
            package demo;
            public class Logic {
                public int abs(int x) {
                    if (x < 0) {
                        return -x;
                    }
                    return x;
                }
            }
            """;

    @Test
    void passWhenNoRegressionAndNewCodeCovered(@TempDir Path tempDir) {
        TestSupport.Environment env = TestSupport.newCustomEnvironment(
                tempDir.resolve("data"), tempDir.resolve("src"), "Logic.java", VERSION_ONE, 1.0d);
        SourceManifest manifest = env.analyze();
        env.coverageService().createBatch("full");
        env.coverageService().uploadEvents("full", allEvents(manifest));
        env.baselineService().saveBaseline("golden");

        CompareResult result = env.baselineService().compareWithBaseline("golden");
        assertEquals(CompareVerdict.PASS, result.verdict());
        assertEquals(100.0d, result.currentOverallPercent());
    }

    @Test
    void newUncoveredCodeIsReported(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env = TestSupport.newCustomEnvironment(
                tempDir.resolve("data"), tempDir.resolve("src"), "Logic.java", VERSION_ONE, 1.0d);
        SourceManifest manifest = env.analyze();
        env.coverageService().createBatch("full");
        env.coverageService().uploadEvents("full", allEvents(manifest));
        env.baselineService().saveBaseline("golden");

        Files.writeString(env.sourceDir().resolve("Extra.java"), """
                package demo;
                public class Extra {
                    public int extra(int y) {
                        if (y > 0) {
                            return y;
                        }
                        return 0;
                    }
                }
                """);
        SourceManifest next = env.analyze();
        Thread.sleep(10);
        env.coverageService().createBatch("extra-run");
        List<CoverableElement> extraLines = next.files().stream()
                .filter(file -> file.sourcePath().equals("Extra.java"))
                .flatMap(file -> file.elements().stream())
                .filter(element -> element.type() == ElementType.LINE)
                .toList();
        env.coverageService().uploadEvents("extra-run",
                extraLines.stream().map(e -> new CoverageEvent(e.id())).toList());

        CompareResult result = env.baselineService().compareWithBaseline("golden");
        assertTrue(result.verdict() == CompareVerdict.NEW_CODE_UNCOVERED
                        || result.verdict() == CompareVerdict.NEW_CODE_UNCOVERED_AND_REGRESSION,
                "必须给出新增代码未覆盖的可区分结论，实际: " + result.verdict());
        assertTrue(result.uncoveredNewElementCount() >= 2, "新增文件的两个分支未覆盖");
        assertTrue(result.reasons().get(0).contains("新增可覆盖元素"));
    }

    @Test
    void regressionBeyondThresholdIsReported(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment baselineEnv = TestSupport.newCustomEnvironment(
                tempDir.resolve("data"), tempDir.resolve("src"), "Logic.java", VERSION_ONE, 1.0d);
        SourceManifest baselineManifest = baselineEnv.analyze();
        baselineEnv.coverageService().createBatch("full");
        baselineEnv.coverageService().uploadEvents("full", allEvents(baselineManifest));
        baselineEnv.baselineService().saveBaseline("golden");

        TestSupport.Environment env = TestSupport.newCustomEnvironment(
                tempDir.resolve("data"), tempDir.resolve("src3"), "Logic.java", VERSION_ONE, 1.0d);
        SourceManifest manifest = env.analyze();
        Thread.sleep(10);
        env.coverageService().createBatch("partial");
        List<CoverableElement> onlyFirstReturn = manifest.files().stream()
                .flatMap(file -> file.elements().stream())
                .filter(element -> element.type() == ElementType.LINE && element.line() == 5)
                .toList();
        env.coverageService().uploadEvents("partial",
                onlyFirstReturn.stream().map(e -> new CoverageEvent(e.id())).toList());

        CompareResult regressed = env.baselineService().compareWithBaseline("golden");
        assertEquals(CompareVerdict.REGRESSION, regressed.verdict());
        assertTrue(regressed.currentOverallPercent() < regressed.baselineOverallPercent());
        assertTrue(regressed.reasons().stream().anyMatch(reason -> reason.contains("回落")));
    }

    @Test
    void comparisonIsReproducible(@TempDir Path tempDir) {
        TestSupport.Environment env = TestSupport.newCustomEnvironment(
                tempDir.resolve("data"), tempDir.resolve("src"), "Logic.java", VERSION_ONE, 5.0d);
        SourceManifest manifest = env.analyze();
        env.coverageService().createBatch("full");
        env.coverageService().uploadEvents("full", allEvents(manifest));
        env.baselineService().saveBaseline("golden");

        CompareResult first = env.baselineService().compareWithBaseline("golden");
        CompareResult second = env.baselineService().compareWithBaseline("golden");
        assertEquals(first, second);
    }

    @Test
    void noNewRunsAfterBaselineMeansNoChange(@TempDir Path tempDir) {
        TestSupport.Environment env = TestSupport.newCustomEnvironment(
                tempDir.resolve("data"), tempDir.resolve("src"), "Logic.java", VERSION_ONE, 1.0d);
        SourceManifest manifest = env.analyze();
        env.coverageService().createBatch("partial");
        CoverableElement one = TestSupport.elements(manifest, ElementType.LINE).get(0);
        env.coverageService().uploadEvents("partial", List.of(new CoverageEvent(one.id())));
        env.baselineService().saveBaseline("golden");

        CompareResult result = env.baselineService().compareWithBaseline("golden");
        assertEquals(CompareVerdict.PASS, result.verdict(), "基线后无新运行，视为无变化");
        assertTrue(result.reasons().get(0).contains("没有新的测试运行"));
    }

    private static List<CoverageEvent> allEvents(SourceManifest manifest) {
        return manifest.files().stream()
                .flatMap(file -> file.elements().stream())
                .map(element -> new CoverageEvent(element.id()))
                .toList();
    }
}
