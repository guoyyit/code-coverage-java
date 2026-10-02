package com.github.guoyyit.codecoveragejava.model;

import com.github.guoyyit.codecoveragejava.util.Ids;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class SourceManifest {

    private final String versionId;
    private final long createdAt;
    private final List<FileManifest> files;

    public SourceManifest(String versionId, long createdAt, List<FileManifest> files) {
        this.versionId = versionId;
        this.createdAt = createdAt;
        this.files = files;
    }

    public String versionId() {
        return versionId;
    }

    public long createdAt() {
        return createdAt;
    }

    public List<FileManifest> files() {
        return files;
    }

    public CoverableElement findElement(String elementId) {
        for (FileManifest file : files) {
            for (CoverableElement element : file.elements()) {
                if (element.id().equals(elementId)) {
                    return element;
                }
            }
        }
        return null;
    }

    public Set<String> elementIds() {
        Set<String> ids = new TreeSet<>();
        for (FileManifest file : files) {
            for (CoverableElement element : file.elements()) {
                ids.add(element.id());
            }
        }
        return ids;
    }

    public int totalLines() {
        return count(ElementType.LINE);
    }

    public int totalBranches() {
        return count(ElementType.BRANCH);
    }

    public static SourceManifest of(List<FileManifest> files) {
        List<FileManifest> sorted = files.stream()
                .map(SourceManifest::sortedFile)
                .sorted(Comparator.comparing(FileManifest::sourcePath))
                .toList();
        String hashInput = sorted.stream()
                .map(file -> file.sourcePath() + ":" + file.contentHash() + ":"
                        + file.elements().stream().map(CoverableElement::id).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n"));
        return new SourceManifest(Ids.sha256Hex("manifest|" + hashInput), System.currentTimeMillis(), sorted);
    }

    public Map<String, Integer> totalsByType() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        totals.put("LINE", totalLines());
        totals.put("BRANCH", totalBranches());
        return totals;
    }

    public TreeMap<String, CoverableElement> elementIndex() {
        TreeMap<String, CoverableElement> index = new TreeMap<>();
        for (FileManifest file : files) {
            for (CoverableElement element : file.elements()) {
                index.put(element.id(), element);
            }
        }
        return index;
    }

    private int count(ElementType type) {
        int count = 0;
        for (FileManifest file : files) {
            for (CoverableElement element : file.elements()) {
                if (element.type() == type) {
                    count++;
                }
            }
        }
        return count;
    }

    private static FileManifest sortedFile(FileManifest file) {
        List<CoverableElement> sortedElements = file.elements().stream()
                .sorted(Comparator
                        .comparingInt(CoverableElement::line)
                        .thenComparing(element -> element.type().name())
                        .thenComparing(element -> element.branchTag() == null ? "" : element.branchTag()))
                .toList();
        return new FileManifest(file.sourcePath(), file.contentHash(), sortedElements);
    }
}
