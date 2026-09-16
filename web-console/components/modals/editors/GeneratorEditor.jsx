"use client";

import { useState } from "react";
import { Zap } from "lucide-react";

export default function GeneratorEditor({ payload = {}, onChange }) {
  const [form, setForm] = useState({
    name: payload.name || payload.display || "",
    output_currency: payload.output_currency || "COIN",
    production_rate_per_hour: payload.production_rate_per_hour ?? 100,
    max_capacity: payload.max_capacity ?? 1000,
    tick_interval_seconds: payload.tick_interval_seconds ?? 60,
    required_level: payload.required_level ?? 1,
    description: payload.description || "",
  });

  const updateField = (key, value) => {
    const updated = { ...form, [key]: value };
    setForm(updated);

    const out = {
      name: updated.name,
      display: updated.name,
      output_currency: updated.output_currency,
      production_rate_per_hour: Number(updated.production_rate_per_hour) || 0,
      max_capacity: Number(updated.max_capacity) || 0,
      tick_interval_seconds: Number(updated.tick_interval_seconds) || 60,
      required_level: Number(updated.required_level) || 1,
    };
    if (updated.description) out.description = updated.description;
    onChange(out);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Zap size={16} style={{ color: "var(--accent)" }} /> Generator Settings
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
          <div>
            <label style={labelStyle}>Generator Name *</label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => updateField("name", e.target.value)}
              required
              placeholder="e.g. Solar Energy Cell Mk II"
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Output Currency</label>
            <select
              value={form.output_currency}
              onChange={(e) => updateField("output_currency", e.target.value)}
              style={inputStyle}
            >
              <option value="COIN">COIN</option>
              <option value="ENERGY">ENERGY</option>
            </select>
          </div>
          <div>
            <label style={labelStyle}>Rate (Per Hour)</label>
            <input
              type="number"
              value={form.production_rate_per_hour}
              onChange={(e) => updateField("production_rate_per_hour", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Max Capacity Storage</label>
            <input
              type="number"
              value={form.max_capacity}
              onChange={(e) => updateField("max_capacity", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Tick Interval (Seconds)</label>
            <input
              type="number"
              value={form.tick_interval_seconds}
              onChange={(e) => updateField("tick_interval_seconds", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Required Unlock Level</label>
            <input
              type="number"
              value={form.required_level}
              onChange={(e) => updateField("required_level", e.target.value)}
              style={inputStyle}
            />
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <label style={labelStyle}>Description</label>
          <textarea
            rows={2}
            value={form.description}
            onChange={(e) => updateField("description", e.target.value)}
            placeholder="e.g. Continuously harvests ambient cosmic radiation."
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
