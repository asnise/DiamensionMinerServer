"use client";

import { useState } from "react";
import { Gift, Plus, Trash2 } from "lucide-react";

export default function LootTableEditor({ payload = {}, onChange }) {
  const rawPools = Array.isArray(payload.pools)
    ? payload.pools.map((pl) => ({
        item: pl.item || pl.item_id || pl.id || "",
        weight: pl.weight ?? 10,
        min_qty: (pl.min_qty || pl.min) ?? 1,
        max_qty: (pl.max_qty || pl.max) ?? 1,
      }))
    : [{ item: "", weight: 10, min_qty: 1, max_qty: 1 }];

  const [form, setForm] = useState({
    name: payload.name || payload.display || "",
    min_rolls: payload.min_rolls ?? 1,
    max_rolls: payload.max_rolls ?? 1,
    pools: rawPools.length > 0 ? rawPools : [{ item: "", weight: 10, min_qty: 1, max_qty: 1 }],
  });

  const notifyParent = (updated) => {
    onChange({
      name: updated.name,
      min_rolls: Number(updated.min_rolls) || 1,
      max_rolls: Number(updated.max_rolls) || 1,
      pools: updated.pools
        .filter((p) => p.item.trim())
        .map((p) => ({
          item: p.item.trim(),
          weight: Number(p.weight) || 1,
          min_qty: Number(p.min_qty) || 1,
          max_qty: Number(p.max_qty) || 1,
        })),
    });
  };

  const updateField = (key, value) => {
    const updated = { ...form, [key]: value };
    setForm(updated);
    notifyParent(updated);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Gift size={16} style={{ color: "var(--accent)" }} /> Loot Pool Configuration
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 14 }}>
          <div>
            <label style={labelStyle}>Table Name *</label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => updateField("name", e.target.value)}
              required
              placeholder="e.g. Dragon Hoard Cache"
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Min Rolls</label>
            <input
              type="number"
              min="1"
              value={form.min_rolls}
              onChange={(e) => updateField("min_rolls", Number(e.target.value))}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Max Rolls</label>
            <input
              type="number"
              min="1"
              value={form.max_rolls}
              onChange={(e) => updateField("max_rolls", Number(e.target.value))}
              style={inputStyle}
            />
          </div>
        </div>
      </div>

      {/* Drop Items Table */}
      <div className="card" style={{ padding: 18 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <h4 style={{ margin: 0, fontSize: 14 }}>Drop Items & Weights</h4>
          <button
            type="button"
            className="page-btn"
            onClick={() => {
              const next = [...form.pools, { item: "", weight: 10, min_qty: 1, max_qty: 1 }];
              setForm({ ...form, pools: next });
              notifyParent({ ...form, pools: next });
            }}
            style={{ fontSize: 12, padding: "4px 10px", display: "inline-flex", alignItems: "center", gap: 4 }}
          >
            <Plus size={13} /> Add Drop Item
          </button>
        </div>
        {form.pools.map((p, idx) => (
          <div key={idx} style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 8 }}>
            <input
              type="text"
              placeholder="Item ID (e.g. netherite_scrap)"
              value={p.item}
              onChange={(e) => {
                const next = [...form.pools];
                next[idx].item = e.target.value;
                setForm({ ...form, pools: next });
                notifyParent({ ...form, pools: next });
              }}
              style={{ ...inputStyle, flex: 2 }}
            />
            <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
              <span style={{ fontSize: 11, color: "var(--text-tertiary)" }}>Weight:</span>
              <input
                type="number"
                min="1"
                placeholder="Weight"
                value={p.weight}
                onChange={(e) => {
                  const next = [...form.pools];
                  next[idx].weight = Number(e.target.value) || 1;
                  setForm({ ...form, pools: next });
                  notifyParent({ ...form, pools: next });
                }}
                style={{ ...inputStyle, width: 70 }}
              />
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
              <span style={{ fontSize: 11, color: "var(--text-tertiary)" }}>Qty:</span>
              <input
                type="number"
                min="1"
                placeholder="Min"
                value={p.min_qty}
                onChange={(e) => {
                  const next = [...form.pools];
                  next[idx].min_qty = Number(e.target.value) || 1;
                  setForm({ ...form, pools: next });
                  notifyParent({ ...form, pools: next });
                }}
                style={{ ...inputStyle, width: 60 }}
              />
              <span>-</span>
              <input
                type="number"
                min="1"
                placeholder="Max"
                value={p.max_qty}
                onChange={(e) => {
                  const next = [...form.pools];
                  next[idx].max_qty = Number(e.target.value) || 1;
                  setForm({ ...form, pools: next });
                  notifyParent({ ...form, pools: next });
                }}
                style={{ ...inputStyle, width: 60 }}
              />
            </div>
            <button
              type="button"
              onClick={() => {
                if (form.pools.length > 1) {
                  const next = form.pools.filter((_, i) => i !== idx);
                  setForm({ ...form, pools: next });
                  notifyParent({ ...form, pools: next });
                }
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
    </div>
  );
}

const labelStyle = {
  display: "block",
  marginBottom: 5,
  fontSize: 12,
  fontWeight: 600,
  color: "var(--text-secondary)",
};

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
