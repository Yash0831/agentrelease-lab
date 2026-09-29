package com.agentreleaselab.api;

import com.agentreleaselab.domain.Runbook;
import com.agentreleaselab.service.RetrievalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Runbooks + permission-aware retrieval. */
@RestController
public class RunbookController {

    private final RetrievalService retrieval;

    public RunbookController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @GetMapping("/api/runbooks")
    public List<Map<String, Object>> list() {
        return retrieval.listCurrent().stream()
                .map(r -> Map.<String, Object>of("slug", r.getSlug(), "title", r.getTitle(),
                        "version", r.getVersion(), "status", r.getStatus()))
                .toList();
    }

    @GetMapping("/api/runbooks/{slug}/versions/{version}")
    public Map<String, Object> getVersion(@PathVariable String slug, @PathVariable int version) {
        Runbook r = retrieval.getVersion(slug, version);
        return Map.of("slug", r.getSlug(), "title", r.getTitle(), "version", r.getVersion(),
                "status", r.getStatus(), "content", r.getContent());
    }

    public record SearchRequest(String query, Integer topK) {}

    @PostMapping("/api/retrieval/search")
    public Map<String, Object> search(@RequestBody SearchRequest body) {
        if (body.query() == null || body.query().isBlank()) {
            throw new com.agentreleaselab.service.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "QUERY_REQUIRED", "query is required");
        }
        List<RetrievalService.SearchHit> hits = retrieval.search(body.query(), body.topK() == null ? 5 : body.topK());
        return Map.of("results", hits.stream().map(h -> Map.of(
                "slug", h.slug(), "title", h.title(), "version", h.version(),
                "status", h.status(), "snippet", h.snippet(), "score", h.score())).toList());
    }
}
