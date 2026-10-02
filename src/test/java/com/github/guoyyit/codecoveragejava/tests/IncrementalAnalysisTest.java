package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.tests.support.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalAnalysisTest {

    @Test
    void unchangedFilesAreNotReparsedAndChangedFileProducesNewVersion(@TempDir Path tempDir)
            throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path srcDir = tempDir.resolve("src");
        TestSupport.Environment env = TestSupport.newEnvironment(dataDir, srcDir);
        Files.writeString(srcDir.resolve("Other.java"), """
                package demo;
                public class Other {
                    public int twice(int x) {
                        return x * 2;
                    }
                }
                """);

        SourceManifest first = env.manifestService().analyzeDirectory(srcDir);
        assertEquals(2, env.analyzer().getParsedFileCount());
        SourceManifest rescanned = env.manifestService().analyzeDirectory(srcDir);
        assertEquals(first.versionId(), rescanned.versionId(), "源码未变，版本必须保持一致");
        assertEquals(0, env.analyzer().getParsedFileCount(), "未变化文件不得重复分析");

        Files.writeString(srcDir.resolve("Other.java"), """
                package demo;
                public class Other {
                    public int thrice(int x) {
                        if (x > 0) {
                            return x * 3;
                        }
                        return 0;
                    }
                }
                """);

        SourceManifest second = env.manifestService().analyzeDirectory(srcDir);
        assertEquals(1, env.analyzer().getParsedFileCount(), "只有变化的文件被重新分析");
        assertNotEquals(first.versionId(), second.versionId());
        assertNotNull(env.manifestService().getManifest(first.versionId()),
                "历史清单版本仍可查询");
    }

    @Test
    void removedFileDropsFromManifest(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        TestSupport.Environment env =
                TestSupport.newEnvironment(tempDir.resolve("data"), srcDir);
        Files.writeString(srcDir.resolve("Temp.java"), """
                package demo;
                public class Temp { void x() { int a = 1; } }
                """);
        SourceManifest withTemp = env.manifestService().analyzeDirectory(srcDir);
        assertEquals(2, withTemp.files().size());

        Files.delete(srcDir.resolve("Temp.java"));
        SourceManifest withoutTemp = env.manifestService().analyzeDirectory(srcDir);
        assertEquals(1, withoutTemp.files().size());
        assertEquals(0, env.analyzer().getParsedFileCount(), "仅删除文件时无需重新解析");
    }
}
