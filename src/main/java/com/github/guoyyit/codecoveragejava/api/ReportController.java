package com.github.guoyyit.codecoveragejava.api;

import com.github.guoyyit.codecoveragejava.coverage.CoverageService;
import com.github.guoyyit.codecoveragejava.model.CoverageReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final CoverageService coverageService;

    public ReportController(CoverageService coverageService) {
        this.coverageService = coverageService;
    }

    @GetMapping("/batches/{batchId}")
    public CoverageReport batchReport(@PathVariable String batchId) {
        return coverageService.batchReport(batchId);
    }

    @GetMapping("/aggregate")
    public CoverageReport aggregateCurrent() {
        return coverageService.aggregateReport(null);
    }

    @GetMapping("/aggregate/{versionId}")
    public CoverageReport aggregate(@PathVariable String versionId) {
        return coverageService.aggregateReport(versionId);
    }
}
