package com.github.guoyyit.codecoveragejava.analyzer;

import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.ElementType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

@Component
public class JavaSourceAnalyzer {

    private static final Set<String> CONTROL_KEYWORDS =
            Set.of("if", "for", "while", "switch", "catch", "return");

    public List<CoverableElement> analyze(String sourcePath, String content) {
        String normalizedPath = sourcePath.replace('\\', '/');
        String className = deriveClassName(normalizedPath, content);
        String masked = maskCommentsAndLiterals(content);
        String[] lines = masked.split("\n", -1);

        List<CoverableElement> elements = new ArrayList<>();
        for (int line : findStatementLines(lines)) {
            String signature = lineSignature(lines[line - 1]);
            String id = "STMT:" + normalizedPath + ":" + line + ":" + signature;
            elements.add(new CoverableElement(id, ElementType.STATEMENT, normalizedPath,
                    className, line, -1, "statement at line " + line));
        }

        int branchIndex = 0;
        for (RawBranch raw : findBranches(masked)) {
            String signature = lineSignature(lines[raw.line() - 1]);
            for (int arm = 0; arm < raw.armCount(); arm++) {
                String id = "BR:" + normalizedPath + ":" + raw.line() + "#" + branchIndex + ":" + signature;
                String description = raw.kind() + " branch " + arm + " at line " + raw.line();
                elements.add(new CoverableElement(id, ElementType.BRANCH, normalizedPath,
                        className, raw.line(), branchIndex, description));
                branchIndex++;
            }
        }
        return List.copyOf(elements);
    }

