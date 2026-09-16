"use client";

import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";

export default function GenericEditor({ payload = {}, onChange }) {
  const [fields, setFields] = useState(() => {
    return Object.entries(payload).map(([k, v]) => ({
      key: k,
      value: typeof v === "object" ? JSON.stringify(v) : String(v),
    }));
  });

  const notifyParent = (fList) => {
    const out = {};
    fList.forEach((f) => {
      if (!f.key.trim()) return;
      try {
        out[f.key] = JSON.parse(f.value);
      } catch {
        out[f.key] = f.value;
      }
    });
    onChange(out);
  };

  return (
    <div className="card" style={{ padding: 18 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
        <h4 style={{ margin: 0, fontSize: 14 }}>Dynamic Attributes</h4>
        <button
          type="button"
          className="page-btn"
          onClick={() => {
            const next = [...fields, { key: "", value: "" }];
            setFields(next);
          }}
          style={{ fontSize: 12, padding: "4px 10px", display: "inline-flex", alignItems: "center", gap: 4 }}
        >
          <Plus size={13} /> Add Attribute
        </button>
      </div>
      {fields.map((f, idx) => (
        <div key={idx} style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 8 }}>
          <input
            type="text"
            placeholder="Property key"
            value={f.key}
            onChange={(e) => {
              const next = [...fields];
              next[idx].key = e.target.value;
              setFields(next);
              notifyParent(next);
            }}
            style={{ ...inputStyle, flex: 1 }}
          />
          <input
            type="text"
            placeholder="Value (string, number, or JSON)"
            value={f.value}
            onChange={(e) => {
              const next = [...fields];
              next[idx].value = e.target.value;
              setFields(next);
              notifyParent(next);
            }}
            style={{ ...inputStyle, flex: 2 }}
          />
          <button
            type="button"
            onClick={() => {
              const next = fields.filter((_, i) => i !== idx);
              setFields(next);
              notifyParent(next);
            }}
            style={{
              background: "transparent",
              border: "none",
              color: "var(--danger)",
              cursor: "pointer",
              padding: 6,
            }}
          >
            <Trash2 size={16} />
          </button>
        </div>
      ))}
    </div>
  );
}

const inputStyle = {
  width: "100%",
  padding: "8px 12px",
  borderRadius: "var(--radius-sm)",
  border: "1px solid var(--border-strong)",
  background: "var(--surface)",
  color: "var(--text)",
  fontSize: 13,
  outline: "none",
};
