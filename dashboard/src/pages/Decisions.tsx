import { Link } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import { Empty, ErrorBox, Loading, VerdictBadge, fmtTs } from "../ui";

export default function Decisions({ keyVersion }: { keyVersion: number }) {
  const { data, loading, error, reload } = useFetch(() => api.decisions(), [keyVersion]);

  return (
    <div>
      <div className="page-head">
        <h1>Release decisions</h1>
        <p>Recorded release verdicts with their supporting evidence.</p>
      </div>

      {loading && <Loading label="Loading release decisions…" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {!loading && !error && data && (
        data.length === 0 ? (
          <Empty message="No release decisions yet." />
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Decision</th>
                  <th>Verdict</th>
                  <th>Candidate</th>
                  <th>Baseline</th>
                  <th>Decided</th>
                </tr>
              </thead>
              <tbody>
                {data.map((d) => (
                  <tr key={d.id}>
                    <td>
                      <Link to={`/decisions/${d.id}`} className="mono">
                        {d.id.slice(0, 8)}
                      </Link>
                    </td>
                    <td>
                      <VerdictBadge verdict={d.verdict} />
                    </td>
                    <td className="mono">{d.candidateVersionId.slice(0, 8)}</td>
                    <td className="mono">{d.baselineVersionId.slice(0, 8)}</td>
                    <td>{fmtTs(d.decidedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </div>
  );
}
