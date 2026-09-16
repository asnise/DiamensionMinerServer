"use client";

import { useState } from "react";
import { Sparkles } from "lucide-react";

export default function UpgradeEditor({ payload = {}, onChange }) {
  const [form, setForm] = useState({
    name: payload.name || payload.display || "",
    category: payload.category || "MINING",
    max_level: payload.max_level ?? 10,
    base_cost: payload.base_cost ?? 100,
    cost_currency: payload.cost_currency || "COIN",
    cost_scaling_formula: payload.cost_scaling_formula || "base * (level ** 1.5)",
    stat_bonus_per_level: payload.stat_bonus_per_level || "",
    description: payload.description || "",
  });

  const updateField = (key, value) => {
    const updated = { ...form, [key]: value };
    setForm(updated);

    const out = {
      name: updated.name,
      display: updated.name,
      category: updated.category,
      max_level: Number(updated.max_level) || 1,
      base_cost: Number(updated.base_cost) || 0,
      cost_currency: updated.cost_currency,
      cost_scaling_formula: updated.cost_scaling_formula,
    };
    if (updated.stat_bonus_per_level) out.stat_bonus_per_level = updated.stat_bonus_per_level;
    if (updated.description) out.description = updated.description;
    onChange(out);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Sparkles size={16} style={{ color: "var(--accent)" }} /> Skill / Upgrade Properties
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
          <div>
            <label style={labelStyle}>Upgrade Title *</label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => updateField("name", e.target.value)}
              required
              placeholder="e.g. Overclocked Drill"
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Category</label>
            <select
              value={form.category}
              onChange={(e) => updateField("category", e.target.value)}
              style={inputStyle}
            >
              <option value="MINING">Mining Efficiency</option>
              <option value="COMBAT">Combat Prowess</option>
              <option value="ECONOMY">Economy & Vault</option>
              <option value="UTILITY">Utility & Storage</option>
            </select>
          </div>
          <div>
            <label style={labelStyle}>Max Level</label>
            <input
              type="number"
              min="1"
              value={form.max_level}
              onChange={(e) => updateField("max_level", Number(e.target.value))}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Base Cost</label>
            <input
              type="number"
              min="0"
              value={form.base_cost}
              onChange={(e) => updateField("base_cost", Number(e.target.value))}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Cost Currency</label>
            <select
              value={form.cost_currency}
              onChange={(e) => updateField("cost_currency", e.target.value)}
              style={inputStyle}
            >
              <option value="COIN">COIN</option>
              <option value="ENERGY">ENERGY</option>
            </select>
          </div>
          <div>
            <label style={labelStyle}>Cost Formula</label>
            <input
              type="text"
              value={form.cost_scaling_formula}
              onChange={(e) => updateField("cost_scaling_formula", e.target.value)}
              placeholder="e.g. base * (level ** 1.5)"
              style={inputStyle}
            />
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <label style={labelStyle}>Bonus Per Level</label>
          <input
            type="text"
            value={form.stat_bonus_per_level}
            onChange={(e) => updateField("stat_bonus_per_level", e.target.value)}
            placeholder="e.g. +5% Mining Speed per tier"
            style={inputStyle}
          />
        </div>
        <div style={{ marginTop: 14 }}>
          <label style={labelStyle}>Description</label>
          <textarea
            rows={2}
            value={form.description}
            onChange={(e) => updateField("description", e.target.value)}
            placeholder="e.g. Enhances dimensional drill frequency."
            style={inputStyle}
          />
        </div>
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
