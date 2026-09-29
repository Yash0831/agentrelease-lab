# Limitations — what AgentRelease Lab does not claim

1. **Synthetic demo data.** All tenants, users, tickets, runbooks, and service
   statuses are synthetic and labeled as such. There are no production
   customers, no real incidents, and no real access grants.
2. **Fixture mode is not AI.** The deterministic fixture LLM exists for cheap,
   reproducible CI. Its outputs measure harness behavior, not model capability.
   Reports and the dashboard always show the `mode` badge (`fixture` / `live`
   / `replay`).
3. **Hash-based fixture embeddings.** In fixture mode, document embeddings are
   deterministic hashes, not semantic vectors. Retrieval-ordering results from
   fixture runs say nothing about production retrieval quality.
4. **Sandbox measurements.** Latency, token, and cost numbers in checked-in
   reports were measured in a local sandbox (fixture mode). They are real
   measurements of those runs — not production SLOs, not benchmarks of the
   underlying model providers.
5. **Single-node lab.** One Spring Boot service, one worker process, local
   Redis/PostgreSQL via Docker Compose. No HA, no multi-region, no Kubernetes.
6. **Auth is demo-grade.** API keys in a database table; no OIDC, no MFA, no
   key rotation workflow. Do not expose the demo stack to the internet.
7. **LLM judge is probabilistic.** Where used, judge verdicts are labeled and
   excluded from critical release-gate checks.
8. **Replay ≠ fresh execution.** Recorded-response replay reproduces a past
   transcript for debugging; it does not reproduce a fresh model's
   nondeterministic behavior. The UI and reports state this explicitly.
9. **Cost estimates are estimates.** The price table (`eval/price-table.json`)
   is dated and configurable; actual provider billing may differ.
10. **No PII.** Do not load real user data, real tickets, or real credentials
    into the lab.
