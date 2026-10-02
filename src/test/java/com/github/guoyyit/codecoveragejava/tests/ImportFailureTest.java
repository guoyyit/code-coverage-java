package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.ImportCoverageData;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.tests.support.TestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportFailureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void malformedJsonIsRejected(@TempDir Path tempDir) {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        env.analyze();

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson("{ not json"));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不是合法 JSON"));
    }

    @Test
    void truncatedJsonIsRejected(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        env.analyze();
        String full = validJson(env, "imp-1", 1);
        String truncated = full.substring(0, full.length() / 2);

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(truncated));
        assertTrue(error.getMessage().contains("不是合法 JSON"));
        assertBatchCount(env, 0);
    }

    @Test
    void unsupportedFormatVersionIsRejected(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        CoverableElement element = firstLine(manifest);
        String json = objectMapper.writeValueAsString(new ImportCoverageData(
                "9.9", manifest.versionId(), "imp-x", 1, List.of(element.id())));

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(json));
        assertTrue(error.getMessage().contains("formatVersion"));
        assertBatchCount(env, 0);
    }

    @Test
    void unknownManifestVersionIsRejected(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        env.analyze();
        String json = objectMapper.writeValueAsString(new ImportCoverageData(
                ImportCoverageData.FORMAT_VERSION, "0000000000000000", "imp-x",
                1, List.of("L-whatever")));

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(json));
        assertTrue(error.getMessage().contains("清单不匹配"));
        assertBatchCount(env, 0);
    }

    @Test
    void mismatchedElementsAreRejected(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        String json = objectMapper.writeValueAsString(new ImportCoverageData(
                ImportCoverageData.FORMAT_VERSION, manifest.versionId(), "imp-x",
                1, List.of("L-doesnotexist")));

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(json));
        assertTrue(error.getMessage().contains("元素不属于清单版本"));
        assertBatchCount(env, 0);
    }

    @Test
    void duplicateIdsAndHitCountMismatchAreRejected(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        CoverableElement element = firstLine(manifest);

        String duplicates = objectMapper.writeValueAsString(new ImportCoverageData(
                ImportCoverageData.FORMAT_VERSION, manifest.versionId(), "imp-dup",
                2, List.of(element.id(), element.id())));
        CoverageApiException dupError = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(duplicates));
        assertTrue(dupError.getMessage().contains("重复元素"));

        String wrongCount = objectMapper.writeValueAsString(new ImportCoverageData(
                ImportCoverageData.FORMAT_VERSION, manifest.versionId(), "imp-count",
                99, List.of(element.id())));
        CoverageApiException countError = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(wrongCount));
        assertTrue(countError.getMessage().contains("hitCount"));

        assertBatchCount(env, 0);
    }

    @Test
    void duplicateBatchIdIsRejectedAtomically(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        env.analyze();
        env.importService().importJson(validJson(env, "same-id", 2));

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> env.importService().importJson(validJson(env, "same-id", 1)));
        assertEquals(409, error.getStatus());
        assertBatchCount(env, 1);
    }

    @Test
    void validImportCreatesFinishedBatchAndReport(@TempDir Path tempDir) throws Exception {
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), tempDir.resolve("src"));
        SourceManifest manifest = env.analyze();
        CoverageBatch batch = env.importService().importJson(validJson(env, "imp-good", 3));

        assertEquals(BatchStatus.FINISHED, batch.status());
        assertEquals("IMPORT", batch.origin());
        CoverageReport report = env.coverageService().batchReport("imp-good");
        assertEquals(manifest.versionId(), report.manifestVersion());
        assertEquals(3, report.coveredLines());
    }

    private String validJson(TestSupport.Environment env, String batchId, int count) throws Exception {
        SourceManifest manifest = env.manifestService().currentManifest().orElseThrow();
        List<String> ids = TestSupport.elements(manifest, ElementType.LINE).stream()
                .limit(count)
                .map(CoverableElement::id)
                .toList();
        return objectMapper.writeValueAsString(new ImportCoverageData(
                ImportCoverageData.FORMAT_VERSION, manifest.versionId(), batchId, count, ids));
    }

    private CoverableElement firstLine(SourceManifest manifest) {
        return TestSupport.elements(manifest, ElementType.LINE).get(0);
    }

    private void assertBatchCount(TestSupport.Environment env, int expected) {
        assertEquals(expected, env.coverageService().listBatches().size(),
                "失败的导入必须整体拒绝，不得产生部分数据");
    }
}
