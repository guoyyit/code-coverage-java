package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.codecoveragejava.analysis.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.FileManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAnalyzerTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @Test
    void identifiesLinesBranchesAndProducesStableIds(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, """
                package demo;
                public class Sample {
                    int field = 42;
                    public String grade(int score) {
                        if (score >= 60) {
                            return "pass";
                        } else {
                            return "fail";
                        }
                    }
                }
                """);

        FileManifest first = analyzer.analyzeFile("Sample.java", file);
        FileManifest second = analyzer.analyzeFile("Sample.java", file);

        assertEquals(first, second, "相同源码必须生成相同清单与稳定 ID");
        assertEquals(5, first.elements().size());

        List<CoverableElement> branches = first.elements().stream()
                .filter(e -> e.type() == ElementType.BRANCH)
                .collect(Collectors.toList());
        List<CoverableElement> lines = first.elements().stream()
                .filter(e -> e.type() == ElementType.LINE)
                .collect(Collectors.toList());

        assertEquals(3, lines.size(), "if 行 + 两个 return 行");
        assertEquals(2, branches.size(), "if 产生真/假两个分支");
        assertEquals(List.of("if-false", "if-true"),
                branches.stream().map(CoverableElement::branchTag).sorted().toList());
    }

    @Test
    void elseIfOnSameLineGetsDistinctBranches(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Chain.java");
        Files.writeString(file, """
                package demo;
                public class Chain {
                    public int rank(int x) {
                        if (x > 10) {
                            return 1;
                        } else if (x > 5) {
                            return 2;
                        } else {
                            return 3;
                        }
                    }
                }
                """);

        FileManifest manifest = analyzer.analyzeFile("Chain.java", file);
        long branchCount = manifest.elements().stream()
                .filter(e -> e.type() == ElementType.BRANCH).count();
        assertEquals(4, branchCount, "外层 if 与同行的 else-if 必须各自产生真/假分支");
    }

    @Test
    void identifiesLoopAndSwitchBranches(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Control.java");
        Files.writeString(file, """
                package demo;
                public class Control {
                    public int run(int n) {
                        int sum = 0;
                        for (int i = 0; i < n; i++) {
                            sum += i;
                        }
                        switch (n) {
                            case 1: return 1;
                            default: return sum;
                        }
                    }
                }
                """);

        FileManifest manifest = analyzer.analyzeFile("Control.java", file);

        long branchCount = manifest.elements().stream()
                .filter(e -> e.type() == ElementType.BRANCH).count();
        assertTrue(branchCount >= 4, "for(2) + switch 两个 case(2) 至少 4 个分支，实际: " + branchCount);
    }

    @Test
    void rejectsUnparseableSource(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Broken.java");
        Files.writeString(file, "public class Broken { public void x() { ");

        CoverageApiException error = assertThrows(CoverageApiException.class,
                () -> analyzer.analyzeFile("Broken.java", file));
        assertTrue(error.getMessage().contains("Broken.java"));
    }

    @Test
    void countsParsedFiles(@TempDir Path dir) throws IOException {
        analyzer.resetStats();
        Path file = dir.resolve("A.java");
        Files.writeString(file, "package demo; public class A { void x() { int a = 1; } }");
        analyzer.analyzeFile("A.java", file);
        assertEquals(1, analyzer.getParsedFileCount());
    }
}
