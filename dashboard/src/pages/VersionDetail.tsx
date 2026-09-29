import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import { DemoNote, Empty, ErrorBox, JsonView, KeyValueTable, Loading } from "../ui";

export default function VersionDetail({ keyVersion }: { keyVersion: number }) {
  const { id } = useParams<{ id: string }>();
  const { data, loading, error, reload } = useFetch(
    () => api.version(id ?? ""),
    [keyVersion, id]
  );

  return (
    <div>
      <div className="page-head">
        <p>
          <Link to="/versions">← Agent versions</Link>
        </p>
        <h1>{data?.name ?? "Agent version"}</h1>
      </div>
      <DemoNote text="Seeded demo data — synthetic." />

      {loading && <Loading label="Loading version detail…" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {!loading && !error && !data && <Empty message="No version found." />}
      {!loading && !error && data && (
        <div>
          <div className="card">
            <h3>Identity</h3>
            <KeyValueTable
              obj={{
                id: data.id,
                name: data.name,
                model_id: data.modelId,
                doc_snapshot_id: data.docSnapshotId,
                policy_version: data.policyVersion,
              }}
            />
            <h3 className="mt">Configuration fingerprint</h3>
            <div className="fingerprint">{data.fingerprint}</div>
            <p className="muted">
              SHA-256 over prompt, model identifier, retrieval configuration, tool schemas,
              document snapshot, and policy version. Identical fingerprints mean identical configs.
            </p>
          </div>
          <div className="card">
            <h3>Prompt</h3>
            <div className="prompt-block">{data.prompt}</div>
          </div>
          <div className="card">
            <h3>Retrieval configuration</h3>
            <JsonView value={data.retrievalConfig} summary="view retrieval config" defaultOpen />
          </div>
          <div className="card">
            <h3>Tool schemas</h3>
            <JsonView value={data.toolSchemas} summary="view tool schemas" defaultOpen />
          </div>
        </div>
      )}
    </div>
  );
}
