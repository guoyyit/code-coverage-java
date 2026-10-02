package com.github.guoyyit.codecoveragejava.api.dto;

import com.github.guoyyit.codecoveragejava.model.CoverageEvent;
import java.util.List;

public record EventUploadRequest(List<CoverageEvent> events) {
}
