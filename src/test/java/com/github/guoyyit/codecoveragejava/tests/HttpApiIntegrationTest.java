package com.github.guoyyit.codecoveragejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "coverage.data-dir=target/http-it-data")
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endToEndAnalyzeUploadReportAndCompare(@TempDir Path tempDir) throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String batchId = "it-" + suffix;
        String baselineName = "it-baseline-" + suffix;
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Calc.java"), """
                package demo;
                public class Calc {
                    public int neg(int x) {
                        if (x < 0) {
                            return -x;
                        }
                        return x;
                    }
                }
                """);

        mockMvc.perform(post("/api/manifests/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sourceRoot", src.toString()))))
                .andExpect(status().isOk());

        MvcResult manifestResult = mockMvc.perform(get("/api/manifests/current"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode manifest = objectMapper.readTree(manifestResult.getResponse().getContentAsString());
        String lineId = null;
        for (JsonNode element : manifest.get("files").get(0).get("elements")) {
            if ("LINE".equals(element.get("type").asText())) {
                lineId = element.get("id").asText();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(lineId);

        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("batchId", batchId))))
                .andExpect(status().isOk());

        String events = objectMapper.writeValueAsString(Map.of("events",
                java.util.List.of(Map.of("elementId", lineId))));
        mockMvc.perform(post("/api/batches/" + batchId + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(events))
                .andExpect(status().isOk());

        MvcResult reportResult = mockMvc.perform(get("/api/reports/aggregate"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode report = objectMapper.readTree(reportResult.getResponse().getContentAsString());
        assertEquals(1, report.get("coveredLines").asInt());
        assertTrue(report.get("files").get(0).get("uncoveredLines").size() >= 1);

        mockMvc.perform(post("/api/baselines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", baselineName))))
                .andExpect(status().isOk());
        MvcResult compareResult = mockMvc.perform(get("/api/baselines/" + baselineName + "/comparison"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode comparison = objectMapper.readTree(compareResult.getResponse().getContentAsString());
        assertEquals("PASS", comparison.get("verdict").asText());

        mockMvc.perform(get("/api/reports/batches/missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/baselines/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("data", "broken"))))
                .andExpect(status().isBadRequest());
    }
}
