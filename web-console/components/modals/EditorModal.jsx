"use client";

import { useState, useCallback } from "react";
import Modal from "@/components/Modal";
import { useToast } from "@/components/Toast";
import { Layout, Code } from "lucide-react";

import ItemEditor from "./editors/ItemEditor";
import GeneratorEditor from "./editors/GeneratorEditor";
import UpgradeEditor from "./editors/UpgradeEditor";
import RecipeEditor from "./editors/RecipeEditor";
import LootTableEditor from "./editors/LootTableEditor";
import DungeonEditor from "./editors/DungeonEditor";
import WorldBossEditor from "./editors/WorldBossEditor";

const PROCESSING_STATIONS = [
  { id: "SMELTER", label: "Smelter" },
  { id: "PRESS", label: "Press" },
  { id: "REFINERY", label: "Refinery" },
];
import GenericEditor from "./editors/GenericEditor";

export default function EditorModal({ kind = "ItemBase", initialData, onClose }) {
  const isEditing = !!initialData;
  const toast = useToast();

  const [activeTab, setActiveTab] = useState("form"); // "form" | "json"
  const [definitionId, setDefinitionId] = useState(initialData?.id || "");
  const [payload, setPayload] = useState(initialData?.payload || {});
  const [rawJsonText, setRawJsonText] = useState(
    JSON.stringify(initialData?.payload || {}, null, 2)
  );
  const [jsonParseError, setJsonParseError] = useState(null);

  // Called whenever child editor updates payload
  const handlePayloadChange = useCallback((newPayload) => {
    setPayload(newPayload);
    setRawJsonText(JSON.stringify(newPayload, null, 2));
  }, []);

  const handleSwitchTab = (tab) => {
    if (tab === "json") {
      setRawJsonText(JSON.stringify(payload, null, 2));
      setJsonParseError(null);
    } else if (tab === "form") {
      try {
        const parsed = JSON.parse(rawJsonText);
        setJsonParseError(null);
        setPayload(parsed);
      } catch (err) {
        setJsonParseError("Invalid JSON syntax. Fix syntax before switching to Visual Form.");
        return;
      }
    }
    setActiveTab(tab);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (!definitionId.trim()) {
      toast.warning("Definition ID is required", "Missing ID");
      return;
    }

    let finalPayload = payload;
    if (activeTab === "json") {
      try {
        finalPayload = JSON.parse(rawJsonText);
      } catch (err) {
        toast.error("Invalid JSON syntax: " + err.message, "Validation Error");
        return;
      }
    }

    try {
      const res = await fetch(`/api/content/${kind}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          definition_id: definitionId.trim(),
          payload: finalPayload,
        }),
      });

      const data = await res.json();
      if (res.ok) {
        toast.success(`${kind} "${definitionId}" saved successfully`, "Changes Saved");
        onClose(true);
      } else {
        toast.error(data.error || "Failed to save content definition", "Save Error");
      }
    } catch (err) {
      toast.error(err.message, "Network Error");
    }
  };

  const renderSpecificEditor = () => {
    if (kind === "ItemBase" || kind === "MATERIAL" || kind === "ITEM") {
      return <ItemEditor payload={payload} onChange={handlePayloadChange} />;
    }
    if (kind === "PassiveGenerator") {
      return <GeneratorEditor payload={payload} onChange={handlePayloadChange} />;
    }
    if (kind === "UpgradeTree") {
      return <UpgradeEditor payload={payload} onChange={handlePayloadChange} />;
    }
    if (kind === "Recipe") {
      return <RecipeEditor payload={payload} onChange={handlePayloadChange} />;
    }
    if (kind === "ProcessingRecipe") {
      // Workspace machines (config workspace.stations); duration is real time and runs while offline.
      return <RecipeEditor payload={payload} onChange={handlePayloadChange} stations={PROCESSING_STATIONS} />;
    }
    if (kind === "LootTable") {
      return <LootTableEditor payload={payload} onChange={handlePayloadChange} />;
    }
    if (kind === "Dungeon") {
      return <DungeonEditor payload={payload} onChange={handlePayloadChange} />;
    }
    if (kind === "WorldBoss") {
      return <WorldBossEditor payload={payload} onChange={handlePayloadChange} />;
    }
    return <GenericEditor payload={payload} onChange={handlePayloadChange} />;
  };

  return (
    <Modal
      isOpen={true}
      onClose={() => onClose(false)}
      title={isEditing ? `Edit ${kind}` : `Create New ${kind}`}
      size="xl"
      footer={
        <div style={{ display: "flex", justifyContent: "space-between", width: "100%", alignItems: "center" }}>
          <div style={{ fontSize: 12, color: "var(--text-tertiary)" }}>
            Kind: <strong style={{ color: "var(--text)" }}>{kind}</strong> · Mode:{" "}
            <span style={{ textTransform: "capitalize" }}>{activeTab}</span>
          </div>
          <div style={{ display: "flex", gap: 10 }}>
            <button type="button" className="page-btn" onClick={() => onClose(false)}>
              Cancel
            </button>
            <button type="submit" form="editor-form" className="page-btn primary">
              Save {kind}
            </button>
          </div>
        </div>
      }
    >
      {/* Mode Switcher Tabs */}
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderBottom: "1px solid var(--border)",
          paddingBottom: 12,
          marginBottom: 20,
        }}
      >
        <div style={{ display: "flex", gap: 8 }}>
          <button
            type="button"
            onClick={() => handleSwitchTab("form")}
            className="page-btn"
            style={{
              padding: "6px 14px",
              fontSize: 13,
              fontWeight: activeTab === "form" ? 600 : 400,
              background: activeTab === "form" ? "var(--accent)" : "transparent",
              color: activeTab === "form" ? "#fff" : "var(--text-secondary)",
              border: activeTab === "form" ? "1px solid var(--accent)" : "1px solid var(--border)",
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
            }}
          >
            <Layout size={14} /> Visual Form
          </button>
          <button
            type="button"
            onClick={() => handleSwitchTab("json")}
            className="page-btn"
            style={{
              padding: "6px 14px",
              fontSize: 13,
              fontWeight: activeTab === "json" ? 600 : 400,
              background: activeTab === "json" ? "var(--accent)" : "transparent",
              color: activeTab === "json" ? "#fff" : "var(--text-secondary)",
              border: activeTab === "json" ? "1px solid var(--accent)" : "1px solid var(--border)",
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
            }}
          >
            <Code size={14} /> Raw JSON
          </button>
        </div>

        <span style={{ fontSize: 12, color: "var(--text-tertiary)" }}>
          Real-time sync between Form and JSON
        </span>
      </div>

      <form id="editor-form" onSubmit={handleSubmit}>
        {/* Definition ID Field */}
        <div
          style={{
            background: "var(--surface-muted)",
            border: "1px solid var(--border)",
            borderRadius: "var(--radius-md)",
            padding: 16,
            marginBottom: 20,
          }}
        >
          <label
            style={{
              display: "block",
              marginBottom: 6,
              fontSize: 12,
              fontWeight: 700,
              textTransform: "uppercase",
              color: "var(--text-secondary)",
            }}
          >
            Definition Identifier (ID) *
          </label>
          <input
            type="text"
            value={definitionId}
            onChange={(e) => setDefinitionId(e.target.value.toLowerCase().replace(/\s+/g, "_"))}
            disabled={isEditing}
            required
            placeholder="e.g. netherite_pickaxe, solar_panel_t1"
            style={{
              width: "100%",
              padding: "9px 12px",
              borderRadius: "var(--radius-sm)",
              border: "1px solid var(--border-strong)",
              background: isEditing ? "var(--surface-muted)" : "var(--surface)",
              color: "var(--text)",
              fontFamily: "ui-monospace, monospace",
              fontSize: 13.5,
              fontWeight: 600,
            }}
          />
          <span style={{ fontSize: 11.5, color: "var(--text-tertiary)", marginTop: 4, display: "block" }}>
            Unique database key (snake_case format recommended).
          </span>
        </div>

        {/* RAW JSON VIEW */}
        {activeTab === "json" ? (
          <div>
            {jsonParseError && (
              <div
                style={{
                  padding: "10px 14px",
                  borderRadius: "var(--radius-sm)",
                  background: "rgba(239, 68, 68, 0.1)",
                  border: "1px solid rgba(239, 68, 68, 0.25)",
                  color: "var(--danger)",
                  fontSize: 13,
                  marginBottom: 12,
                }}
              >
                {jsonParseError}
              </div>
            )}
            <label
              style={{
                display: "block",
                marginBottom: 6,
                fontSize: 12,
                fontWeight: 700,
                textTransform: "uppercase",
                color: "var(--text-secondary)",
              }}
            >
              Payload Object (JSON)
            </label>
            <textarea
              rows={16}
              value={rawJsonText}
              onChange={(e) => {
                setRawJsonText(e.target.value);
                try {
                  const p = JSON.parse(e.target.value);
                  setJsonParseError(null);
                  setPayload(p);
                } catch (err) {
                  setJsonParseError(err.message);
                }
              }}
              style={{
                width: "100%",
                padding: "12px",
                borderRadius: "var(--radius-sm)",
                border: "1px solid var(--border-strong)",
                background: "var(--surface)",
                color: "var(--text)",
                fontFamily: "ui-monospace, monospace",
                fontSize: 13,
                lineHeight: 1.5,
              }}
            />
          </div>
        ) : (
          /* DELEGATED MODULAR FORM */
          renderSpecificEditor()
        )}
      </form>
    </Modal>
  );
}
