package com.github.guoyyit.codecoveragejava.web.dto;

import java.util.List;

public record EventRequest(
        List<String> elementIds,
        Long timestamp
) {
}
