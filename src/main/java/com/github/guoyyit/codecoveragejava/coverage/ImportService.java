package com.github.guoyyit.codecoveragejava.coverage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.codecoveragejava.analysis.ManifestService;
import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import com.github.guoyyit.codecoveragejava.model.ImportCoverageData;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class ImportService {

    private final ManifestService manifestService;
    private final CoverageService coverageService;
    private final ObjectMapper strictMapper;

    public ImportService(ManifestService manifestService, CoverageService coverageService,
                         ObjectMapper objectMapper) {
        this.manifestService = manifestService;
        this.coverageService = coverageService;
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public CoverageBatch importJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw CoverageApiException.badRequest("导入失败: 内容为空");
        }
        ImportCoverageData data = parseStrict(rawJson);
        validate(data);
        Set<String> dedup = new TreeSet<>();
        for (String elementId : data.hitElementIds()) {
            if (elementId == null || elementId.isBlank()) {
                throw CoverageApiException.badRequest("导入失败: hitElementIds 中存在空元素 ID");
            }
            dedup.add(elementId.trim());
        }
        return coverageService.importBatch(data.batchId().trim(), data.manifestVersion().trim(),
                new TreeSet<>(dedup));
    }

    ImportCoverageData parseStrict(String rawJson) {
        ImportCoverageData data;
        try {
            data = strictMapper.readValue(rawJson, ImportCoverageData.class);
        } catch (JsonProcessingException e) {
            throw CoverageApiException.badRequest("导入失败: 文件不是合法 JSON 或内容已截断: "
                    + e.getOriginalMessage());
        }
        if (data == null) {
            throw CoverageApiException.badRequest("导入失败: 文件内容为空对象");
        }
        return data;
    }

    private void validate(ImportCoverageData data) {
        if (!ImportCoverageData.FORMAT_VERSION.equals(data.formatVersion())) {
            throw CoverageApiException.badRequest("导入失败: 不支持的 formatVersion "
                    + data.formatVersion() + "，仅支持 " + ImportCoverageData.FORMAT_VERSION);
        }
        if (data.manifestVersion() == null || data.manifestVersion().isBlank()) {
            throw CoverageApiException.badRequest("导入失败: 缺少 manifestVersion");
        }
        if (data.batchId() == null || data.batchId().isBlank()) {
            throw CoverageApiException.badRequest("导入失败: 缺少 batchId");
        }
        if (data.batchId().contains("/") || data.batchId().contains("\\")
                || data.batchId().contains("..") || data.batchId().length() > 128) {
            throw CoverageApiException.badRequest("导入失败: 非法 batchId " + data.batchId());
        }
        if (data.hitElementIds() == null) {
            throw CoverageApiException.badRequest("导入失败: 缺少 hitElementIds 字段");
        }
        if (data.hitCount() != null && data.hitCount() != data.hitElementIds().size()) {
            throw CoverageApiException.badRequest("导入失败: hitCount(" + data.hitCount()
                    + ") 与实际元素条数(" + data.hitElementIds().size() + ")不一致，文件可能已截断");
        }
        Set<String> duplicated = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (String elementId : data.hitElementIds()) {
            if (elementId != null && !seen.add(elementId.trim())) {
                duplicated.add(elementId.trim());
            }
        }
        if (!duplicated.isEmpty()) {
            throw CoverageApiException.badRequest("导入失败: hitElementIds 存在重复元素: "
                    + duplicated.stream().sorted().limit(5).toList()
                    + (duplicated.size() > 5 ? " ..." : ""));
        }

        SourceManifest manifest;
        try {
            manifest = manifestService.getManifest(data.manifestVersion().trim());
        } catch (CoverageApiException e) {
            throw CoverageApiException.badRequest(
                    "导入失败: manifestVersion 与当前已知清单不匹配: " + data.manifestVersion());
        }
        List<String> mismatched = data.hitElementIds().stream()
                .map(String::trim)
                .filter(id -> manifest.findElement(id) == null)
                .sorted()
                .limit(10)
                .toList();
        if (!mismatched.isEmpty()) {
            throw CoverageApiException.badRequest(
                    "导入失败: " + mismatched.size() + " 个元素不属于清单版本 "
                            + manifest.versionId() + "，例如: " + mismatched);
        }
    }

}
