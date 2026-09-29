package com.agentreleaselab.service;

import com.agentreleaselab.domain.Repositories;
import com.agentreleaselab.domain.Runbook;
import com.agentreleaselab.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.*;

/** Permission-aware retrieval (requirement 2, ADR-0003).
 *  Tenant + CURRENT + role filters are applied in SQL before vector ordering,
 *  so a model can never widen its own access. */
@Service
public class RetrievalService {

    private final Repositories.RunbookRepository runbooks;
    private final EmbeddingService embeddings;

    public RetrievalService(Repositories.RunbookRepository runbooks, EmbeddingService embeddings) {
        this.runbooks = runbooks;
        this.embeddings = embeddings;
    }

    public record SearchHit(String slug, String title, int version, String status,
                            String snippet, double score) {}

    public List<SearchHit> search(String query, int topK) {
        TenantContext ctx = TenantContext.get();
        int k = Math.min(Math.max(topK, 1), 10);
        String literal = embeddings.toVectorLiteral(embeddings.embedFixture(query));
        List<UUID> ids = runbooks.searchIds(ctx.tenantId(), ctx.role(), literal, k);
        if (ids.isEmpty()) return List.of();
        Map<UUID, Runbook> byId = new HashMap<>();
        runbooks.findAllById(ids).forEach(r -> byId.put(r.getId(), r));
        // Score in Java for a stable, explainable ordering value (all inputs normalized).
        float[] q = embeddings.embedFixture(query);
        List<SearchHit> hits = new ArrayList<>();
        for (UUID id : ids) {
            Runbook r = byId.get(id);
            if (r == null || r.getEmbedding() == null) continue;
            double score = EmbeddingService.cosine(q, r.getEmbedding().toArray());
            String snippet = r.getContent().length() > 280
                    ? r.getContent().substring(0, 280) + "…" : r.getContent();
            hits.add(new SearchHit(r.getSlug(), r.getTitle(), r.getVersion(), r.getStatus(), snippet, score));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits;
    }

    public List<Runbook> listCurrent() {
        return runbooks.findByTenantIdAndStatus(TenantContext.get().tenantId(), "CURRENT");
    }

    public Runbook getVersion(String slug, int version) {
        return runbooks.findByTenantIdAndSlugAndVersion(TenantContext.get().tenantId(), slug, version)
                .orElseThrow(() -> ApiException.notFound("RUNBOOK_NOT_FOUND",
                        "No such runbook version in your organization"));
    }
}
