import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import { EvidenceView } from "../evidence";
import { Empty, ErrorBox, KeyValueTable, Loading, fmtTs } from "../ui";

export default function DecisionDetail({ keyVersion }: { keyVersion: number }) {
  const { id } = useParams<{ id: string }>();
  const { data, loading, error, reload } = useFetch(
    () => api.decision(id ?? ""),
    [keyVersion, id]
  );

  return (
    <div>
      <div className="page-head">
        <p>
          <Link to="/decisions">← Release decisions</Link>
        </p>
        <h1>Release decision</h1>
      </div>

      {loading && <Loading label="Loading decision…" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {!loading && !error && !data && <Empty message="No decision found." />}
      {!loading && !error && data && (
        <div>
          <div className="card">
            <h3>Summary</h3>
            <KeyValueTable
              obj={{
                id: data.id,
                verdict: data.verdict,
                decided_at: fmtTs(data.decidedAt),
              }}
            />
          </div>
          <div className="card">
            <h3>Evidence</h3>
            <EvidenceView evidence={data.evidence} decisionId={data.id} />
          </div>
        </div>
      )}
    </div>
  );
}