    /**
     * Short content signature of a source line (comments and literals already
     * masked). Included in element ids so that changing a line's code yields a
     * new element identity, while identical content keeps a stable reproducible id.
     */
    private String lineSignature(String maskedLine) {
        String normalized = maskedLine.trim().replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 10);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String deriveClassName(String sourcePath, String content) {
        String simpleName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        if (simpleName.endsWith(".java")) {
            simpleName = simpleName.substring(0, simpleName.length() - ".java".length());
        }
        String packageName = "";
        for (String rawLine : content.split("\n", -1)) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.substring("package ".length()).replace(";", "").trim();
                break;
            }
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")
                    && !trimmed.startsWith("*")) {
                break;
            }
        }
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * Replace comment and literal contents with spaces while preserving newlines,
     * so original line numbers stay valid.
     */
    String maskCommentsAndLiterals(String content) {
        StringBuilder out = new StringBuilder(content.length());
        int length = content.length();
        int state = 0;
        for (int i = 0; i < length; i++) {
            char ch = content.charAt(i);
            char next = i + 1 < length ? content.charAt(i + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (ch == '/' && next == '/') {
                        state = 1;
                        out.append("  ");
                        i++;
                    } else if (ch == '/' && next == '*') {
                        state = 2;
                        out.append("  ");
                        i++;
                    } else if (ch == '"' && next == '"' && i + 2 < length && content.charAt(i + 2) == '"') {
                        state = 5;
                        out.append("   ");
                        i += 2;
                    } else if (ch == '"') {
                        state = 3;
                        out.append(' ');
                    } else if (ch == '\'') {
                        state = 4;
                        out.append(' ');
                    } else {
                        out.append(ch);
                    }
                }
                case 1 -> {
                    if (ch == '\n') {
                        state = 0;
                        out.append(ch);
                    } else {
                        out.append(' ');
                    }
                }
                case 2 -> {
                    if (ch == '*' && next == '/') {
                        state = 0;
                        out.append("  ");
                        i++;
                    } else {
                        out.append(ch == '\n' ? '\n' : ' ');
                    }
                }
                case 3, 4 -> {
                    char quote = state == 3 ? '"' : '\'';
                    if (ch == '\\' && next != '\0') {
                        out.append("  ");
                        i++;
                    } else if (ch == quote) {
                        state = 0;
                        out.append(' ');
                    } else {
                        out.append(ch == '\n' ? '\n' : ' ');
                    }
                }
                case 5 -> {
                    if (ch == '"' && next == '"' && i + 2 < length && content.charAt(i + 2) == '"') {
                        state = 0;
                        out.append("   ");
                        i += 2;
                    } else {
                        out.append(ch == '\n' ? '\n' : ' ');
                    }
                }
            }
        }
        return out.toString();
    }

    private List<Integer> findStatementLines(String[] lines) {
        List<Integer> result = new ArrayList<>();
        for (int idx = 0; idx < lines.length; idx++) {
            String trimmed = lines[idx].trim();
            if (!trimmed.isEmpty() && isExecutableLine(trimmed)) {
                result.add(idx + 1);
            }
        }
        return result;
    }

    private boolean isExecutableLine(String trimmed) {
        if (trimmed.startsWith("package ") || trimmed.startsWith("import ") || trimmed.startsWith("@")) {
            return false;
        }
        if (trimmed.startsWith("case ") || trimmed.startsWith("default:")) {
            return true;
        }
        if (trimmed.equals("{") || trimmed.equals("static {")) {
            return true;
        }
        if (startsWithControlHeader(trimmed)) {
            return true;
        }
        if (trimmed.indexOf(';') >= 0) {
            return !isDeclarationOnly(trimmed);
        }
        return false;
    }

    private boolean startsWithControlHeader(String line) {
        String rest = line;
        while (rest.startsWith("}")) {
            rest = rest.substring(1).trim();
        }
        String firstWord = firstWord(rest);
        return CONTROL_KEYWORDS.contains(firstWord) || "else".equals(firstWord) || "do".equals(firstWord);
    }

    private boolean isDeclarationOnly(String line) {
        String stripped = line.replace(";", "").trim();
        if (stripped.isEmpty()) {
            return true;
        }
        if (CONTROL_KEYWORDS.contains(firstWord(stripped))) {
            return false;
        }
        boolean hasAssignment = stripped.indexOf('=') >= 0;
        boolean looksLikeMethodCall = isMethodCall(stripped);
        boolean hasControl = stripped.startsWith("throw ") || stripped.startsWith("return ")
                || stripped.startsWith("break") || stripped.startsWith("continue");
        return !hasAssignment && !looksLikeMethodCall && !hasControl;
    }

    /**
     * A method call looks like {@code name(...)} with {@code name} at line start
     * or right after a dot; a declaration has a type/modifier token before it.
     */
    private boolean isMethodCall(String stripped) {
        int paren = stripped.indexOf('(');
        if (paren <= 0) {
            return false;
        }
        int nameStart = paren;
        while (nameStart > 0 && Character.isJavaIdentifierPart(stripped.charAt(nameStart - 1))) {
            nameStart--;
        }
        if (nameStart == paren) {
            return false;
        }
        if (nameStart == 0) {
            return true;
        }
        return stripped.charAt(nameStart - 1) == '.';
    }

    private String firstWord(String text) {
        int end = 0;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : text.substring(0, end);
    }

    private List<RawBranch> findBranches(String masked) {
        List<RawBranch> branches = new ArrayList<>();
        int length = masked.length();
        for (int i = 0; i < length; i++) {
            char ch = masked.charAt(i);
            if (Character.isJavaIdentifierStart(ch)) {
                int end = i + 1;
                while (end < length && Character.isJavaIdentifierPart(masked.charAt(end))) {
                    end++;
                }
                String word = masked.substring(i, end);
                int after = end;
                while (after < length && Character.isWhitespace(masked.charAt(after))) {
                    after++;
                }
                switch (word) {
                    case "if", "while" -> {
                        if (after < length && masked.charAt(after) == '(') {
                            branches.add(new RawBranch(lineOf(masked, i), word, 2));
                            i = end - 1;
                        } else {
                            i = end - 1;
                        }
                    }
                    case "for" -> {
                        if (after < length && masked.charAt(after) == '(') {
                            branches.add(new RawBranch(lineOf(masked, i), "for", 2));
                            i = end - 1;
                        } else {
                            i = end - 1;
                        }
                    }
                    case "do" -> {
                        branches.add(new RawBranch(lineOf(masked, i), "do", 2));
                        i = end - 1;
                    }
                    case "switch" -> {
                        if (after < length && masked.charAt(after) == '(') {
                            int close = matchingParen(masked, after);
                            int brace = close == -1 ? -1 : masked.indexOf('{', close);
                            int arms = countSwitchArms(masked, brace);
                            if (arms > 0) {
                                branches.add(new RawBranch(lineOf(masked, i), "switch", arms));
                            }
                            int braceEnd = brace == -1 ? -1 : matchingBrace(masked, brace);
                            i = braceEnd == -1 ? (close == -1 ? end - 1 : close) : braceEnd;
                        } else {
                            i = end - 1;
                        }
                    }
                    default -> i = end - 1;
                }
            } else if (ch == '&' && i + 1 < length && masked.charAt(i + 1) == '&') {
                branches.add(new RawBranch(lineOf(masked, i), "&&", 2));
                i++;
            } else if (ch == '|' && i + 1 < length && masked.charAt(i + 1) == '|') {
                branches.add(new RawBranch(lineOf(masked, i), "||", 2));
                i++;
            } else if (ch == '?' && !isGenericWildcard(masked, i)
                    && findBeforeDelimiter(masked, i, ':') >= 0) {
                branches.add(new RawBranch(lineOf(masked, i), "ternary", 2));
            }
        }
        return branches;
    }

    private boolean isGenericWildcard(String text, int questionIndex) {
        int before = questionIndex - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
            before--;
        }
        return before >= 0 && text.charAt(before) == '<';
    }

    private int findBeforeDelimiter(String text, int from, char target) {
        for (int i = from + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ';' || ch == '{' || ch == '}') {
                return -1;
            }
            if (ch == target) {
                return i;
            }
        }
        return -1;
    }

    private int matchingParen(String text, int openIndex) {
        return matching(text, openIndex, '(', ')');
    }

    private int matchingBrace(String text, int openIndex) {
        return matching(text, openIndex, '{', '}');
    }

    private int matching(String text, int openIndex, char open, char close) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int countSwitchArms(String text, int braceIndex) {
        if (braceIndex < 0) {
            return 0;
        }
        int end = matchingBrace(text, braceIndex);
        if (end < 0) {
            return 0;
        }
        int cases = 0;
        boolean hasDefault = false;
        for (int i = braceIndex + 1; i < end; i++) {
            char ch = text.charAt(i);
            if (Character.isJavaIdentifierStart(ch)) {
                int wordEnd = i + 1;
                while (wordEnd < end && Character.isJavaIdentifierPart(text.charAt(wordEnd))) {
                    wordEnd++;
                }
                String word = text.substring(i, wordEnd);
                if ("case".equals(word)) {
                    cases++;
                } else if ("default".equals(word)) {
                    hasDefault = true;
                }
                i = wordEnd - 1;
            }
        }
        if (cases == 0) {
            return 0;
        }
        return hasDefault ? cases + 1 : cases + 1;
    }

    private int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private record RawBranch(int line, String kind, int armCount) {
    }
}
