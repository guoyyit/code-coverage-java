package com.github.guoyyit.codecoveragejava.model;

public record CoverableElement(
        String id,
        ElementType type,
        String sourceFile,
        String className,
        int line,
        int branchIndex,
        String description
) {
}
