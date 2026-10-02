package com.github.guoyyit.codecoveragejava.web.dto;

public record IngestResponse(
        String batchId,
        int acceptedNewHits,
        int totalDistinctHits
) {
}
