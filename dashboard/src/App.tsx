import { useState } from "react";
import { BrowserRouter, Link, NavLink, Route, Routes } from "react-router-dom";
import { DEFAULT_API_KEY, getApiKey, setApiKey } from "./api";
import Approvals from "./pages/Approvals";
import Compare from "./pages/Compare";
import DecisionDetail from "./pages/DecisionDetail";
import Decisions from "./pages/Decisions";
import Datasets from "./pages/Datasets";
import Failures from "./pages/Failures";
import Overview from "./pages/Overview";
import RunTrace from "./pages/RunTrace";
import VersionDetail from "./pages/VersionDetail";
import Versions from "./pages/Versions";
import "./styles.css";

const NAV = [
  { to: "/", label: "Overview" },
  { to: "/versions", label: "Versions" },
  { to: "/datasets", label: "Datasets" },
  { to: "/compare", label: "Compare" },
  { to: "/failures", label: "Failures" },
  { to: "/approvals", label: "Approvals" },
  { to: "/decisions", label: "Decisions" },
];

function Header({
  keyVersion,
  onKeyCommit,
}: {
  keyVersion: number;
  onKeyCommit: () => void;
}) {
  const [draft, setDraft] = useState(getApiKey());

  function commit() {
    const k = draft.trim() === "" ? DEFAULT_API_KEY : draft.trim();
    setApiKey(k);
    setDraft(k);
    onKeyCommit();
  }

  return (
    <header className="header">
      <div className="brand">
        <Link to="/">AgentRelease Lab</Link>
      </div>
      <nav className="nav">
        {NAV.map((n) => (
          <NavLink
            key={n.to}
            to={n.to}
            end={n.to === "/"}
            className={({ isActive }) => (isActive ? "active" : "")}
          >
            {n.label}
          </NavLink>
        ))}
      </nav>
      <div className="api-key-box">
        <label htmlFor="api-key">API key</label>
        <input
          id="api-key"
          type="text"
          value={draft}
          spellCheck={false}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") commit();
          }}
          placeholder={DEFAULT_API_KEY}
        />
        <span className="muted" title={`Data refetch counter: ${keyVersion}`}>
          key #{keyVersion}
        </span>
      </div>
    </header>
  );
}

export default function App() {
  // Bumped whenever the API key is committed so every page refetches.
  const [keyVersion, setKeyVersion] = useState(0);

  return (
    <BrowserRouter>
      <Header keyVersion={keyVersion} onKeyCommit={() => setKeyVersion((v) => v + 1)} />
      <main className="main">
        <Routes>
          <Route path="/" element={<Overview keyVersion={keyVersion} />} />
          <Route path="/versions" element={<Versions keyVersion={keyVersion} />} />
          <Route path="/versions/:id" element={<VersionDetail keyVersion={keyVersion} />} />
          <Route path="/datasets" element={<Datasets keyVersion={keyVersion} />} />
          <Route path="/compare" element={<Compare keyVersion={keyVersion} />} />
          <Route path="/runs/:id" element={<RunTrace keyVersion={keyVersion} />} />
          <Route path="/failures" element={<Failures keyVersion={keyVersion} />} />
          <Route path="/approvals" element={<Approvals keyVersion={keyVersion} />} />
          <Route path="/decisions" element={<Decisions keyVersion={keyVersion} />} />
          <Route path="/decisions/:id" element={<DecisionDetail keyVersion={keyVersion} />} />
          <Route path="*" element={<p>Not found. <Link to="/">Back to overview</Link>.</p>} />
        </Routes>
      </main>
    </BrowserRouter>
  );
}
