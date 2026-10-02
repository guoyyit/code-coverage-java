package com.github.guoyyit.codecoveragejava.model;

public record BranchStat(
        String elementId,
        int line,
        int branchIndex,
        boolean covered,
        String description
) {
}
