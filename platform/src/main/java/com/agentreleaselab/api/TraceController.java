package com.agentreleaselab.api;

import com.agentreleaselab.service.TraceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trace event ingestion (batch) and timeline reads. */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceService traces;

    public TraceController(TraceService traces) {
        this.traces = traces;
    }

    public record BatchIngest(UUID evalRunId, List<Map<String, Object>> events) {}

    @PostMapping("/events")
    public Map<String, Object> ingest(@RequestBody BatchIngest body) {
        traces.recordBatch(body.evalRunId(), body.events());
        return Map.of("ingested", body.events() == null ? 0 : body.events().size());
    }

    @GetMapping("/timeline")
    public List<Map<String, Object>> timeline(@RequestParam UUID evalRunId) {
        return traces.timeline(evalRunId).stream().map(e -> Map.<String, Object>of(
                "id", e.getId(), "traceId", e.getTraceId(),
                "spanId", e.getSpanId() == null ? "" : e.getSpanId(),
                "ts", e.getTs().toString(), "kind", e.getKind(), "name", e.getName(),
                "payload", e.getPayload() == null ? Map.of() : e.getPayload(),
                "sanitized", e.isSanitized())).toList();
    }
}
