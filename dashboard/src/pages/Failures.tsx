import { Link } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import { Empty, ErrorBox, Loading, ModeBadge } from "../ui";
import type { AgentVersion, EvalRun } from "../types";

interface VersionFailures {
  version: AgentVersion;
  runs: EvalRun[];
}

function isFailure(r: EvalRun): boolean {
  const critical = r.metrics && r.metrics["critical_policy_failure"] === true;
  const status = (r.status ?? "").toUpperCase();
  return critical || status === "FAILED" || status === "BLOCKED";
}

export default function Failures({ keyVersion }: { keyVersion: number }) {
  const { data, loading, error, reload } = useFetch<VersionFailures[]>(
    async () => {
      const versions = await api.versions();
      const grouped: VersionFailures[] = await Promise.all(
        versions.map(async (version) => {
          let runs: EvalRun[] = [];
          try {
            runs = await api.evalRuns(version.id);
          } catch {
            runs = [];
          }
          return { version, runs: runs.filter(isFailure) };
        })
      );
      return grouped.filter((g) => g.runs.length > 0);
    },
    [keyVersion]
  );

  return (
    <div>
      <div className="page-head">
        <h1>Failure details</h1>
        <p>
          Eval runs with a critical policy failure, or a FAILED / BLOCKED status,
          grouped by agent version. Select a run to inspect its trace.
        </p>
      </div>

      {loading && <Loading label="Scanning eval runs for failures…" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {!loading && !error && data && (
        data.length === 0 ? (
          <Empty message="No failing eval runs found." />
        ) : (
          data.map(({ version, runs }) => (
            <div className="card" key={version.id}>
              <h3>
                <Link to={`/versions/${version.id}`}>{version.name}</Link>
                <span className="muted"> — {runs.length} failing run{runs.length === 1 ? "" : "s"}</span>
              </h3>
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Run</th>
                      <th>Scenario</th>
                      <th>Trial</th>
                      <th>Mode</th>
                      <th>Status</th>
                      <th>Critical policy failure</th>
                      <th>Error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((r) => (
                      <tr key={r.id}>
                        <td>
                          <Link to={`/runs/${r.id}`} className="mono">
                            {r.id.slice(0, 8)}
                          </Link>
                        </td>
                        <td className="mono">{r.scenarioId}</td>
                        <td>{r.trialIndex}</td>
                        <td>
                          <ModeBadge mode={r.mode} />
                        </td>
                        <td>{r.status}</td>
                        <td>{r.metrics?.["critical_policy_failure"] === true ? "yes" : "no"}</td>
                        <td className="muted">{r.error ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))
        )
      )}
    </div>
  );
}
