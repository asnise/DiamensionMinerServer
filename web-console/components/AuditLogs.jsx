"use client";

import { useState, useEffect } from "react";
import { History, TrendingUp, TrendingDown, RefreshCw, Layers, Coins } from "lucide-react";
import { nf } from "@/lib/format";

export default function AuditLogs() {
  const [activeType, setActiveType] = useState("currency"); // "currency" | "item"
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);

  const fetchLogs = async (typeToFetch) => {
    setLoading(true);
    try {
      const res = await fetch(`/api/admin/audit?type=${typeToFetch}&limit=50`);
      const json = await res.json();
      if (res.ok && json.data) {
        setLogs(json.data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs(activeType);
  }, [activeType]);

  return (
    <div>
      <div className="toolbar" style={{ marginTop: 0, marginBottom: 16 }}>
        <div style={{ display: "flex", gap: 8 }}>
          <button
            type="button"
            className="btn-secondary"
            style={{
              padding: "6px 12px",
              fontSize: 12.5,
              fontWeight: 600,
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
              background: activeType === "currency" ? "var(--accent)" : "transparent",
              color: activeType === "currency" ? "#fff" : "var(--text)",
              borderColor: activeType === "currency" ? "var(--accent)" : "var(--border)",
            }}
            onClick={() => setActiveType("currency")}
          >
            <Coins size={14} />
            Currency Ledger (Audit Trail)
          </button>

          <button
            type="button"
            className="btn-secondary"
            style={{
              padding: "6px 12px",
              fontSize: 12.5,
              fontWeight: 600,
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
              background: activeType === "item" ? "var(--accent)" : "transparent",
              color: activeType === "item" ? "#fff" : "var(--text)",
              borderColor: activeType === "item" ? "var(--accent)" : "var(--border)",
            }}
            onClick={() => setActiveType("item")}
          >
            <Layers size={14} />
            Item Provenance Logs
          </button>
        </div>

        <button
          type="button"
          className="btn-secondary"
          style={{ padding: "6px 12px", fontSize: 12.5, display: "inline-flex", alignItems: "center", gap: 6 }}
          onClick={() => fetchLogs(activeType)}
        >
          <RefreshCw size={14} />
          Refresh
        </button>
      </div>

      <div
        className="table-container"
        style={{
          background: "var(--surface)",
          border: "1px solid var(--border)",
          borderRadius: "var(--radius)",
          overflow: "hidden",
        }}
      >
        {activeType === "currency" ? (
          <table className="data-table" style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ background: "var(--surface-subtle)", borderBottom: "1px solid var(--border)", textAlign: "left" }}>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Time</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Player</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Reason / Source</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Command</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Delta</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Idempotency Key</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6} style={{ padding: 32, textAlign: "center", color: "var(--text-tertiary)" }}>
                    Loading currency audit records...
                  </td>
                </tr>
              )}
              {!loading && logs.length === 0 && (
                <tr>
                  <td colSpan={6} style={{ padding: 48, textAlign: "center", color: "var(--text-tertiary)" }}>
                    No ledger transactions recorded yet.
                  </td>
                </tr>
              )}
              {!loading &&
                logs.map((row) => {
                  const deltaNum = Number(row.delta);
                  const isPositive = deltaNum > 0;
                  return (
                    <tr key={row.entry_id} style={{ borderBottom: "1px solid var(--border-subtle, var(--border))" }}>
                      <td style={{ padding: "12px 16px", fontSize: 12, color: "var(--text-secondary)", whiteSpace: "nowrap" }}>
                        {new Date(row.created_at).toLocaleString("en-GB", {
                          day: "2-digit",
                          month: "short",
                          hour: "2-digit",
                          minute: "2-digit",
                          second: "2-digit",
                        })}
                      </td>
                      <td style={{ padding: "12px 16px", fontWeight: 600 }}>{row.username}</td>
                      <td style={{ padding: "12px 16px" }}>
                        <span
                          style={{
                            fontFamily: "monospace",
                            fontSize: 12,
                            background: "var(--surface-hover)",
                            padding: "3px 6px",
                            borderRadius: 4,
                            border: "1px solid var(--border)",
                          }}
                        >
                          {row.reason}
                        </span>
                      </td>
                      <td style={{ padding: "12px 16px", fontSize: 12, color: "var(--text-secondary)" }}>
                        {row.command_type || "SYSTEM"}
                      </td>
                      <td style={{ padding: "12px 16px" }}>
                        <span
                          style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: 4,
                            fontWeight: 700,
                            fontSize: 13,
                            color: isPositive ? "var(--success, #16a34a)" : "var(--danger, #dc2626)",
                          }}
                        >
                          {isPositive ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
                          {isPositive ? `+${nf(deltaNum)}` : nf(deltaNum)} {row.currency}
                        </span>
                      </td>
                      <td style={{ padding: "12px 16px", fontSize: 11, fontFamily: "monospace", color: "var(--text-tertiary)" }}>
                        {row.idempotency_key}
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        ) : (
          <table className="data-table" style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ background: "var(--surface-subtle)", borderBottom: "1px solid var(--border)", textAlign: "left" }}>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Time</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Item Definition</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Item UUID</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>Action / Source</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>From Player</th>
                <th style={{ padding: "12px 16px", fontSize: 12 }}>To Player</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6} style={{ padding: 32, textAlign: "center", color: "var(--text-tertiary)" }}>
                    Loading item provenance records...
                  </td>
                </tr>
              )}
              {!loading && logs.length === 0 && (
                <tr>
                  <td colSpan={6} style={{ padding: 48, textAlign: "center", color: "var(--text-tertiary)" }}>
                    No item transactions recorded yet.
                  </td>
                </tr>
              )}
              {!loading &&
                logs.map((row) => (
                  <tr key={row.transaction_id} style={{ borderBottom: "1px solid var(--border-subtle, var(--border))" }}>
                    <td style={{ padding: "12px 16px", fontSize: 12, color: "var(--text-secondary)", whiteSpace: "nowrap" }}>
                      {new Date(row.created_at).toLocaleString("en-GB", {
                        day: "2-digit",
                        month: "short",
                        hour: "2-digit",
                        minute: "2-digit",
                        second: "2-digit",
                      })}
                    </td>
                    <td style={{ padding: "12px 16px", fontWeight: 600 }}>{row.definition_id}</td>
                    <td style={{ padding: "12px 16px", fontSize: 11, fontFamily: "monospace", color: "var(--text-tertiary)" }}>
                      {row.item_id ? `#${row.item_id.slice(0, 8)}` : "-"}
                    </td>
                    <td style={{ padding: "12px 16px" }}>
                      <span
                        style={{
                          fontFamily: "monospace",
                          fontSize: 12,
                          background: "var(--surface-hover)",
                          padding: "3px 6px",
                          borderRadius: 4,
                        }}
                      >
                        {row.source_action}
                      </span>
                    </td>
                    <td style={{ padding: "12px 16px", fontSize: 13 }}>{row.from_player || "-"}</td>
                    <td style={{ padding: "12px 16px", fontSize: 13, fontWeight: 600 }}>{row.to_player || "-"}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
