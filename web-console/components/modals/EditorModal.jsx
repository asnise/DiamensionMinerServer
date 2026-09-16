"use client";

import { useState } from "react";
import Modal from "@/components/Modal";
import { useToast } from "@/components/Toast";

const SCHEMA_MAP = {
  ItemBase: [
    { key: "name", label: "Name", type: "text" },
    { key: "rarity", label: "Rarity", type: "select", options: ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"] },
    { key: "model_path", label: "Model Path", type: "text" },
    { key: "category", label: "Category", type: "select", options: ["WEAPON", "ARMOR", "MATERIAL", "CONSUMABLE"] },
    { key: "base_stats", label: "Base Stats (JSON)", type: "json" },
  ],
  PassiveGenerator: [
    { key: "name", label: "Name", type: "text" },
    { key: "production_rate_per_hour", label: "Production Rate / Hour", type: "number" },
    { key: "max_capacity", label: "Max Capacity", type: "number" },
    { key: "output_currency", label: "Output Currency", type: "select", options: ["COIN", "ENERGY"] },
  ],
  UpgradeTree: [
    { key: "name", label: "Name", type: "text" },
    { key: "max_level", label: "Max Level", type: "number" },
    { key: "cost_scaling_formula", label: "Cost Formula (e.g. level * 100)", type: "text" },
  ],
  Recipe: [
    { key: "name", label: "Recipe Name", type: "text" },
    { key: "duration_seconds", label: "Duration (Seconds)", type: "number" },
    { key: "inputs", label: "Inputs (JSON Array)", type: "json" },
    { key: "outputs", label: "Outputs (JSON Array)", type: "json" },
  ],
  LootTable: [
    { key: "name", label: "Loot Table Name", type: "text" },
    { key: "pools", label: "Pools (JSON Array)", type: "json" },
  ],
};

export default function EditorModal({ kind, initialData, onClose }) {
  const isEditing = !!initialData;
  const toast = useToast();
  const [formData, setFormData] = useState(initialData ? initialData : { id: "", payload: {} });

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      const res = await fetch(`/api/content/${kind}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ definition_id: formData.id, payload: formData.payload }),
      });
      if (res.ok) {
        toast.success(`${kind} "${formData.id}" saved successfully`, "Changes Saved");
        onClose(true);
      } else {
        toast.error("Failed to save content definition", "Save Error");
      }
    } catch (err) {
      toast.error(err.message, "Network Error");
    }
  };

  const schema = SCHEMA_MAP[kind] || [{ key: "raw", label: "Raw Payload (JSON)", type: "json_full" }];

  const updatePayload = (key, value) => {
    setFormData((prev) => ({
      ...prev,
      payload: { ...prev.payload, [key]: value },
    }));
  };

  return (
    <Modal
      isOpen={true}
      onClose={() => onClose(false)}
      title={isEditing ? `Edit ${kind}` : `Create New ${kind}`}
      size="lg"
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={() => onClose(false)}>
            Cancel
          </button>
          <button type="submit" form="editor-form" className="btn-primary">
            Save Changes
          </button>
        </>
      }
    >
      <form id="editor-form" onSubmit={handleSubmit}>
        <div className="form-group" style={{ marginBottom: 16 }}>
          <label style={{ display: "block", marginBottom: 6, fontSize: 13, fontWeight: 600 }}>ID</label>
          <input
            type="text"
            value={formData.id}
            onChange={(e) => setFormData({ ...formData, id: e.target.value })}
            disabled={isEditing}
            required
            placeholder="e.g. item_iron_sword"
            style={{
              width: "100%",
              padding: "8px 12px",
              borderRadius: "var(--radius-sm)",
              border: "1px solid var(--border-strong)",
              background: isEditing ? "var(--surface-muted)" : "var(--surface)",
              color: "var(--text)",
              fontSize: 13.5,
            }}
          />
        </div>

        {schema.map((field) => {
          if (field.type === "json_full") {
            return (
              <div className="form-group" key={field.key} style={{ marginBottom: 16 }}>
                <label style={{ display: "block", marginBottom: 6, fontSize: 13, fontWeight: 600 }}>
                  {field.label}
                </label>
                <textarea
                  rows={10}
                  value={JSON.stringify(formData.payload, null, 2)}
                  onChange={(e) => {
                    try {
                      setFormData({ ...formData, payload: JSON.parse(e.target.value) });
                    } catch {}
                  }}
                  style={{
                    width: "100%",
                    padding: "8px 12px",
                    borderRadius: "var(--radius-sm)",
                    border: "1px solid var(--border-strong)",
                    fontFamily: "ui-monospace, monospace",
                    fontSize: 12.5,
                  }}
                />
              </div>
            );
          }

          const val = formData.payload[field.key];

          if (field.type === "select") {
            return (
              <div className="form-group" key={field.key} style={{ marginBottom: 16 }}>
                <label style={{ display: "block", marginBottom: 6, fontSize: 13, fontWeight: 600 }}>
                  {field.label}
                </label>
                <select
                  value={val || ""}
                  onChange={(e) => updatePayload(field.key, e.target.value)}
                  style={{
                    width: "100%",
                    padding: "8px 12px",
                    borderRadius: "var(--radius-sm)",
                    border: "1px solid var(--border-strong)",
                    background: "var(--surface)",
                    color: "var(--text)",
                    fontSize: 13.5,
                  }}
                >
                  <option value="" disabled>
                    Select {field.label}
                  </option>
                  {field.options.map((opt) => (
                    <option key={opt} value={opt}>
                      {opt}
                    </option>
                  ))}
                </select>
              </div>
            );
          }

          if (field.type === "json") {
            return (
              <div className="form-group" key={field.key} style={{ marginBottom: 16 }}>
                <label style={{ display: "block", marginBottom: 6, fontSize: 13, fontWeight: 600 }}>
                  {field.label}
                </label>
                <textarea
                  rows={4}
                  value={val ? JSON.stringify(val, null, 2) : ""}
                  onChange={(e) => {
                    try {
                      updatePayload(field.key, JSON.parse(e.target.value));
                    } catch {}
                  }}
                  style={{
                    width: "100%",
                    padding: "8px 12px",
                    borderRadius: "var(--radius-sm)",
                    border: "1px solid var(--border-strong)",
                    fontFamily: "ui-monospace, monospace",
                    fontSize: 12.5,
                  }}
                />
              </div>
            );
          }

          return (
            <div className="form-group" key={field.key} style={{ marginBottom: 16 }}>
              <label style={{ display: "block", marginBottom: 6, fontSize: 13, fontWeight: 600 }}>
                {field.label}
              </label>
              <input
                type={field.type === "number" ? "number" : "text"}
                value={val !== undefined ? val : ""}
                onChange={(e) =>
                  updatePayload(field.key, field.type === "number" ? Number(e.target.value) : e.target.value)
                }
                style={{
                  width: "100%",
                  padding: "8px 12px",
                  borderRadius: "var(--radius-sm)",
                  border: "1px solid var(--border-strong)",
                  background: "var(--surface)",
                  color: "var(--text)",
                  fontSize: 13.5,
                }}
              />
            </div>
          );
        })}
      </form>
    </Modal>
  );
}
