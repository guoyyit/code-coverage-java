package com.github.guoyyit.codecoveragejava.api;

import com.github.guoyyit.codecoveragejava.api.dto.CreateBatchRequest;
import com.github.guoyyit.codecoveragejava.api.dto.EventUploadRequest;
import com.github.guoyyit.codecoveragejava.coverage.CoverageService;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final CoverageService coverageService;

    public BatchController(CoverageService coverageService) {
        this.coverageService = coverageService;
    }

    @PostMapping
    public CoverageBatch create(@RequestBody(required = false) CreateBatchRequest request) {
        return coverageService.createBatch(request == null ? null : request.batchId());
    }

    @GetMapping
    public List<CoverageBatch> list() {
        return coverageService.listBatches();
    }

    @GetMapping("/{batchId}")
    public CoverageBatch get(@PathVariable String batchId) {
        return coverageService.getBatch(batchId);
    }

    @PostMapping("/{batchId}/events")
    public Map<String, Object> uploadEvents(@PathVariable String batchId,
                                            @RequestBody EventUploadRequest request) {
        if (request == null || request.events() == null) {
            throw new com.github.guoyyit.codecoveragejava.exception.CoverageApiException(
                    400, "events 不能为空");
        }
        int newlyAdded = coverageService.uploadEvents(batchId, request.events());
        CoverageBatch batch = coverageService.getBatch(batchId);
        return Map.of(
                "batchId", batchId,
                "newlyAdded", newlyAdded,
                "totalHits", batch.hitCount(),
                "status", batch.status().name());
    }

    @PostMapping("/{batchId}/finish")
    public CoverageBatch finish(@PathVariable String batchId) {
        return coverageService.finishBatch(batchId);
    }
}
