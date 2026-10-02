package com.github.guoyyit.codecoveragejava.model;

import java.util.List;

public record LineStat(
        int line,
        boolean covered,
        List<String> elementIds
) {
}
