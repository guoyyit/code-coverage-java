package com.github.guoyyit.codecoveragejava.api;

import com.github.guoyyit.codecoveragejava.api.dto.ImportRequest;
import com.github.guoyyit.codecoveragejava.api.dto.SaveBaselineRequest;
import com.github.guoyyit.codecoveragejava.coverage.BaselineService;
import com.github.guoyyit.codecoveragejava.coverage.ImportService;
import com.github.guoyyit.codecoveragejava.model.Baseline;
import com.github.guoyyit.codecoveragejava.model.CompareResult;
import com.github.guoyyit.codecoveragejava.model.CoverageBatch;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/baselines")
public class BaselineController {

    private final BaselineService baselineService;
    private final ImportService importService;

    public BaselineController(BaselineService baselineService, ImportService importService) {
        this.baselineService = baselineService;
        this.importService = importService;
    }

    @PostMapping
    public Baseline save(@RequestBody SaveBaselineRequest request) {
        if (request == null || request.name() == null) {
            throw new com.github.guoyyit.codecoveragejava.exception.CoverageApiException(
                    400, "基线名称不能为空");
        }
        return baselineService.saveBaseline(request.name());
    }

    @GetMapping
    public List<Baseline> list() {
        return baselineService.listBaselines();
    }

    @GetMapping("/{name}")
    public Baseline get(@PathVariable String name) {
        return baselineService.getBaseline(name);
    }

    @GetMapping("/{name}/comparison")
    public CompareResult compare(@PathVariable String name) {
        return baselineService.compareWithBaseline(name);
    }

    @PostMapping("/import")
    public CoverageBatch importCoverage(@RequestBody ImportRequest request) {
        if (request == null || request.data() == null) {
            throw new com.github.guoyyit.codecoveragejava.exception.CoverageApiException(
                    400, "导入数据不能为空");
        }
        return importService.importJson(request.data());
    }
}
