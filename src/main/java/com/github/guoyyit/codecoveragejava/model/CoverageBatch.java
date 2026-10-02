package com.github.guoyyit.codecoveragejava.model;

import java.util.SortedSet;
import java.util.TreeSet;

public final class CoverageBatch {

    private final String batchId;
    private final String manifestVersion;
    private final long createdAt;
    private final String origin;
    private BatchStatus status;
    private long finishedAt;
    private boolean frozen;
    private final SortedSet<String> hitElementIds;

    public CoverageBatch(String batchId, String manifestVersion, long createdAt,
                         BatchStatus status, long finishedAt, SortedSet<String> hitElementIds) {
        this(batchId, manifestVersion, createdAt, "EVENT", status, finishedAt, false, hitElementIds);
    }

    public CoverageBatch(String batchId, String manifestVersion, long createdAt, String origin,
                         BatchStatus status, long finishedAt, boolean frozen,
                         SortedSet<String> hitElementIds) {
        this.batchId = batchId;
        this.manifestVersion = manifestVersion;
        this.createdAt = createdAt;
        this.origin = origin == null ? "EVENT" : origin;
        this.status = status;
        this.finishedAt = finishedAt;
        this.frozen = frozen;
        this.hitElementIds = hitElementIds == null ? new TreeSet<>() : new TreeSet<>(hitElementIds);
    }

    public String batchId() {
        return batchId;
    }

    public String manifestVersion() {
        return manifestVersion;
    }

    public long createdAt() {
        return createdAt;
    }

    public String origin() {
        return origin;
    }

    public BatchStatus status() {
        return status;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public boolean frozen() {
        return frozen;
    }

    public SortedSet<String> hitElementIds() {
        return new TreeSet<>(hitElementIds);
    }

    public boolean markHit(String elementId) {
        return hitElementIds.add(elementId);
    }

    public void finish(long at) {
        if (status == BatchStatus.FINISHED) {
            throw new IllegalStateException("batch already finished: " + batchId);
        }
        status = BatchStatus.FINISHED;
        finishedAt = at;
    }

    public void freeze() {
        this.frozen = true;
    }

    public int hitCount() {
        return hitElementIds.size();
    }
}
