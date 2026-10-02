package com.github.guoyyit.codecoveragejava.model;

public record BatchRecord(
        String batchId,
        String manifestVersion,
        BatchStatus status,
        long createdAt,
        long lastUpdatedAt
) {
}
