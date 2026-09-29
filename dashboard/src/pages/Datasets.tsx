import { useState } from "react";
import { api } from "../api";
import { useFetch } from "../hooks";
import { Empty, ErrorBox, JsonView, Loading } from "../ui";
import type { DatasetDetail } from "../types";

function DatasetRow({
  id,
  name,
  description,
  provenance,
  version,
  scenarioCount,
  keyVersion,
}: {
  id: string;
  name: string;
  description: string;
  provenance: string;
  version: string;
  scenarioCount: number;
  keyVersion: number;
}) {
  const [open, setOpen] = useState(false);
  const { data, loading, error } = useFetch<DatasetDetail | null>(
    () => (open ? api.dataset(id) : Promise.resolve(null)),
    [keyVersion, id, open]
  );

  return (
    <div className="card">
      <h3>{name}</h3>
      <p className="muted">{description}</p>
      <p>
        <span className="muted">provenance:</span> {provenance} ·{" "}
        <span className="muted">version:</span> {version} ·{" "}
        <span className="muted">scenarios:</span> {scenarioCount}
      </p>
      <button className="btn btn-secondary btn-small" onClick={() => setOpen((o) => !o)}>
        {open ? "Hide scenarios" : "Show scenarios"}
      </button>
      {open && (
        <div className="mt">
          {loading && <Loading label="Loading scenarios…" />}
          {error && <ErrorBox message={error} />}
          {!loading && !error && data && (
            data.scenarios.length === 0 ? (
              <Empty message="No scenarios in this dataset yet." />
            ) : (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Scenario</th>
                      <th>Task</th>
                      <th>Expected outcome</th>
                      <th>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.scenarios.map((s) => (
                      <tr key={s.id}>
                        <td>
                          <strong>{s.id}</strong>
                          <div className="muted">{s.description}</div>
                        </td>
                        <td>{s.task}</td>
                        <td>{s.expected_outcome}</td>
                        <td>
                          <JsonView value={{ assertions: s.assertions, chaos: s.chaos }} summary="assertions / chaos" />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          )}
        </div>
      )}
    </div>
  );
}

export default function Datasets({ keyVersion }: { keyVersion: number }) {
  const { data, loading, error, reload } = useFetch(() => api.datasets(), [keyVersion]);

  return (
    <div>
      <div className="page-head">
        <h1>Evaluation datasets</h1>
        <p>Scenario sets with provenance and expected outcomes used by the evaluation runner.</p>
      </div>

      {loading && <Loading label="Loading datasets…" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {!loading && !error && data && (
        data.length === 0 ? (
          <Empty message="No datasets yet." />
        ) : (
          data.map((d) => (
            <DatasetRow
              key={d.id}
              id={d.id}
              name={d.name}
              description={d.description}
              provenance={d.provenance}
              version={d.version}
              scenarioCount={d.scenario_count}
              keyVersion={keyVersion}
            />
          ))
        )
      )}
    </div>
  );
}
