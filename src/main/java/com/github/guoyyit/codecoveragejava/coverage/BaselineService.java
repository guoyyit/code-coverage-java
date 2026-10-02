package com.github.guoyyit.codecoveragejava.coverage;

import com.github.guoyyit.codecoveragejava.analysis.ManifestService;
import com.github.guoyyit.codecoveragejava.config.CoverageProperties;
import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.CompareResult;
import com.github.guoyyit.codecoveragejava.model.CompareVerdict;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.CoverableElement;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import com.github.guoyyit.codecoveragejava.storage.CoverageStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class BaselineService {

    private final ManifestService manifestService;
    private final CoverageService coverageService;
    private final CoverageStorage storage;
    private final CoverageProperties properties;

    private final Map<String, Baseline> baselines = new TreeMap<>();

    public BaselineService(ManifestService manifestService, CoverageService coverageService,
                           CoverageStorage storage, CoverageProperties properties) {
        this.manifestService = manifestService;
        this.coverageService = coverageService;
        this.storage = storage;
        this.properties = properties;
        for (Baseline baseline : storage.loadBaselines()) {
            baselines.put(baseline.name(), baseline);
        }
    }

    public synchronized Baseline saveBaseline(String name) {
        if (name == null || name.isBlank()) {
            throw CoverageApiException.badRequest("基线名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128 || trimmed.contains("/") || trimmed.contains("\\")) {
            throw CoverageApiException.badRequest("非法基线名称: " + trimmed);
        }
        CoverageReport report = coverageService.aggregateReport(null);
        java.util.SortedSet<String> coveredIds =
                coverageService.allCoveredElementIds(report.manifestVersion());
        Baseline baseline = new Baseline(
                trimmed,
                report.manifestVersion(),
                System.currentTimeMillis(),
                List.copyOf(coveredIds),
                report.totalLines(),
                report.coveredLines(),
                report.totalBranches(),
                report.coveredBranches());
        baselines.put(trimmed, baseline);
        storage.saveBaseline(baseline);
        coverageService.freezeBatchesOfVersion(report.manifestVersion());
        return baseline;
    }

    public synchronized Baseline getBaseline(String name) {
        Baseline baseline = baselines.get(name);
        if (baseline == null) {
            throw CoverageApiException.notFound("基线不存在: " + name);
        }
        return baseline;
    }

    public synchronized List<Baseline> listBaselines() {
        return List.copyOf(baselines.values());
    }

    public synchronized CompareResult compareWithBaseline(String baselineName) {
        Baseline baseline = getBaseline(baselineName);
        CoverageReport currentReport = coverageService.aggregateReport(null);
        SourceManifest currentManifest =
                manifestService.getManifest(currentReport.manifestVersion());

        java.util.Set<String> currentCovered =
                new java.util.TreeSet<>(coverageService.coveredElementIds(currentManifest.versionId()));

        if (currentManifest.versionId().equals(baseline.manifestVersion())
                && !coverageService.hasUnfrozenBatches(currentManifest.versionId())) {
            return new CompareResult(
                    baseline.name(),
                    currentManifest.versionId(),
                    CompareVerdict.PASS,
                    overallPercent(baseline),
                    overallPercent(baseline),
                    properties.getRegressionThresholdPercent(),
                    List.of("基线建立后没有新的测试运行，覆盖率与基线一致"),
                    List.of(),
                    0,
                    0);
        }

        SourceManifest baselineManifest = manifestsOf(baseline.manifestVersion(), currentManifest);

        List<CoverableElement> newElements = new ArrayList<>();
        for (CoverableElement element : currentManifest.elementIndex().values()) {
            if (baselineManifest.findElement(element.id()) == null) {
                newElements.add(element);
            }
        }
        List<CoverableElement> uncoveredNew = newElements.stream()
                .filter(element -> !currentCovered.contains(element.id()))
                .toList();

        double baselinePercent = overallPercent(baseline);
        double currentPercent = currentReport.overallCoveragePercent();
        double drop = round2(baselinePercent - currentPercent);
        double threshold = properties.getRegressionThresholdPercent();
        boolean regressed = drop > threshold && currentPercent < baselinePercent;
        boolean newUncovered = properties.isFailOnNewUncoveredCode() && !uncoveredNew.isEmpty();

        List<String> reasons = new ArrayList<>();
        if (newUncovered) {
            reasons.add("新增可覆盖元素 " + newElements.size() + " 个，其中 "
                    + uncoveredNew.size() + " 个未被任何批次覆盖");
        }
        if (regressed) {
            reasons.add("整体覆盖率由 " + baselinePercent + "% 下降至 " + currentPercent
                    + "%，回落 " + drop + " 个百分点，超过阈值 " + threshold + " 个百分点");
        }
        if (!newUncovered && !regressed) {
            reasons.add("覆盖率未回退，且新增代码均已覆盖");
        }

        CompareVerdict verdict;
        if (newUncovered && regressed) {
            verdict = CompareVerdict.NEW_CODE_UNCOVERED_AND_REGRESSION;
        } else if (newUncovered) {
            verdict = CompareVerdict.NEW_CODE_UNCOVERED;
        } else if (regressed) {
            verdict = CompareVerdict.REGRESSION;
        } else {
            verdict = CompareVerdict.PASS;
        }

        return new CompareResult(
                baseline.name(),
                currentManifest.versionId(),
                verdict,
                baselinePercent,
                currentPercent,
                threshold,
                List.copyOf(reasons),
                List.copyOf(uncoveredNew),
                newElements.size(),
                uncoveredNew.size());
    }

    private SourceManifest manifestsOf(String versionId, SourceManifest fallback) {
        try {
            return manifestService.getManifest(versionId);
        } catch (CoverageApiException e) {
            return fallback;
        }
    }

    private double overallPercent(Baseline baseline) {
        int total = baseline.totalLines() + baseline.totalBranches();
        int covered = baseline.coveredLines() + baseline.coveredBranches();
        if (total == 0) {
            return 100.0d;
        }
        return round2(covered * 100.0d / total);
    }

    private static double round2(double value) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }
}
