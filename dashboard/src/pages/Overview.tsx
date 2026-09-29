import { Link } from "react-router-dom";
import { api } from "../api";
import { useFetch } from "../hooks";
import { ErrorBox, Loading } from "../ui";

const CARDS = [
  { to: "/versions", title: "Agent versions", text: "Registered agent versions, prompts, and config fingerprints." },
  { to: "/datasets", title: "Evaluation datasets", text: "Scenarios with provenance and expected outcomes." },
  { to: "/compare", title: "Baseline vs candidate", text: "Run a release evaluation and compare observed outcomes." },
  { to: "/failures", title: "Failure details", text: "Eval runs with critical policy failures or failures." },
  { to: "/approvals", title: "Approval queue", text: "Pending sensitive-action approvals: approve, reject, execute." },
  { to: "/decisions", title: "Release decisions", text: "Release verdicts with supporting evidence." },
];

export default function Overview({ keyVersion }: { keyVersion: number }) {
  const { data: health, loading, error } = useFetch(() => api.health(), [keyVersion]);

  return (
    <div>
      <div className="page-head">
        <h1>AgentRelease Lab</h1>
        <p>Evidence-backed release decisions for AI agents. Every metric shown comes from stored execution results.</p>
      </div>

      {loading && <Loading label="Checking platform health…" />}
      {error && <ErrorBox message={error} />}
      {!loading && !error && (
        <div className="notice">
          Platform reachable{health !== null && health !== undefined ? ` — health check returned a response.` : "."}
        </div>
      )}

      <div className="overview-grid">
        {CARDS.map((c) => (
          <Link key={c.to} to={c.to} className="overview-card">
            <h2>{c.title}</h2>
            <p>{c.text}</p>
          </Link>
        ))}
      </div>
    </div>
  );
}
