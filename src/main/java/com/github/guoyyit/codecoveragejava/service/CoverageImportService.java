package com.github.guoyyit.codecoveragejava.service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.BatchStatus;
import com.github.guoyyit.codecoveragejava.model.ImportResult;
import com.github.guoyyit.codecoveragejava.model.Manifest;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class CoverageImportService {

    private final CoverageStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CoverageImportService(CoverageStore store) {
        this.store = store;
    }

    /**
     * Import a historical coverage file. Parsing and validation complete fully
     * before any state is written, so malformed / mismatched / truncated input
     * is rejected as a whole without partial import.
     */
    public ImportResult importData(InputStream input) {
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (IOException ex) {
            throw new ImportException("failed to read import file: " + ex.getMessage(), ex);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(bytes);
        } catch (JsonParseException ex) {
            throw new ImportException("malformed JSON (truncated or syntax error) at "
                    + ex.getLocation() + ": " + ex.getOriginalMessage());
        } catch (IOException ex) {
            throw new ImportException("failed to parse import file: " + ex.getMessage(), ex);
        }

        if (root == null || !root.isObject()) {
            throw new ImportException("import file must contain a JSON object");
        }

        String formatVersion = requireText(root, "formatVersion", "string");
        if (!"1.0".equals(formatVersion)) {
            throw new ImportException("unsupported formatVersion: " + formatVersion + " (supported: 1.0)");
        }
        String sourceRoot = requireText(root, "sourceRoot", "string");
        String batchId = requireText(root, "batchId", "string");
        JsonNode manifestVersionNode = root.get("manifestVersion");
        if (manifestVersionNode == null || !manifestVersionNode.isTextual()
                || manifestVersionNode.asText().isBlank()) {
            throw new ImportException("field 'manifestVersion' is required and must be a non-blank string");
        }
        String manifestVersion = manifestVersionNode.asText();

        JsonNode eventsNode = root.get("events");
        if (eventsNode == null || !eventsNode.isArray()) {
            throw new ImportException("field 'events' is required and must be an array");
        }

        store.lock().writeLock().lock();
        try {
            Manifest manifest = store.loadManifest(manifestVersion);
            if (manifest == null) {
                throw new ImportException(
                        "manifestVersion does not match any current manifest: " + manifestVersion
                                + "; analyze the source tree before importing");
            }
            if (!manifest.sourceRoot().equals(normalizeRoot(sourceRoot))) {
                throw new ImportException("sourceRoot does not match manifest " + manifestVersion
                        + ": import='" + sourceRoot + "', manifest='" + manifest.sourceRoot() + "'");
            }
            if (store.loadBatch(batchId) != null) {
                throw new ImportException("batch already exists: " + batchId);
            }

            Set<String> knownIds = new TreeSet<>();
            manifest.files().forEach(file -> file.elements()
                    .forEach(element -> knownIds.add(element.id())));

            TreeSet<String> aggregated = new TreeSet<>();
            int eventCount = 0;
            for (JsonNode eventNode : eventsNode) {
                if (!eventNode.isObject()) {
                    throw new ImportException("events[" + eventCount + "] must be an object");
                }
                JsonNode idsNode = eventNode.get("elementIds");
                if (idsNode == null || !idsNode.isArray()) {
                    throw new ImportException(
                            "events[" + eventCount + "].elementIds is required and must be an array");
                }
                int index = 0;
                for (JsonNode idNode : idsNode) {
                    if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
                        throw new ImportException("events[" + eventCount + "].elementIds["
                                + index + "] must be a non-blank string");
                    }
                    String elementId = idNode.asText();
                    if (!knownIds.contains(elementId)) {
                        throw new ImportException("element does not match current manifest "
                                + manifestVersion + ": " + elementId);
                    }
                    aggregated.add(elementId);
                    index++;
                }
                eventCount++;
            }

            BatchStatus status = BatchStatus.OPEN;
            JsonNode statusNode = root.get("batchStatus");
            if (statusNode != null) {
                if (!statusNode.isTextual()) {
                    throw new ImportException("field 'batchStatus' must be a string");
                }
                String statusText = statusNode.asText();
                try {
                    status = BatchStatus.valueOf(statusText);
                } catch (IllegalArgumentException ex) {
                    throw new ImportException("invalid batchStatus: " + statusText
                            + " (allowed: OPEN, COMPLETED)");
                }
            }

            long now = System.currentTimeMillis();
            BatchRecord batch = new BatchRecord(batchId, manifestVersion, status, now, now);
            store.saveBatch(batch);
            store.saveBatchHits(batchId, manifestVersion, List.copyOf(aggregated));
            return new ImportResult(manifest.sourceRoot(), batchId, eventCount,
                    aggregated.size(), List.copyOf(aggregated));
        } finally {
            store.lock().writeLock().unlock();
        }
    }

    private String requireText(JsonNode root, String field, String expected) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ImportException("field '" + field + "' is required and must be a "
                    + expected + " non-blank value");
        }
        return node.asText();
    }

    private String normalizeRoot(String sourceRoot) {
        return java.nio.file.Paths.get(sourceRoot).toAbsolutePath().normalize().toString();
    }
}
