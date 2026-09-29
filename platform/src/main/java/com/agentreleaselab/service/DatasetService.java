package com.agentreleaselab.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Loads versioned evaluation datasets from EVAL_DATASETS_DIR (default
 *  ../eval/datasets). Datasets carry provenance + expected outcomes. */
@Service
public class DatasetService {

    private final Path datasetsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public DatasetService(@Value("${arl.datasets-dir:../eval/datasets}") String dir) {
        this.datasetsDir = Path.of(dir);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path p : datasetFiles()) {
            try {
                Map<String, Object> ds = mapper.readValue(Files.readString(p), new TypeReference<>() {});
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", ds.get("id"));
                summary.put("name", ds.get("name"));
                summary.put("description", ds.get("description"));
                summary.put("provenance", ds.get("provenance"));
                summary.put("version", ds.get("version"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> scenarios = (List<Map<String, Object>>) ds.getOrDefault("scenarios", List.of());
                summary.put("scenario_count", scenarios.size());
                summary.put("scenario_ids", scenarios.stream().map(s -> s.get("id")).toList());
                out.add(summary);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read dataset " + p, e);
            }
        }
        return out;
    }

    public Map<String, Object> get(String datasetId) {
        for (Path p : datasetFiles()) {
            try {
                Map<String, Object> ds = mapper.readValue(Files.readString(p), new TypeReference<>() {});
                if (datasetId.equals(ds.get("id"))) return ds;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read dataset " + p, e);
            }
        }
        throw ApiException.notFound("DATASET_NOT_FOUND", "No such dataset: " + datasetId);
    }

    public Map<String, Object> getScenario(String datasetId, String scenarioId) {
        Map<String, Object> ds = get(datasetId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) ds.getOrDefault("scenarios", List.of());
        return scenarios.stream().filter(s -> scenarioId.equals(s.get("id"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("SCENARIO_NOT_FOUND", "No such scenario: " + scenarioId));
    }

    private List<Path> datasetFiles() {
        if (!Files.isDirectory(datasetsDir)) return List.of();
        try (Stream<Path> s = Files.list(datasetsDir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list datasets dir " + datasetsDir, e);
        }
    }
}
