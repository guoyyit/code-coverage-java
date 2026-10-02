package com.github.guoyyit.codecoveragejava.model;

public record CoverableElement(
        String id,
        String sourcePath,
        ElementType type,
        int line,
        String branchTag,
        String descriptor
) {
}
