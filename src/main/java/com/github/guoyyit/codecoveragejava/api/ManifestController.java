package com.github.guoyyit.codecoveragejava.api;

import com.github.guoyyit.codecoveragejava.analysis.ManifestService;
import com.github.guoyyit.codecoveragejava.api.dto.AnalyzeRequest;
import com.github.guoyyit.codecoveragejava.exception.CoverageApiException;
import com.github.guoyyit.codecoveragejava.model.SourceManifest;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manifests")
public class ManifestController {

    private final ManifestService manifestService;

    public ManifestController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    @PostMapping("/analyze")
    @ResponseStatus(HttpStatus.OK)
    public SourceManifest analyze(@RequestBody AnalyzeRequest request) {
        if (request == null || request.sourceRoot() == null || request.sourceRoot().isBlank()) {
            throw CoverageApiException.badRequest("sourceRoot 不能为空");
        }
        return manifestService.analyzeDirectory(Path.of(request.sourceRoot()));
    }

    @GetMapping("/current")
    public SourceManifest current() {
        return manifestService.requireCurrentManifest();
    }

    @GetMapping("/{versionId}")
    public SourceManifest byVersion(@PathVariable String versionId) {
        return manifestService.getManifest(versionId);
    }
}
