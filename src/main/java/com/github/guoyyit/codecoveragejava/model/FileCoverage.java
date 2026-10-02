package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record FileCoverage(
        String sourceFile,
        int totalStatements,
        int coveredStatements,
        int totalBranches,
        int coveredBranches,
        List<LineStat> lines,
        List<BranchStat> branches
) {
}
