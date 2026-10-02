package com.github.guoyyit.codecoveragejava.tests;

import com.github.guoyyit.codecoveragejava.analyzer.JavaSourceAnalyzer;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceAnalyzerTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @Test
    void identifiesStatementsAndBranchesWithStableIds() {
        String source = """
                package com.example;

                public class Sample {
                    int add(int a, int b) {
                        if (a > 0 && b > 0) {
                            return a + b;
                        }
                        return 0;
                    }
                }
                """;

        List<CoverableElement> elements = analyzer.analyze("com/example/Sample.java", source);

        List<CoverableElement> statements = elements.stream()
                .filter(element -> element.type() == ElementType.STATEMENT).toList();
        assertTrue(statements.stream().anyMatch(element -> element.line() == 5
                && element.id().startsWith("STMT:com/example/Sample.java:5:")));
        assertTrue(statements.stream().anyMatch(element -> element.line() == 6));
        assertTrue(statements.stream().anyMatch(element -> element.line() == 8));

        List<CoverableElement> branches = elements.stream()
                .filter(element -> element.type() == ElementType.BRANCH).toList();
        assertEquals(4, branches.size());
        assertTrue(branches.get(0).id().startsWith("BR:com/example/Sample.java:5#0:"));
        assertTrue(branches.get(1).id().startsWith("BR:com/example/Sample.java:5#1:"));
        assertTrue(branches.get(2).id().startsWith("BR:com/example/Sample.java:5#2:"));
        assertTrue(branches.get(3).id().startsWith("BR:com/example/Sample.java:5#3:"));
    }

    @Test
    void ignoresCommentsAndStringLiterals() {
        String source = """
                package com.example;

                public class Literals {
                    void run() {
                        String text = "if (x) return; // fake";
                        // if (hidden) ;
                        /* while (alsoHidden) { ; } */
                        String block = \"\"\"
                                if (insideTextBlock) ;
                                \"\"\";
                        System.out.println(text + block);
                    }
                }
                """;

        List<CoverableElement> elements = analyzer.analyze("Literals.java", source);

        List<Integer> branchLines = elements.stream()
                .filter(element -> element.type() == ElementType.BRANCH)
                .map(CoverableElement::line)
                .toList();
        assertTrue(branchLines.isEmpty(), "no branches should come from literals/comments: " + branchLines);

        List<Integer> statementLines = elements.stream()
                .filter(element -> element.type() == ElementType.STATEMENT)
                .map(CoverableElement::line)
                .toList();
        assertTrue(statementLines.contains(5));
        assertTrue(statementLines.contains(11));
        assertFalse(statementLines.contains(6));
        assertFalse(statementLines.contains(7));
    }

    @Test
    void resultIsReproducibleForSameContent() {
        String source = """
                package com.example;
                public class Stable {
                    int calc(int x) {
                        return x > 0 ? x : -x;
                    }
                }
                """;

        List<CoverableElement> first = analyzer.analyze("Stable.java", source);
        List<CoverableElement> second = analyzer.analyze("Stable.java", source);
        assertEquals(first, second);
    }

    @Test
    void countsSwitchBranchesAndLoops() {
        String source = """
                package com.example;
                public class Flow {
                    int pick(int n) {
                        int result = 0;
                        switch (n) {
                            case 1:
                                result = 10;
                                break;
                            case 2:
                                result = 20;
                                break;
                        }
                        for (int i = 0; i < n; i++) {
                            result += i;
                        }
                        return result;
                    }
                }
                """;

        List<CoverableElement> branches = analyzer.analyze("Flow.java", source).stream()
                .filter(element -> element.type() == ElementType.BRANCH)
                .toList();
        long switchArms = branches.stream().filter(element -> element.description().startsWith("switch")).count();
        assertEquals(3, switchArms);
        assertTrue(branches.stream().anyMatch(element -> element.description().startsWith("for")));
    }
}
