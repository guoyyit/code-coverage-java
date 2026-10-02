package com.github.guoyyit.codecoveragejava.web;

import com.github.guoyyit.codecoveragejava.analyzer.IncrementalManifestManager;
import com.github.guoyyit.codecoveragejava.model.AnalysisResult;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.BaselineCompareResult;
import com.github.guoyyit.codecoveragejava.model.BatchRecord;
import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import com.github.guoyyit.codecoveragejava.model.ImportResult;
import com.github.guoyyit.codecoveragejava.service.BaselineService;
import com.github.guoyyit.codecoveragejava.service.BatchService;
import com.github.guoyyit.codecoveragejava.service.CoverageImportService;
import com.github.guoyyit.codecoveragejava.service.EventIngestionService;
import com.github.guoyyit.codecoveragejava.service.ReportService;
import com.github.guoyyit.codecoveragejava.store.CoverageStore;
import com.github.guoyyit.codecoveragejava.web.dto.EventRequest;
import com.github.guoyyit.codecoveragejava.web.dto.IngestResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public class CoverageController {

    private final IncrementalManifestManager manifestManager;
    private final CoverageStore coverageStore;
    private final EventIngestionService eventIngestionService;
    private final BatchService batchService;
    private final ReportService reportService;
    private final BaselineService baselineService;
    private final CoverageImportService importService;

    public CoverageController(IncrementalManifestManager manifestManager,
                              CoverageStore coverageStore,
                              EventIngestionService eventIngestionService,
                              BatchService batchService,
                              ReportService reportService,
                              BaselineService baselineService,
                              CoverageImportService importService) {
        this.manifestManager = manifestManager;
        this.coverageStore = coverageStore;
        this.eventIngestionService = eventIngestionService;
        this.batchService = batchService;
        this.reportService = reportService;
        this.baselineService = baselineService;
        this.importService = importService;
    }

    @PostMapping("/api/manifests/analyze")
    public AnalysisResult analyze(@RequestParam String sourceRoot) {
        return manifestManager.analyze(sourceRoot);
    }

    @GetMapping("/api/manifests/{version}/elements")
    public List<com.github.guoyyit.codecoveragejava.model.CoverableElement> elements(
            @org.springframework.web.bind.annotation.PathVariable String version) {
        var manifest = coverageStore.loadManifest(version);
        if (manifest == null) {
            throw new IllegalArgumentException("unknown manifest version: " + version);
        }
        return manifest.files().stream()
                .flatMap(file -> file.elements().stream())
                .sorted(java.util.Comparator.comparing(
                        com.github.guoyyit.codecoveragejava.model.CoverableElement::id))
                .toList();
    }

    @PostMapping("/api/batches")
    public BatchRecord openBatch(@RequestParam String batchId, @RequestParam String sourceRoot) {
        return batchService.openBatch(batchId, sourceRoot);
    }

    @PostMapping("/api/batches/{batchId}/complete")
    public BatchRecord completeBatch(@PathVariable String batchId) {
        return batchService.completeBatch(batchId);
    }

    @GetMapping("/api/batches")
    public List<BatchRecord> listBatches() {
        return batchService.listBatches();
    }

    @GetMapping("/api/batches/{batchId}")
    public BatchRecord getBatch(@PathVariable String batchId) {
        return batchService.getBatch(batchId);
    }

    @PostMapping("/api/batches/{batchId}/events")
    public IngestResponse ingest(@PathVariable String batchId, @RequestBody EventRequest request) {
        CoverageEvent event = new CoverageEvent(batchId, request.elementIds(), request.timestamp());
        int accepted = eventIngestionService.ingest(event);
        return new IngestResponse(batchId, accepted, eventIngestionService.totalDistinctHits(batchId));
    }

    @GetMapping("/api/batches/{batchId}/report")
    public CoverageReport batchReport(@PathVariable String batchId) {
        return reportService.batchReport(batchId);
    }

    @GetMapping("/api/reports/merged")
    public CoverageReport mergedReport(@RequestParam(required = false) String manifestVersion,
                                       @RequestParam(required = false) List<String> batchIds) {
        if (batchIds != null && !batchIds.isEmpty()) {
            return reportService.mergedReport(batchIds);
        }
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return reportService.mergedReport(manifestVersion);
        }
        throw new IllegalArgumentException("either manifestVersion or batchIds must be provided");
    }

    @PostMapping("/api/baselines/{name}")
    public Baseline saveBaseline(@PathVariable String name,
                                 @RequestParam(required = false) List<String> batchIds) {
        return baselineService.saveBaseline(name, batchIds);
    }

    @GetMapping("/api/baselines")
    public List<Baseline> listBaselines() {
        return baselineService.listBaselines();
    }

    @GetMapping("/api/baselines/{name}")
    public Baseline getBaseline(@PathVariable String name) {
        return baselineService.getBaseline(name);
    }

    @GetMapping("/api/baselines/{name}/compare")
    public BaselineCompareResult compare(@PathVariable String name,
                                         @RequestParam(required = false) List<String> batchIds) {
        return baselineService.compare(name, batchIds);
    }

    @PostMapping("/api/imports")
    public ImportResult importData(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.importData(file.getInputStream());
    }
}
