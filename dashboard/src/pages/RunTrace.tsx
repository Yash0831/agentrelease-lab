import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import {
  Empty,
  ErrorBox,
  JsonView,
  KeyValueTable,
  Loading,
  ModeBadge,
  fmtTs,
} from "../ui";

export default function RunTrace({ keyVersion }: { keyVersion: number }) {
  const { id } = useParams<{ id: string }>();
  const run = useFetch(() => api.evalRun(id ?? ""), [keyVersion, id]);
  const timeline = useFetch(() => api.traceTimeline(id ?? ""), [keyVersion, id]);

  const loading = run.loading || timeline.loading;
  const err = run.error ?? timeline.error;

  return (
    <div>
      <div className="page-head">
        <p>
          <Link to="/failures">← Failure details</Link>
        </p>
        <h1>Trace timeline</h1>
        <p className="mono muted">eval run {id}</p>
      </div>

      {loading && <Loading label="Loading trace…" />}
      {err && (
        <ErrorBox
          message={err}
          onRetry={() => {
            run.reload();
            timeline.reload();
          }}
        />
      )}

      {!loading && !err && run.data && (
        <div className="card">
          <h3>Eval run</h3>
          <KeyValueTable
            obj={{
              id: run.data.id,
              scenario_id: run.data.scenarioId,
              trial_index: run.data.trialIndex,
              status: run.data.status,
              error: run.data.error,
            }}
          />
          <p>
            <ModeBadge mode={run.data.mode} />
          </p>
          <JsonView value={run.data.metrics} summary="view metrics" />
        </div>
      )}

      {!loading && !err && timeline.data && (
        <div className="card">
          <h3>Events (chronological)</h3>
          {timeline.data.length === 0 ? (
            <Empty message="No trace events recorded for this run yet." />
          ) : (
            <ol className="timeline">
              {timeline.data.map((ev) => (
                <li key={ev.id}>
                  <div className="event-head">
                    <span className="kind">{ev.kind}</span>
                    <span className="event-name">{ev.name}</span>
                    <span className="event-ts">{fmtTs(ev.ts)}</span>
                    {ev.sanitized && <span className="badge badge-neutral">sanitized</span>}
                  </div>
                  <div className="mono muted">
                    trace {ev.traceId.slice(0, 8)} · span {ev.spanId.slice(0, 8)}
                  </div>
                  <JsonView value={ev.payload} summary="event payload" />
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </div>
  );
}
