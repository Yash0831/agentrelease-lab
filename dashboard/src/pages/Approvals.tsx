import { useState } from "react";
import { api, ApiError } from "../api";
import { useFetch } from "../hooks";
import { Empty, ErrorBox, JsonView, Loading, fmtTs } from "../ui";

type Action = "approve" | "reject" | "execute";

export default function Approvals({ keyVersion }: { keyVersion: number }) {
  const { data, loading, error, reload } = useFetch(() => api.approvals("PENDING"), [keyVersion]);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function doAction(id: string, action: Action) {
    setBusy(id + action);
    setNotice(null);
    setActionError(null);
    try {
      const fn = action === "approve" ? api.approve : action === "reject" ? api.reject : api.execute;
      const updated = await fn(id);
      setNotice(`${action}d approval ${id.slice(0, 8)} — new status: ${updated.status}`);
      reload();
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <div className="page-head">
        <h1>Approval queue</h1>
        <p>
          Sensitive tool actions require an approval tied to the exact proposed action,
          requester, tenant, and expiry. Approvals are revalidated when executed.
        </p>
      </div>

      {notice && <div className="notice">{notice}</div>}
      {actionError && <ErrorBox message={actionError} />}

      {loading && <Loading label="Loading pending approvals…" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {!loading && !error && data && (
        data.length === 0 ? (
          <Empty message="No pending approvals yet." />
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Tool</th>
                  <th>Arguments</th>
                  <th>Requester</th>
                  <th>Expires</th>
                  <th>Fingerprint</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.map((a) => (
                  <tr key={a.id}>
                    <td className="mono">{a.tool}</td>
                    <td>
                      <JsonView value={a.args} summary="view args" />
                    </td>
                    <td className="mono">{a.requesterId.slice(0, 8)}</td>
                    <td>{fmtTs(a.expiresAt)}</td>
                    <td className="mono">{a.fingerprint.slice(0, 12)}…</td>
                    <td>
                      <div className="btn-row">
                        {(["approve", "reject", "execute"] as Action[]).map((act) => (
                          <button
                            key={act}
                            className={`btn btn-small ${act === "reject" ? "btn-danger" : act === "execute" ? "btn-secondary" : ""}`}
                            disabled={busy !== null}
                            onClick={() => doAction(a.id, act)}
                          >
                            {busy === a.id + act ? "…" : act}
                          </button>
                        ))}
                      </div>
                    </td>
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
