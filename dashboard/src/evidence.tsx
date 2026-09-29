import { Link } from "react-router-dom";
import {
  Empty,
  JsonView,
  KeyValueTable,
  ModeBadge,
  ObjectTable,
  VerdictBadge,
  asArray,
  fmtValue,
  isRecord,
} from "./ui";

// Renders the release-decision evidence object readably, without assuming
// more than the documented shape: {candidate,baseline,policy,dataset_id,mode,
// verdict,blockers,critical_failures,scenario_stats,baseline_success_rates,
// thresholds}. Unknown nested values fall back to JSON views.

function StrList({ items }: { items: unknown[] }) {
  if (items.length === 0) return <Empty message="None." />;
  return (
    <ul className="bullet-list">
      {items.map((it, i) => (
        <li key={i}>{isRecord(it) || Array.isArray(it) ? <JsonView value={it} summary="view" /> : fmtValue(it)}</li>
      ))}
    </ul>
  );
}

export function EvidenceView({
  evidence,
  decisionId,
}: {
  evidence: Record<string, unknown>;
  decisionId?: string;
}) {
  const verdict = typeof evidence.verdict === "string" ? evidence.verdict : undefined;
  const mode = typeof evidence.mode === "string" ? evidence.mode : undefined;
  const blockers = asArray(evidence.blockers);
  const criticalFailures = asArray(evidence.critical_failures);
  const scenarioStats = asArray(evidence.scenario_stats).filter(isRecord);
  const baselineRates = evidence.baseline_success_rates;
  const thresholds = evidence.thresholds;
  const policy = evidence.policy;
  const candidate = evidence.candidate;
  const baseline = evidence.baseline;
  const datasetId = evidence.dataset_id;

  return (
    <div className="evidence">
      <div className="evidence-head">
        {verdict && <VerdictBadge verdict={verdict} />}
        {mode && <ModeBadge mode={mode} />}
        {decisionId && <span className="mono muted">decision {decisionId.slice(0, 8)}</span>}
      </div>

      <div className="evidence-grid">
        <div className="card">
          <h3>Blockers</h3>
          <StrList items={blockers} />
        </div>
        <div className="card">
          <h3>Critical failures</h3>
          <StrList items={criticalFailures} />
        </div>
      </div>

      <div className="card">
        <h3>Per-scenario stats</h3>
        {scenarioStats.length > 0 ? (
          <ObjectTable rows={scenarioStats} />
        ) : (
          <Empty message="No scenario stats in this evidence." />
        )}
      </div>

      <div className="evidence-grid">
        <div className="card">
          <h3>Baseline success rates</h3>
          {isRecord(baselineRates) ? (
            <KeyValueTable obj={baselineRates} />
          ) : baselineRates === undefined || baselineRates === null ? (
            <Empty message="No baseline success rates recorded." />
          ) : (
            <JsonView value={baselineRates} summary="view" defaultOpen />
          )}
        </div>
        <div className="card">
          <h3>Thresholds</h3>
          {isRecord(thresholds) ? (
            <KeyValueTable obj={thresholds} />
          ) : thresholds === undefined || thresholds === null ? (
            <Empty message="No thresholds recorded." />
          ) : (
            <JsonView value={thresholds} summary="view" defaultOpen />
          )}
        </div>
      </div>

      <div className="evidence-grid">
        <div className="card">
          <h3>Policy</h3>
          {isRecord(policy) ? <KeyValueTable obj={policy} /> : <p className="muted">{fmtValue(policy)}</p>}
        </div>
        <div className="card">
          <h3>Versions</h3>
          <KeyValueTable
            obj={{
              candidate: isRecord(candidate) ? (candidate.name ?? candidate.id ?? "") : fmtValue(candidate),
              baseline: isRecord(baseline) ? (baseline.name ?? baseline.id ?? "") : fmtValue(baseline),
              dataset_id: fmtValue(datasetId),
            }}
          />
          {isRecord(candidate) && typeof candidate.id === "string" && (
            <p className="mt">
              <Link to={`/versions/${candidate.id}`}>View candidate version</Link>
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
