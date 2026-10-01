import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import { EvidenceView } from "../evidence";
import { Empty, ErrorBox, Loading, ModeBadge, VerdictBadge } from "../ui";
import type { ReleaseDecisionDetail } from "../types";

const MODES = ["fixture", "live", "replay"];

export default function Compare({ keyVersion }: { keyVersion: number }) {
  const versions = useFetch(() => api.versions(), [keyVersion]);
  const policies = useFetch(() => api.policies(), [keyVersion]);
  const datasets = useFetch(() => api.datasets(), [keyVersion]);

  const [baselineId, setBaselineId] = useState("");
  const [candidateId, setCandidateId] = useState("");
  const [policyId, setPolicyId] = useState("");
  const [datasetId, setDatasetId] = useState("");
  const [mode, setMode] = useState("fixture");
  const [batchId, setBatchId] = useState("");
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [result, setResult] = useState<ReleaseDecisionDetail | null>(null);

  const ready = versions.data && policies.data && datasets.data;
  const formValid = baselineId !== "" && candidateId !== "" && policyId !== "" && datasetId !== "" && batchId.trim() !== "";

  async function runEvaluation() {
    if (!formValid) return;
    setRunning(true);
    setRunError(null);
    setResult(null);
    try {
      const d = await api.evaluateDecision({
        candidateVersionId: candidateId,
        baselineVersionId: baselineId,
        policyId,
        datasetId,
        mode,
        batchId: batchId.trim(),
      });
      setResult(d);
    } catch (e) {
      setRunError(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
    }
  }

  return (
    <div>
      <div className="page-head">
        <h1>Baseline vs candidate</h1>
        <p>
          Run the release gate over stored evaluation runs for a baseline and a candidate
          version, then inspect the verdict and its evidence.
        </p>
      </div>

      {(versions.loading || policies.loading || datasets.loading) && <Loading label="Loading options…" />}
      {(versions.error || policies.error || datasets.error) && (
        <ErrorBox
          message={[versions.error, policies.error, datasets.error].filter(Boolean).join(" | ")}
          onRetry={() => {
            versions.reload();
            policies.reload();
            datasets.reload();
          }}
        />
      )}

      {ready && (
        <div className="card">
          <div className="form-grid">
            <div className="form-field">
              <label htmlFor="baseline">Baseline version</label>
              <select id="baseline" value={baselineId} onChange={(e) => setBaselineId(e.target.value)}>
                <option value="">Select…</option>
                {versions.data!.map((v) => (
                  <option key={v.id} value={v.id}>{v.name}</option>
                ))}
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="candidate">Candidate version</label>
              <select id="candidate" value={candidateId} onChange={(e) => setCandidateId(e.target.value)}>
                <option value="">Select…</option>
                {versions.data!.map((v) => (
                  <option key={v.id} value={v.id}>{v.name}</option>
                ))}
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="policy">Release policy</label>
              <select id="policy" value={policyId} onChange={(e) => setPolicyId(e.target.value)}>
                <option value="">Select…</option>
                {policies.data!.map((p) => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="dataset">Dataset</label>
              <select id="dataset" value={datasetId} onChange={(e) => setDatasetId(e.target.value)}>
                <option value="">Select…</option>
                {datasets.data!.map((d) => (
                  <option key={d.id} value={d.id}>{d.name}</option>
                ))}
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="mode">Mode</label>
              <select id="mode" value={mode} onChange={(e) => setMode(e.target.value)}>
                {MODES.map((m) => (
                  <option key={m} value={m}>{m}</option>
                ))}
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="batchId">Evaluation batch</label>
              <input
                id="batchId"
                type="text"
                value={batchId}
                onChange={(e) => setBatchId(e.target.value)}
                placeholder="batch-… (from a benchmark or job run)"
              />
            </div>
          </div>
          <div className="btn-row">
            <button className="btn" disabled={!formValid || running} onClick={runEvaluation}>
              {running ? "Evaluating…" : "Run release evaluation"}
            </button>
            {mode === "fixture" && (
              <span className="muted">Fixture mode uses deterministic scripted responses, not a live model.</span>
            )}
          </div>
        </div>
      )}

      {runError && <ErrorBox message={runError} />}

      {result && (
        <div className="card">
          <div className="evidence-head">
            <h2 className="section-title" style={{ margin: 0 }}>Result</h2>
            <VerdictBadge verdict={result.verdict} />
            <ModeBadge mode={mode} />
          </div>
          <EvidenceView evidence={result.evidence} decisionId={result.id} />
          <p className="mt">
            <Link to={`/decisions/${result.id}`}>Open full decision record</Link>
          </p>
        </div>
      )}

      {!result && !running && !runError && (
        <Empty message="No evaluation yet — pick a baseline, candidate, policy, dataset, batch, and mode, then run." />
      )}
    </div>
  );
}
