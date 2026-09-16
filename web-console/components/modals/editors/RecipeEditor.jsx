"use client";

import { useState } from "react";
import { Hammer, Plus, Trash2 } from "lucide-react";

export default function RecipeEditor({ payload = {}, onChange }) {
  const rawInputs = Array.isArray(payload.inputs)
    ? payload.inputs.map((inp) => ({
        item: inp.item || inp.item_id || inp.id || "",
        qty: inp.qty || inp.quantity || inp.count || 1,
      }))
    : [{ item: "", qty: 1 }];

  const rawOutputs = Array.isArray(payload.outputs)
    ? payload.outputs.map((out) => ({
        item: out.item || out.item_id || out.id || "",
        qty: out.qty || out.quantity || out.count || 1,
        chance: out.chance ?? 100,
      }))
    : [{ item: "", qty: 1, chance: 100 }];

  const [form, setForm] = useState({
    name: payload.name || payload.display || "",
    station: payload.station || "CRAFTING_TABLE",
    duration_seconds: payload.duration_seconds ?? 0,
    required_level: payload.required_level ?? 1,
    inputs: rawInputs.length > 0 ? rawInputs : [{ item: "", qty: 1 }],
    outputs: rawOutputs.length > 0 ? rawOutputs : [{ item: "", qty: 1, chance: 100 }],
  });

  const notifyParent = (updated) => {
    onChange({
      name: updated.name,
      station: updated.station,
      duration_seconds: Number(updated.duration_seconds) || 0,
      required_level: Number(updated.required_level) || 1,
      inputs: updated.inputs
        .filter((i) => i.item.trim())
        .map((i) => ({ item: i.item.trim(), qty: Number(i.qty) || 1 })),
      outputs: updated.outputs
        .filter((o) => o.item.trim())
        .map((o) => ({
          item: o.item.trim(),
          qty: Number(o.qty) || 1,
          chance: Number(o.chance) || 100,
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
          <Hammer size={16} style={{ color: "var(--accent)" }} /> Recipe Overview
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 14 }}>
          <div>
            <label style={labelStyle}>Recipe Name *</label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => updateField("name", e.target.value)}
              required
              placeholder="e.g. Smelt Refined Steel"
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Crafting Station</label>
            <select
              value={form.station}
              onChange={(e) => updateField("station", e.target.value)}
              style={inputStyle}
            >
              <option value="CRAFTING_TABLE">Crafting Table</option>
              <option value="FURNACE">Furnace / Smelter</option>
              <option value="DIMENSIONAL_FORGE">Dimensional Forge</option>
              <option value="ALCHEMICAL_ALTAR">Alchemical Altar</option>
            </select>
          </div>
          <div>
            <label style={labelStyle}>Duration (Seconds)</label>
            <input
              type="number"
              min="0"
              value={form.duration_seconds}
              onChange={(e) => updateField("duration_seconds", Number(e.target.value))}
              style={inputStyle}
            />
          </div>
        </div>
      </div>

      {/* Inputs */}
      <div className="card" style={{ padding: 18 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <h4 style={{ margin: 0, fontSize: 14 }}>Required Ingredients (Inputs)</h4>
          <button
            type="button"
            className="page-btn"
            onClick={() => {
              const next = [...form.inputs, { item: "", qty: 1 }];
              setForm({ ...form, inputs: next });
              notifyParent({ ...form, inputs: next });
            }}
            style={{ fontSize: 12, padding: "4px 10px", display: "inline-flex", alignItems: "center", gap: 4 }}
          >
            <Plus size={13} /> Add Input
          </button>
        </div>
        {form.inputs.map((inp, idx) => (
          <div key={idx} style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 8 }}>
            <input
              type="text"
              placeholder="Item ID (e.g. iron_ingot)"
              value={inp.item}
              onChange={(e) => {
                const next = [...form.inputs];
                next[idx].item = e.target.value;
                setForm({ ...form, inputs: next });
                notifyParent({ ...form, inputs: next });
              }}
              style={{ ...inputStyle, flex: 2 }}
            />
            <input
              type="number"
              min="1"
              placeholder="Qty"
              value={inp.qty}
              onChange={(e) => {
                const next = [...form.inputs];
                next[idx].qty = Number(e.target.value) || 1;
                setForm({ ...form, inputs: next });
                notifyParent({ ...form, inputs: next });
              }}
              style={{ ...inputStyle, width: 90 }}
            />
            <button
              type="button"
              onClick={() => {
                if (form.inputs.length > 1) {
                  const next = form.inputs.filter((_, i) => i !== idx);
                  setForm({ ...form, inputs: next });
                  notifyParent({ ...form, inputs: next });
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

      {/* Outputs */}
      <div className="card" style={{ padding: 18 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <h4 style={{ margin: 0, fontSize: 14 }}>Crafted Result (Outputs)</h4>
          <button
            type="button"
            className="page-btn"
            onClick={() => {
              const next = [...form.outputs, { item: "", qty: 1, chance: 100 }];
              setForm({ ...form, outputs: next });
              notifyParent({ ...form, outputs: next });
            }}
            style={{ fontSize: 12, padding: "4px 10px", display: "inline-flex", alignItems: "center", gap: 4 }}
          >
            <Plus size={13} /> Add Output
          </button>
        </div>
        {form.outputs.map((out, idx) => (
          <div key={idx} style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 8 }}>
            <input
              type="text"
              placeholder="Item ID (e.g. refined_steel)"
              value={out.item}
              onChange={(e) => {
                const next = [...form.outputs];
                next[idx].item = e.target.value;
                setForm({ ...form, outputs: next });
                notifyParent({ ...form, outputs: next });
              }}
              style={{ ...inputStyle, flex: 2 }}
            />
            <input
              type="number"
              min="1"
              placeholder="Qty"
              value={out.qty}
              onChange={(e) => {
                const next = [...form.outputs];
                next[idx].qty = Number(e.target.value) || 1;
                setForm({ ...form, outputs: next });
                notifyParent({ ...form, outputs: next });
              }}
              style={{ ...inputStyle, width: 80 }}
            />
            <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
              <input
                type="number"
                min="1"
                max="100"
                placeholder="%"
                value={out.chance}
                onChange={(e) => {
                  const next = [...form.outputs];
                  next[idx].chance = Number(e.target.value) || 100;
                  setForm({ ...form, outputs: next });
                  notifyParent({ ...form, outputs: next });
                }}
                style={{ ...inputStyle, width: 70 }}
              />
              <span style={{ fontSize: 12, color: "var(--text-tertiary)" }}>%</span>
            </div>
            <button
              type="button"
              onClick={() => {
                if (form.outputs.length > 1) {
                  const next = form.outputs.filter((_, i) => i !== idx);
                  setForm({ ...form, outputs: next });
                  notifyParent({ ...form, outputs: next });
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
