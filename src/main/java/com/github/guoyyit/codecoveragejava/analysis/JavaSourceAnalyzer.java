package com.github.guoyyit.codecoveragejava.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import com.github.guoyyit.codecoveragejava.model.FileManifest;
import com.github.guoyyit.codecoveragejava.util.Ids;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class JavaSourceAnalyzer implements SourceAnalyzer {

    private final JavaParser javaParser;
    private final AtomicInteger parsedFileCount = new AtomicInteger();

    public JavaSourceAnalyzer() {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(configuration);
    }

    @Override
    public FileManifest analyzeFile(String relativePath, Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        ParseResult<CompilationUnit> result = javaParser.parse(file);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream()
                    .map(problem -> problem.getVerboseMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown parse error");
            throw CoverageApiException.badRequest("无法解析 Java 源文件 " + relativePath + ": " + problems);
        }
        CompilationUnit compilationUnit = result.getResult().get();
        List<CoverableElement> elements = new ArrayList<>();
        collectElements(relativePath, compilationUnit, elements);
        List<CoverableElement> deduplicated = deduplicate(elements);
        parsedFileCount.incrementAndGet();
        String contentHash = Ids.sha256Hex("content|" + relativePath + "|" + content);
        return new FileManifest(relativePath, contentHash, deduplicated);
    }

    @Override
    public int getParsedFileCount() {
        return parsedFileCount.get();
    }

    @Override
    public void resetStats() {
        parsedFileCount.set(0);
    }

    private void collectElements(String sourcePath, Node node, List<CoverableElement> sink) {
        for (Node child : node.getChildNodes()) {
            if (child instanceof CatchClause catchClause) {
                handleCatch(sourcePath, catchClause, sink);
            } else if (child instanceof Statement statement) {
                handleStatement(sourcePath, statement, sink);
            } else if (child instanceof ConditionalExpr conditional) {
                addBranch(sourcePath, conditional.getBegin().map(p -> p.line).orElse(0),
                        "ternary", "条件表达式 ?:", conditional, sink);
            }
            collectElements(sourcePath, child, sink);
        }
    }

    private void handleCatch(String sourcePath, CatchClause catchClause, List<CoverableElement> sink) {
        int line = catchClause.getBegin().map(p -> p.line).orElse(0);
        addLine(sourcePath, line, "catch 异常处理", catchClause, sink);
        addBranch(sourcePath, line, "catch-taken", "异常被捕获", catchClause, sink);
        addBranch(sourcePath, line, "catch-not-taken", "异常未抛出", catchClause, sink);
    }

    private void handleStatement(String sourcePath, Statement statement, List<CoverableElement> sink) {
        int line = statement.getBegin().map(p -> p.line).orElse(0);
        if (statement instanceof BlockStmt) {
            return;
        }
        if (statement instanceof IfStmt ifStmt) {
            String suffix = nestingSuffix(ifStmt);
            addLine(sourcePath, line, "if 条件判断", ifStmt, sink);
            addBranch(sourcePath, line, "if-true" + suffix, "if 取真分支", ifStmt, sink);
            addBranch(sourcePath, line, "if-false" + suffix, "if 取假分支", ifStmt, sink);
        } else if (statement instanceof ForStmt || statement instanceof ForEachStmt || statement instanceof WhileStmt) {
            addLine(sourcePath, line, "循环条件判断", statement, sink);
            addBranch(sourcePath, line, "loop-enter", "循环进入", statement, sink);
            addBranch(sourcePath, line, "loop-exit", "循环退出", statement, sink);
        } else if (statement instanceof DoStmt doStmt) {
            addLine(sourcePath, line, "do-while 条件判断", doStmt, sink);
            addBranch(sourcePath, line, "loop-continue", "循环继续", doStmt, sink);
            addBranch(sourcePath, line, "loop-exit", "循环退出", doStmt, sink);
        } else if (statement instanceof SwitchStmt switchStmt) {
            addLine(sourcePath, line, "switch 选择", switchStmt, sink);
            int index = 0;
            for (SwitchEntry entry : switchStmt.getEntries()) {
                int entryLine = entry.getBegin().map(p -> p.line).orElse(line);
                String tag = "case-" + index;
                addBranch(sourcePath, entryLine, tag, "switch 分支 " + labelOf(entry), entry, sink);
                index++;
            }
            if (!hasDefaultEntry(switchStmt)) {
                addBranch(sourcePath, line, "default", "switch 默认分支(无匹配)", switchStmt, sink);
            }
        } else if (statement instanceof ReturnStmt
                || statement instanceof ThrowStmt
                || statement instanceof BreakStmt
                || statement instanceof ContinueStmt
                || statement instanceof ExplicitConstructorInvocationStmt) {
            addLine(sourcePath, line, statement.getClass().getSimpleName(), statement, sink);
        } else {
            addLine(sourcePath, line, "可执行语句 " + statement.getClass().getSimpleName(), statement, sink);
        }
    }

    private void addLine(String sourcePath, int line, String descriptor, Node node,
                         List<CoverableElement> sink) {
        if (line <= 0) {
            return;
        }
        sink.add(new CoverableElement(
                Ids.elementId(sourcePath, ElementType.LINE, line, null),
                sourcePath, ElementType.LINE, line, null, descriptor));
    }

    private String nestingSuffix(Node node) {
        int depth = 0;
        Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof IfStmt) {
                depth++;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return depth == 0 ? "" : "-n" + depth;
    }

    private void addBranch(String sourcePath, int line, String tag, String descriptor, Node node,
                           List<CoverableElement> sink) {
        if (line <= 0) {
            return;
        }
        String id = Ids.elementId(sourcePath, ElementType.BRANCH, line, tag);
        sink.add(new CoverableElement(id, sourcePath, ElementType.BRANCH, line, tag, descriptor));
    }

    private boolean hasDefaultEntry(SwitchStmt switchStmt) {
        return switchStmt.getEntries().stream().anyMatch(entry -> entry.getLabels().isEmpty());
    }

    private String labelOf(SwitchEntry entry) {
        if (entry.getLabels().isEmpty()) {
            return "default";
        }
        return entry.getLabels().stream().map(Expression::toString).reduce((a, b) -> a + "," + b).orElse("?");
    }

    private List<CoverableElement> deduplicate(List<CoverableElement> elements) {
        Set<String> seen = new TreeSet<>();
        List<CoverableElement> unique = new ArrayList<>();
        for (CoverableElement element : elements) {
            if (seen.add(element.id())) {
                unique.add(element);
            }
        }
        unique.sort(Comparator
                .comparingInt(CoverableElement::line)
                .thenComparing(element -> element.type().name())
                .thenComparing(element -> element.branchTag() == null ? "" : element.branchTag()));
        return List.copyOf(unique);
    }
}
