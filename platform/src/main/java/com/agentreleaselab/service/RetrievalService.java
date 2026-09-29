package com.agentreleaselab.service;

import com.agentreleaselab.domain.RunbookRepository;
import com.agentreleaselab.domain.Runbook;
import com.agentreleaselab.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.*;

/** Permission-aware retrieval (requirement 2, ADR-0003).
 *  Tenant + CURRENT + role filters are applied in SQL before vector ordering,
 *  so a model can never widen its own access. */
@Service
public class RetrievalService {

    private final RunbookRepository runbooks;
    private final EmbeddingService embeddings;

    public RetrievalService(RunbookRepository runbooks, EmbeddingService embeddings) {
        this.runbooks = runbooks;
        this.embeddings = embeddings;
    }

    public record SearchHit(String slug, String title, int version, String status,
                            String snippet, double score) {}

    public List<SearchHit> search(String query, int topK) {
        return search(query, topK, false);
    }

    /** @param includeStale when true, STALE runbooks are also retrievable
     *  (chaos knob "include_stale_runbooks"; the agent must reconcile them
     *  against live service state instead of acting on them). */
    public List<SearchHit> search(String query, int topK, boolean includeStale) {
        TenantContext ctx = TenantContext.get();
        int k = Math.min(Math.max(topK, 1), 10);
        String literal = embeddings.toVectorLiteral(embeddings.embedFixture(query));
        // Fetch a wider candidate set, then re-rank deterministically in Java.
        List<UUID> ids = includeStale
                ? runbooks.searchIdsWithStatuses(ctx.tenantId(), ctx.role(), literal, 20,
                        new String[]{"CURRENT", "STALE"})
                : runbooks.searchIds(ctx.tenantId(), ctx.role(), literal, 20);
        if (ids.isEmpty()) return List.of();
        Map<UUID, Runbook> byId = new HashMap<>();
        runbooks.findAllById(ids).forEach(r -> byId.put(r.getId(), r));
        // Score in Java for a stable, explainable ordering value (all inputs normalized).
        // Fixture mode blends vector similarity with a deterministic lexical overlap
        // so that an obviously relevant runbook (shared keywords) ranks first even
        // though fixture embeddings are hash-based, not semantic (ADR-0003).
        float[] q = embeddings.embedFixture(query);
        Set<String> queryTokens = tokens(query);
        List<SearchHit> hits = new ArrayList<>();
        for (UUID id : ids) {
            Runbook r = byId.get(id);
            if (r == null || r.getEmbedding() == null) continue;
            double cosine = EmbeddingService.cosine(q, r.getEmbedding());
            double lexical = overlap(queryTokens, tokens(r.getTitle() + " " + r.getContent()));
            double score = 0.7 * cosine + 0.3 * lexical;
            String snippet = r.getContent().length() > 280
                    ? r.getContent().substring(0, 280) + "…" : r.getContent();
            hits.add(new SearchHit(r.getSlug(), r.getTitle(), r.getVersion(), r.getStatus(), snippet, score));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String t : text.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() > 2) out.add(t);
        }
        return out;
    }

    private static double overlap(Set<String> queryTokens, Set<String> docTokens) {
        if (queryTokens.isEmpty()) return 0;
        long hit = queryTokens.stream().filter(docTokens::contains).count();
        return (double) hit / queryTokens.size();
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
