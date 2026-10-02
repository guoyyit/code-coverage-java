package com.github.guoyyit.codecoveragejava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CoverageApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    private String prepareSource() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Service.java"), """
                public class Service {
                    int compute(int input) {
                        int value = input + 1;
                        if (value > 10) {
                            value = value * 2;
                        }
                        return value;
                    }
                }
                """);
        return sourceRoot.toAbsolutePath().toString();
    }

    @Test
    void fullWorkflowOverHttp() throws Exception {
        String sourceRoot = prepareSource();

        String analyzeBody = mockMvc.perform(post("/api/manifests/analyze")
                        .param("sourceRoot", sourceRoot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebuiltFiles[0]").value("Service.java"))
                .andReturn().getResponse().getContentAsString();
        String manifestVersion = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(analyzeBody).path("manifest").path("version").asText();

        mockMvc.perform(post("/api/batches")
                        .param("batchId", "run-1").param("sourceRoot", sourceRoot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.manifestVersion").value(manifestVersion));

        mockMvc.perform(post("/api/batches").param("batchId", "run-1").param("sourceRoot", sourceRoot))
                .andExpect(status().isConflict());

        String elementsBody = mockMvc.perform(get("/api/manifests/{version}/elements", manifestVersion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var elementIds = new com.fasterxml.jackson.databind.ObjectMapper().readTree(elementsBody)
                .findValuesAsText("id");
        org.assertj.core.api.Assertions.assertThat(elementIds).isNotEmpty();

        String eventBody = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.Map.of("elementIds", elementIds, "timestamp", 7));
        mockMvc.perform(post("/api/batches/{batchId}/events", "run-1")
                        .contentType("application/json").content(eventBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDistinctHits").value(elementIds.size()));

        mockMvc.perform(post("/api/batches/{batchId}/complete", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/batches/{batchId}/events", "run-1")
                        .contentType("application/json")
                        .content("""
                                {"elementIds": ["STMT:Unknown.java:1:deadbeef"], "timestamp": 1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"));

        mockMvc.perform(post("/api/batches/{batchId}/events", "missing")
                        .contentType("application/json")
                        .content("{\"elementIds\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/batches/{batchId}/report", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestVersion").value(manifestVersion))
                .andExpect(jsonPath("$.files[0].sourceFile").value("Service.java"));

        mockMvc.perform(post("/api/baselines/{name}", "gold")
                        .param("batchIds", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("gold"));

        mockMvc.perform(get("/api/baselines/{name}/compare", "gold")
                        .param("batchIds", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("MATCHED"))
                .andExpect(jsonPath("$.thresholdPercent").value(1.0));

        MockMultipartFile truncated = new MockMultipartFile("file", "coverage.json",
                "application/json", "{\"formatVersion\":".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/imports").file(truncated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("IMPORT_FAILED"))
                .andExpect(jsonPath("$.message").isString());
    }
}
