import React from "react";

// Shared presentational primitives: badges, loading/empty/error states,
// JSON viewers, and defensive renderers for unknown-shape API payloads.

export function ModeBadge({ mode }: { mode: string | null | undefined }) {
  const m = (mode ?? "unknown").toLowerCase();
  if (m === "fixture") {
    return (
      <span className="badge badge-fixture" title="Deterministic fixture responses, not a live model">
        fixture — not live AI
      </span>
    );
  }
  if (m === "live") {
    return <span className="badge badge-live">live</span>;
  }
  if (m === "replay") {
    return <span className="badge badge-replay">replay</span>;
  }
  return <span className="badge badge-neutral">{mode ?? "unknown"}</span>;
}

export function VerdictBadge({ verdict }: { verdict: string | null | undefined }) {
  const v = (verdict ?? "UNKNOWN").toUpperCase();
  const cls =
    v === "PASS"
      ? "badge-pass"
      : v === "FAIL"
        ? "badge-fail"
        : v === "BLOCKED"
          ? "badge-blocked"
          : "badge-neutral";
  return <span className={`badge ${cls}`}>{v}</span>;
}

export function Loading({ label }: { label?: string }) {
  return <div className="state state-loading">{label ?? "Loading…"}</div>;
}

export function Empty({ message }: { message: string }) {
  return <div className="state state-empty">{message}</div>;
}

export function ErrorBox({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div className="state state-error">
      <div>
        <strong>Request failed.</strong>
        <div className="error-detail">{message}</div>
      </div>
      {onRetry && (
        <button className="btn" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function DemoNote({ text }: { text: string }) {
  return (
    <div className="demo-note">
      <span className="badge badge-demo">DEMO</span>
      <span>{text}</span>
    </div>
  );
}

export function JsonView({
  value,
  summary,
  defaultOpen,
}: {
  value: unknown;
  summary?: string;
  defaultOpen?: boolean;
}) {
  return (
    <details className="json-view" open={defaultOpen}>
      <summary>{summary ?? "view JSON"}</summary>
      <pre>{stringify(value)}</pre>
    </details>
  );
}

export function stringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    return String(value);
  }
}

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

export function fmtValue(v: unknown): string {
  if (v === null || v === undefined) return "—";
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return stringify(v);
}

export function fmtTs(ts: string | null | undefined): string {
  if (!ts) return "—";
  const d = new Date(ts);
  return Number.isNaN(d.getTime()) ? ts : d.toLocaleString();
}

// Renders a flat object as a two-column definition table.
export function KeyValueTable({ obj }: { obj: Record<string, unknown> }) {
  const entries = Object.entries(obj);
  if (entries.length === 0) return <Empty message="No entries." />;
  return (
    <table className="table">
      <tbody>
        {entries.map(([k, v]) => (
          <tr key={k}>
            <th className="kv-key">{k}</th>
            <td className="kv-val">{isRecord(v) || Array.isArray(v) ? <JsonView value={v} summary="view" /> : fmtValue(v)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// Renders a homogeneous array of objects as a table, using the union of keys.
export function ObjectTable({ rows }: { rows: Record<string, unknown>[] }) {
  if (rows.length === 0) return <Empty message="No rows." />;
  const keys: string[] = [];
  for (const r of rows) {
    for (const k of Object.keys(r)) {
      if (!keys.includes(k)) keys.push(k);
    }
  }
  return (
    <div className="table-scroll">
      <table className="table">
        <thead>
          <tr>
            {keys.map((k) => (
              <th key={k}>{k}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              {keys.map((k) => (
                <td key={k}>
                  {k === "mode" ? (
                    <ModeBadge mode={typeof r[k] === "string" ? (r[k] as string) : undefined} />
                  ) : isRecord(r[k]) || Array.isArray(r[k]) ? (
                    <JsonView value={r[k]} summary="view" />
                  ) : (
                    fmtValue(r[k])
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="section">
      <h2 className="section-title">{title}</h2>
      {children}
    </section>
  );
}
