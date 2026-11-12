"use client";

import { useState } from "react";
import {
  Castle,
  Plus,
  Trash2,
  ShieldAlert,
  Award,
  PlayCircle,
  Repeat,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  KeyRound,
} from "lucide-react";
import ItemSelect from "@/components/ItemSelect";

const VANILLA_ENTITIES = [
  { id: "ZOMBIE", label: "Zombie" },
  { id: "SKELETON", label: "Skeleton" },
  { id: "SPIDER", label: "Spider" },
  { id: "CREEPER", label: "Creeper" },
  { id: "WITHER_SKELETON", label: "Wither Skeleton" },
  { id: "WARDEN", label: "Warden" },
  { id: "BLAZE", label: "Blaze" },
  { id: "ENDERMAN", label: "Enderman" },
  { id: "IRON_GOLEM", label: "Iron Golem" },
  { id: "WITCH", label: "Witch" },
  { id: "VINDICATOR", label: "Vindicator" },
  { id: "EVOKER", label: "Evoker" },
  { id: "PILLAGER", label: "Pillager" },
  { id: "RAVAGER", label: "Ravager" },
  { id: "DROWNED", label: "Drowned" },
  { id: "HUSK", label: "Husk" },
  { id: "STRAY", label: "Stray" },
  { id: "PHANTOM", label: "Phantom" },
  { id: "SLIME", label: "Slime" },
  { id: "MAGMA_CUBE", label: "Magma Cube" },
  { id: "GHAST", label: "Ghast" },
  { id: "PIGLIN", label: "Piglin" },
  { id: "PIGLIN_BRUTE", label: "Piglin Brute" },
  { id: "ZOMBIFIED_PIGLIN", label: "Zombified Piglin" },
];

export default function DungeonEditor({ payload = {}, onChange }) {
  const [expandedStep, setExpandedStep] = useState(0);

  const [form, setForm] = useState({
    display_name: payload.display_name || payload.name || "",
    description: payload.description || "",
    template_map: payload.template_map || "ind_t0",
    required_level: payload.required_level ?? 1,
    time_limit_seconds: payload.time_limit_seconds ?? 900,
    max_players: payload.max_players ?? 4,
    // 6.2: what the party leader pays to open a run. Item keys and/or COIN / ENERGY.
    entry_cost: Array.isArray(payload.entry_cost)
      ? payload.entry_cost
      : payload.entry_cost && typeof payload.entry_cost === "object"
        ? [payload.entry_cost]
        : [],
    steps: Array.isArray(payload.steps) && payload.steps.length > 0
      ? payload.steps.map((s, idx) => ({
          step_id: s.step_id || `step_${idx + 1}`,
          name: s.name || `Step ${idx + 1}`,
          goal: s.goal || {
            type: "KILL_MOBS",
            target_mob_id: "void_crawler",
            target_count: 5,
            display_text: "Slay Monsters",
          },
          on_start: Array.isArray(s.on_start) ? s.on_start : [],
          on_current: s.on_current || null,
          on_end: Array.isArray(s.on_end) ? s.on_end : [],
        }))
      : [
          {
            step_id: "step_1",
            name: "Clear Outer Gate",
            goal: {
              type: "KILL_MOBS",
              target_mob_id: "void_crawler",
              target_count: 5,
              display_text: "Slay Monsters",
            },
            on_start: [
              {
                action: "SUMMON_GROUP",
                mob_id: "void_crawler",
                vanilla_type: "ZOMBIE",
                center: { x: 0.5, y: 64.0, z: 0.5 },
                radius: 6.0,
                amount: 5,
              },
            ],
            on_current: {
              interval_seconds: 20,
              actions: [
                {
                  action: "SUMMON_MOB",
                  mob_id: "void_crawler",
                  vanilla_type: "ZOMBIE",
                  x: 0.5,
                  y: 64.0,
                  z: 0.5,
                  amount: 1,
                },
              ],
            },
            on_end: [
              {
                action: "BROADCAST_MESSAGE",
                message: "§aStep 1 complete!",
              },
            ],
          },
        ],
    final_rewards: payload.final_rewards || {
      guaranteed: [{ item: "prim_metal", qty: 2 }],
      roll_table: {
        rolls: 1,
        drops: [{ item: "iron_ingot", weight: 70, min_qty: 1, max_qty: 3 }],
      },
    },
  });

  const notifyParent = (updated) => {
    onChange({
      display_name: updated.display_name,
      description: updated.description,
      template_map: updated.template_map,
      required_level: Number(updated.required_level) || 1,
      time_limit_seconds: Number(updated.time_limit_seconds) || 900,
      max_players: Number(updated.max_players) || 4,
      entry_cost: updated.entry_cost,
      steps: updated.steps,
      final_rewards: updated.final_rewards,
    });
  };

  const updateField = (key, value) => {
    const updated = { ...form, [key]: value };
    setForm(updated);
    notifyParent(updated);
  };

  const addStep = () => {
    const newIdx = form.steps.length + 1;
    const newSteps = [
      ...form.steps,
      {
        step_id: `step_${newIdx}`,
        name: `Step ${newIdx}`,
        goal: {
          type: "KILL_MOBS",
          target_mob_id: "crawler",
          target_count: 5,
          display_text: "Slay Monsters",
        },
        on_start: [],
        on_current: null,
        on_end: [],
      },
    ];
    updateField("steps", newSteps);
    setExpandedStep(newSteps.length - 1);
  };

  const removeStep = (idx) => {
    const newSteps = form.steps.filter((_, i) => i !== idx);
    updateField("steps", newSteps);
    if (expandedStep >= newSteps.length) setExpandedStep(Math.max(0, newSteps.length - 1));
  };

  const updateStep = (idx, updater) => {
    const newSteps = [...form.steps];
    newSteps[idx] = updater(newSteps[idx]);
    updateField("steps", newSteps);
  };

  // Action Helpers for OnStart / OnCurrent / OnEnd
  const addAction = (stepIdx, phase) => {
    updateStep(stepIdx, (step) => {
      const defaultAction = {
        action: "SUMMON_GROUP",
        mob_id: "void_crawler",
        vanilla_type: "ZOMBIE",
        center: { x: 0, y: 64, z: 0 },
        radius: 5,
        amount: 3,
      };

      if (phase === "on_start") {
        return { ...step, on_start: [...(step.on_start || []), defaultAction] };
      }
      if (phase === "on_end") {
        return {
          ...step,
          on_end: [
            ...(step.on_end || []),
            { action: "BROADCAST_MESSAGE", message: "§aStep cleared!" },
          ],
        };
      }
      if (phase === "on_current") {
        const cur = step.on_current || { interval_seconds: 15, actions: [] };
        return {
          ...step,
          on_current: {
            ...cur,
            actions: [...cur.actions, defaultAction],
          },
        };
      }
      return step;
    });
  };

  const removeAction = (stepIdx, phase, actionIdx) => {
    updateStep(stepIdx, (step) => {
      if (phase === "on_start") {
        return { ...step, on_start: step.on_start.filter((_, i) => i !== actionIdx) };
      }
      if (phase === "on_end") {
        return { ...step, on_end: step.on_end.filter((_, i) => i !== actionIdx) };
      }
      if (phase === "on_current") {
        const newActions = step.on_current.actions.filter((_, i) => i !== actionIdx);
        return {
          ...step,
          on_current: newActions.length > 0 ? { ...step.on_current, actions: newActions } : null,
        };
      }
      return step;
    });
  };

  const updateAction = (stepIdx, phase, actionIdx, key, val) => {
    updateStep(stepIdx, (step) => {
      if (phase === "on_start") {
        const acts = [...step.on_start];
        acts[actionIdx] = { ...acts[actionIdx], [key]: val };
        return { ...step, on_start: acts };
      }
      if (phase === "on_end") {
        const acts = [...step.on_end];
        acts[actionIdx] = { ...acts[actionIdx], [key]: val };
        return { ...step, on_end: acts };
      }
      if (phase === "on_current") {
        const acts = [...step.on_current.actions];
        acts[actionIdx] = { ...acts[actionIdx], [key]: val };
        return { ...step, on_current: { ...step.on_current, actions: acts } };
      }
      return step;
    });
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      {/* General Settings */}
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Castle size={16} /> Dungeon Configuration
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "2fr 1fr 1fr 1fr", gap: 12 }}>
          <div className="form-group">
            <label>Dungeon Name</label>
            <input
              type="text"
              value={form.display_name}
              onChange={(e) => updateField("display_name", e.target.value)}
              placeholder="e.g. Ancient Void Ruins"
            />
          </div>
          <div className="form-group">
            <label>Template Map</label>
            <input
              type="text"
              value={form.template_map}
              onChange={(e) => updateField("template_map", e.target.value)}
              placeholder="e.g. ind_t0"
            />
          </div>
          <div className="form-group">
            <label>Level Req.</label>
            <input
              type="number"
              min="1"
              value={form.required_level}
              onChange={(e) => updateField("required_level", e.target.value)}
            />
          </div>
          <div className="form-group">
            <label>Max Players</label>
            <input
              type="number"
              min="1"
              max="8"
              value={form.max_players}
              onChange={(e) => updateField("max_players", e.target.value)}
            />
          </div>
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "3fr 1fr", gap: 12, marginTop: 12 }}>
          <div className="form-group">
            <label>Description</label>
            <input
              type="text"
              value={form.description}
              onChange={(e) => updateField("description", e.target.value)}
              placeholder="A dangerous dungeon deep underground..."
            />
          </div>
          <div className="form-group">
            <label>Time Limit (Sec)</label>
            <input
              type="number"
              min="60"
              step="30"
              value={form.time_limit_seconds}
              onChange={(e) => updateField("time_limit_seconds", e.target.value)}
            />
          </div>
        </div>
      </div>

      {/* Entry Cost (keys) */}
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 6px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <KeyRound size={16} /> Entry Cost ({form.entry_cost.length})
        </h4>
        <p style={{ margin: "0 0 12px", fontSize: 12, color: "var(--text-secondary)" }}>
          Paid by the party leader when the run opens. Refunded only if the instance fails to load or the server
          stops before the run starts. Empty = free entry.
        </p>
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {form.entry_cost.map((c, cIdx) => {
            const isItem = c.currency === undefined;
            const setCost = (next) => {
              const list = [...form.entry_cost];
              list[cIdx] = next;
              updateField("entry_cost", list);
            };
            return (
              <div key={cIdx} style={{ display: "grid", gridTemplateColumns: "1fr 3fr 1fr 40px", gap: 8, alignItems: "center" }}>
                <select
                  value={isItem ? "item" : "currency"}
                  onChange={(e) =>
                    setCost(e.target.value === "item" ? { item: "", qty: 1 } : { currency: "COIN", amount: 100 })
                  }
                >
                  <option value="item">Key item</option>
                  <option value="currency">Currency</option>
                </select>
                {isItem ? (
                  <ItemSelect value={c.item || ""} onChange={(val) => setCost({ ...c, item: val })} />
                ) : (
                  <select value={c.currency} onChange={(e) => setCost({ ...c, currency: e.target.value })}>
                    <option value="COIN">COIN</option>
                    <option value="ENERGY">ENERGY</option>
                  </select>
                )}
                <input
                  type="number"
                  min="1"
                  value={isItem ? c.qty || 1 : c.amount || 1}
                  onChange={(e) => {
                    const n = Math.max(1, Number(e.target.value) || 1);
                    setCost(isItem ? { ...c, qty: n } : { ...c, amount: n });
                  }}
                />
                <button
                  type="button"
                  style={{ background: "transparent", border: "none", color: "var(--text-tertiary)", cursor: "pointer" }}
                  onClick={() => updateField("entry_cost", form.entry_cost.filter((_, i) => i !== cIdx))}
                >
                  <Trash2 size={16} />
                </button>
              </div>
            );
          })}
          <button
            type="button"
            className="btn btn-sm btn-outline"
            style={{ alignSelf: "flex-start", marginTop: 6 }}
            onClick={() => updateField("entry_cost", [...form.entry_cost, { item: "", qty: 1 }])}
          >
            <Plus size={14} /> Add Cost
          </button>
        </div>
      </div>

      {/* Steps Progression */}
      <div className="card" style={{ padding: 18 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14 }}>
          <h4 style={{ margin: 0, fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
            <ShieldAlert size={16} /> Progression Steps ({form.steps.length})
          </h4>
          <button
            type="button"
            className="btn btn-sm btn-outline"
            onClick={addStep}
            style={{ display: "flex", alignItems: "center", gap: 4 }}
          >
            <Plus size={14} /> Add Step
          </button>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          {form.steps.map((step, idx) => {
            const isExpanded = expandedStep === idx;
            return (
              <div
                key={idx}
                style={{
                  background: "var(--surface-muted)",
                  border: isExpanded ? "1px solid var(--accent)" : "1px solid var(--border)",
                  borderRadius: 8,
                  overflow: "hidden",
                }}
              >
                {/* Step Header */}
                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    padding: "12px 14px",
                    background: isExpanded ? "var(--accent-soft)" : "var(--surface)",
                    cursor: "pointer",
                  }}
                  onClick={() => setExpandedStep(isExpanded ? -1 : idx)}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                    <span style={{ fontWeight: 600, fontSize: 14, color: "var(--accent)" }}>
                      Step {idx + 1}:
                    </span>
                    <span style={{ fontWeight: 500, fontSize: 13, color: "var(--text)" }}>
                      {step.name}
                    </span>
                    <span
                      style={{
                        fontSize: 11,
                        background: "rgba(255,255,255,0.06)",
                        padding: "2px 8px",
                        borderRadius: 12,
                        color: "var(--text-secondary)",
                      }}
                    >
                      Goal: {step.goal?.type}
                    </span>
                  </div>

                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    {form.steps.length > 1 && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          removeStep(idx);
                        }}
                        style={{
                          background: "transparent",
                          border: "none",
                          color: "var(--text-tertiary)",
                          cursor: "pointer",
                          padding: 4,
                        }}
                      >
                        <Trash2 size={14} />
                      </button>
                    )}
                    {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                  </div>
                </div>

                {/* Step Body */}
                {isExpanded && (
                  <div style={{ padding: 16, display: "flex", flexDirection: "column", gap: 16 }}>
                    {/* Basic Step Settings & Goal */}
                    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 10 }}>
                      <div className="form-group">
                        <label>Step Name</label>
                        <input
                          type="text"
                          value={step.name}
                          onChange={(e) =>
                            updateStep(idx, (s) => ({ ...s, name: e.target.value }))
                          }
                        />
                      </div>
                      <div className="form-group">
                        <label>Goal Type</label>
                        <select
                          value={step.goal?.type || "KILL_MOBS"}
                          onChange={(e) => {
                            const newType = e.target.value;
                            updateStep(idx, (s) => {
                              const curGoal = s.goal || {};
                              let goalDefaults = {};
                              if (newType === "KILL_MOBS") {
                                goalDefaults = {
                                  target_mob_id: curGoal.target_mob_id || "void_crawler",
                                  target_count: curGoal.target_count || 5,
                                };
                              } else if (newType === "KILL_BOSS") {
                                goalDefaults = {
                                  boss_id: curGoal.boss_id || "void_overseer_boss",
                                };
                              } else if (newType === "HOLD_ZONE") {
                                goalDefaults = {
                                  center: curGoal.center || { x: 0, y: 64, z: 0 },
                                  radius: curGoal.radius ?? 5,
                                  required_hold_seconds: curGoal.required_hold_seconds ?? 20,
                                };
                              } else if (newType === "INTERACT_DUMMY") {
                                goalDefaults = {
                                  dummy_id: curGoal.dummy_id || "crystal_switch_1",
                                  duration_seconds: curGoal.duration_seconds ?? 0,
                                };
                              } else if (newType === "SURVIVE_TIME") {
                                goalDefaults = {
                                  duration_seconds: curGoal.duration_seconds ?? 60,
                                };
                              }
                              return {
                                ...s,
                                goal: {
                                  ...curGoal,
                                  ...goalDefaults,
                                  type: newType,
                                },
                              };
                            });
                          }}
                        >
                          <option value="KILL_MOBS">Kill Mobs</option>
                          <option value="KILL_BOSS">Kill Boss</option>
                          <option value="HOLD_ZONE">Hold Zone (Capture Point)</option>
                          <option value="INTERACT_DUMMY">Interact Dummy (Switch / NPC)</option>
                          <option value="SURVIVE_TIME">Survive Time (Wave Survival)</option>
                        </select>
                      </div>
                      <div className="form-group">
                        <label>Display Text</label>
                        <input
                          type="text"
                          value={step.goal?.display_text || ""}
                          onChange={(e) =>
                            updateStep(idx, (s) => ({
                              ...s,
                              goal: { ...s.goal, display_text: e.target.value },
                            }))
                          }
                          placeholder="e.g. Slay 5 Crawlers"
                        />
                      </div>
                    </div>

                    {/* Goal Specific Inputs */}
                    {step.goal?.type === "KILL_MOBS" && (
                      <div style={{ display: "grid", gridTemplateColumns: "2fr 1fr", gap: 10 }}>
                        <div className="form-group">
                          <label>Target Mob ID / Tag</label>
                          <input
                            type="text"
                            value={step.goal?.target_mob_id || ""}
                            onChange={(e) =>
                              updateStep(idx, (s) => ({
                                ...s,
                                goal: { ...s.goal, target_mob_id: e.target.value },
                              }))
                            }
                            placeholder="e.g. void_crawler"
                          />
                        </div>
                        <div className="form-group">
                          <label>Target Kill Count</label>
                          <input
                            type="number"
                            min="1"
                            value={step.goal?.target_count || 1}
                            onChange={(e) =>
                              updateStep(idx, (s) => ({
                                ...s,
                                goal: { ...s.goal, target_count: Number(e.target.value) || 1 },
                              }))
                            }
                          />
                        </div>
                      </div>
                    )}

                    {step.goal?.type === "KILL_BOSS" && (
                      <div className="form-group">
                        <label>Target Boss ID / Tag</label>
                        <input
                          type="text"
                          value={step.goal?.boss_id || ""}
                          onChange={(e) =>
                            updateStep(idx, (s) => ({
                              ...s,
                              goal: { ...s.goal, boss_id: e.target.value },
                            }))
                          }
                          placeholder="e.g. void_overseer_boss"
                        />
                      </div>
                    )}

                    {step.goal?.type === "HOLD_ZONE" && (
                      <div style={{ display: "flex", flexDirection: "column", gap: 10, background: "rgba(255,255,255,0.02)", padding: 12, borderRadius: 6, border: "1px dashed var(--border)" }}>
                        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr 1.5fr", gap: 8 }}>
                          <div className="form-group" style={{ margin: 0 }}>
                            <label>Center X</label>
                            <input
                              type="number"
                              step="0.5"
                              value={step.goal?.center?.x ?? 0}
                              onChange={(e) =>
                                updateStep(idx, (s) => ({
                                  ...s,
                                  goal: { ...s.goal, center: { ...(s.goal.center || {}), x: Number(e.target.value) || 0 } },
                                }))
                              }
                            />
                          </div>
                          <div className="form-group" style={{ margin: 0 }}>
                            <label>Center Y</label>
                            <input
                              type="number"
                              step="0.5"
                              value={step.goal?.center?.y ?? 64}
                              onChange={(e) =>
                                updateStep(idx, (s) => ({
                                  ...s,
                                  goal: { ...s.goal, center: { ...(s.goal.center || {}), y: Number(e.target.value) || 64 } },
                                }))
                              }
                            />
                          </div>
                          <div className="form-group" style={{ margin: 0 }}>
                            <label>Center Z</label>
                            <input
                              type="number"
                              step="0.5"
                              value={step.goal?.center?.z ?? 0}
                              onChange={(e) =>
                                updateStep(idx, (s) => ({
                                  ...s,
                                  goal: { ...s.goal, center: { ...(s.goal.center || {}), z: Number(e.target.value) || 0 } },
                                }))
                              }
                            />
                          </div>
                          <div className="form-group" style={{ margin: 0 }}>
                            <label>Radius (Blocks)</label>
                            <input
                              type="number"
                              min="1"
                              step="0.5"
                              value={step.goal?.radius ?? 5}
                              onChange={(e) =>
                                updateStep(idx, (s) => ({
                                  ...s,
                                  goal: { ...s.goal, radius: Number(e.target.value) || 5 },
                                }))
                              }
                            />
                          </div>
                        </div>
                        <div className="form-group" style={{ margin: 0 }}>
                          <label>Required Hold Duration (Seconds)</label>
                          <input
                            type="number"
                            min="1"
                            value={step.goal?.required_hold_seconds ?? 20}
                            onChange={(e) =>
                              updateStep(idx, (s) => ({
                                ...s,
                                goal: { ...s.goal, required_hold_seconds: Number(e.target.value) || 20 },
                              }))
                            }
                          />
                        </div>
                      </div>
                    )}

                    {step.goal?.type === "INTERACT_DUMMY" && (
                      <div style={{ display: "grid", gridTemplateColumns: "2fr 1fr", gap: 10, background: "rgba(255,255,255,0.02)", padding: 12, borderRadius: 6, border: "1px dashed var(--border)" }}>
                        <div className="form-group" style={{ margin: 0 }}>
                          <label>Dummy Target ID</label>
                          <input
                            type="text"
                            value={step.goal?.dummy_id || ""}
                            onChange={(e) =>
                              updateStep(idx, (s) => ({
                                ...s,
                                goal: { ...s.goal, dummy_id: e.target.value },
                              }))
                            }
                            placeholder="e.g. crystal_switch_1"
                          />
                        </div>
                        <div className="form-group" style={{ margin: 0 }}>
                          <label>Hold / Channel (Seconds)</label>
                          <input
                            type="number"
                            min="0"
                            value={step.goal?.duration_seconds ?? 0}
                            onChange={(e) =>
                              updateStep(idx, (s) => ({
                                ...s,
                                goal: { ...s.goal, duration_seconds: Number(e.target.value) || 0 },
                              }))
                            }
                            placeholder="0 = Instant click"
                          />
                        </div>
                      </div>
                    )}

                    {step.goal?.type === "SURVIVE_TIME" && (
                      <div className="form-group" style={{ background: "rgba(255,255,255,0.02)", padding: 12, borderRadius: 6, border: "1px dashed var(--border)" }}>
                        <label>Survive Duration (Seconds)</label>
                        <input
                          type="number"
                          min="5"
                          step="5"
                          value={step.goal?.duration_seconds ?? 60}
                          onChange={(e) =>
                            updateStep(idx, (s) => ({
                              ...s,
                              goal: { ...s.goal, duration_seconds: Number(e.target.value) || 60 },
                            }))
                          }
                          placeholder="e.g. 60"
                        />
                      </div>
                    )}

                    {/* =================================================== */}
                    {/* EVENT: OnStart Actions                             */}
                    {/* =================================================== */}
                    <div
                      style={{
                        border: "1px solid rgba(255,255,255,0.08)",
                        borderRadius: 6,
                        padding: 12,
                        background: "rgba(0,0,0,0.1)",
                      }}
                    >
                      <div
                        style={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          marginBottom: 8,
                        }}
                      >
                        <span
                          style={{
                            fontWeight: 600,
                            fontSize: 12,
                            display: "flex",
                            alignItems: "center",
                            gap: 6,
                            color: "var(--accent)",
                          }}
                        >
                          <PlayCircle size={14} /> OnStart Events ({step.on_start?.length || 0})
                        </span>
                        <button
                          type="button"
                          className="btn btn-xs btn-outline"
                          onClick={() => addAction(idx, "on_start")}
                          style={{ fontSize: 11, padding: "2px 8px" }}
                        >
                          + Add Action
                        </button>
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                        {(step.on_start || []).map((act, aIdx) => (
                          <ActionItemRow
                            key={aIdx}
                            act={act}
                            onUpdate={(k, v) => updateAction(idx, "on_start", aIdx, k, v)}
                            onDelete={() => removeAction(idx, "on_start", aIdx)}
                          />
                        ))}
                        {(!step.on_start || step.on_start.length === 0) && (
                          <div style={{ fontSize: 11, color: "var(--text-tertiary)", fontStyle: "italic" }}>
                            No actions configured for OnStart.
                          </div>
                        )}
                      </div>
                    </div>

                    {/* =================================================== */}
                    {/* EVENT: OnCurrent (Interval Loop Actions)           */}
                    {/* =================================================== */}
                    <div
                      style={{
                        border: "1px solid rgba(255,255,255,0.08)",
                        borderRadius: 6,
                        padding: 12,
                        background: "rgba(0,0,0,0.1)",
                      }}
                    >
                      <div
                        style={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          marginBottom: 8,
                        }}
                      >
                        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                          <span
                            style={{
                              fontWeight: 600,
                              fontSize: 12,
                              display: "flex",
                              alignItems: "center",
                              gap: 6,
                              color: "#f59e0b",
                            }}
                          >
                            <Repeat size={14} /> OnCurrent Loop (Continuous)
                          </span>
                          {step.on_current && (
                            <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
                              <span style={{ fontSize: 11, color: "var(--text-secondary)" }}>
                                Every:
                              </span>
                              <input
                                type="number"
                                min="5"
                                style={{ width: 50, padding: "2px 4px", fontSize: 11 }}
                                value={step.on_current?.interval_seconds || 15}
                                onChange={(e) =>
                                  updateStep(idx, (s) => ({
                                    ...s,
                                    on_current: {
                                      ...s.on_current,
                                      interval_seconds: Number(e.target.value) || 15,
                                    },
                                  }))
                                }
                              />
                              <span style={{ fontSize: 11, color: "var(--text-secondary)" }}>
                                sec
                              </span>
                            </div>
                          )}
                        </div>
                        <button
                          type="button"
                          className="btn btn-xs btn-outline"
                          onClick={() => addAction(idx, "on_current")}
                          style={{ fontSize: 11, padding: "2px 8px" }}
                        >
                          + Add Loop Action
                        </button>
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                        {(step.on_current?.actions || []).map((act, aIdx) => (
                          <ActionItemRow
                            key={aIdx}
                            act={act}
                            onUpdate={(k, v) => updateAction(idx, "on_current", aIdx, k, v)}
                            onDelete={() => removeAction(idx, "on_current", aIdx)}
                          />
                        ))}
                        {(!step.on_current || step.on_current.actions?.length === 0) && (
                          <div style={{ fontSize: 11, color: "var(--text-tertiary)", fontStyle: "italic" }}>
                            No repeating actions configured.
                          </div>
                        )}
                      </div>
                    </div>

                    {/* =================================================== */}
                    {/* EVENT: OnEnd Actions                               */}
                    {/* =================================================== */}
                    <div
                      style={{
                        border: "1px solid rgba(255,255,255,0.08)",
                        borderRadius: 6,
                        padding: 12,
                        background: "rgba(0,0,0,0.1)",
                      }}
                    >
                      <div
                        style={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          marginBottom: 8,
                        }}
                      >
                        <span
                          style={{
                            fontWeight: 600,
                            fontSize: 12,
                            display: "flex",
                            alignItems: "center",
                            gap: 6,
                            color: "#10b981",
                          }}
                        >
                          <CheckCircle2 size={14} /> OnEnd Events ({step.on_end?.length || 0})
                        </span>
                        <button
                          type="button"
                          className="btn btn-xs btn-outline"
                          onClick={() => addAction(idx, "on_end")}
                          style={{ fontSize: 11, padding: "2px 8px" }}
                        >
                          + Add Action
                        </button>
                      </div>

                      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                        {(step.on_end || []).map((act, aIdx) => (
                          <ActionItemRow
                            key={aIdx}
                            act={act}
                            onUpdate={(k, v) => updateAction(idx, "on_end", aIdx, k, v)}
                            onDelete={() => removeAction(idx, "on_end", aIdx)}
                          />
                        ))}
                        {(!step.on_end || step.on_end.length === 0) && (
                          <div style={{ fontSize: 11, color: "var(--text-tertiary)", fontStyle: "italic" }}>
                            No actions configured for OnEnd.
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Final Guaranteed Rewards */}
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Award size={16} /> Final Guaranteed Rewards
        </h4>
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {(form.final_rewards?.guaranteed || []).map((rw, rIdx) => (
            <div key={rIdx} style={{ display: "grid", gridTemplateColumns: "3fr 1fr 40px", gap: 8, alignItems: "center" }}>
              <ItemSelect
                value={rw.item || ""}
                onChange={(val) => {
                  const newGuaranteed = [...(form.final_rewards?.guaranteed || [])];
                  newGuaranteed[rIdx] = { ...newGuaranteed[rIdx], item: val };
                  updateField("final_rewards", { ...form.final_rewards, guaranteed: newGuaranteed });
                }}
              />
              <input
                type="number"
                min="1"
                value={rw.qty || 1}
                onChange={(e) => {
                  const newGuaranteed = [...(form.final_rewards?.guaranteed || [])];
                  newGuaranteed[rIdx] = { ...newGuaranteed[rIdx], qty: Number(e.target.value) || 1 };
                  updateField("final_rewards", { ...form.final_rewards, guaranteed: newGuaranteed });
                }}
              />
              <button
                type="button"
                style={{ background: "transparent", border: "none", color: "var(--text-tertiary)", cursor: "pointer" }}
                onClick={() => {
                  const newGuaranteed = form.final_rewards.guaranteed.filter((_, i) => i !== rIdx);
                  updateField("final_rewards", { ...form.final_rewards, guaranteed: newGuaranteed });
                }}
              >
                <Trash2 size={16} />
              </button>
            </div>
          ))}
          <button
            type="button"
            className="btn btn-sm btn-outline"
            style={{ alignSelf: "flex-start", marginTop: 6 }}
            onClick={() => {
              const newGuaranteed = [...(form.final_rewards?.guaranteed || []), { item: "prim_metal", qty: 1 }];
              updateField("final_rewards", { ...form.final_rewards, guaranteed: newGuaranteed });
            }}
          >
            <Plus size={14} /> Add Guaranteed Item
          </button>
        </div>
      </div>
    </div>
  );
}

function ActionItemRow({ act, onUpdate, onDelete }) {
  const actionType = act.action || "SUMMON_GROUP";

  return (
    <div
      style={{
        background: "rgba(255,255,255,0.03)",
        border: "1px solid var(--border)",
        borderRadius: 4,
        padding: 8,
        display: "flex",
        flexDirection: "column",
        gap: 6,
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <select
            value={actionType}
            onChange={(e) => onUpdate("action", e.target.value)}
            style={{ width: 170, fontSize: 11, padding: "3px 6px" }}
          >
            <option value="SUMMON_GROUP">Summon Group</option>
            <option value="SUMMON_MOB">Summon Single Mob</option>
            <option value="SUMMON_BOSS">Summon Boss</option>
            <option value="SPAWN_DUMMY">Spawn Dummy/Switch</option>
            <option value="DESPAWN_DUMMY">Despawn Dummy</option>
            <option value="BROADCAST_TITLE">Broadcast Title</option>
            <option value="BROADCAST_MESSAGE">Broadcast Chat</option>
            <option value="GIVE_BONUS">Give Bonus</option>
          </select>

          <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
            <span style={{ fontSize: 10, color: "var(--text-tertiary)" }}>Delay:</span>
            <input
              type="number"
              min="0"
              step="5"
              title="Execution delay in Minecraft ticks (20 ticks = 1 second)"
              style={{ width: 55, fontSize: 11, padding: "2px 4px" }}
              value={act.delay_ticks ?? 0}
              onChange={(e) => onUpdate("delay_ticks", Number(e.target.value) || 0)}
            />
            <span style={{ fontSize: 10, color: "var(--text-tertiary)" }}>ticks</span>
          </div>
        </div>

        <button
          type="button"
          onClick={onDelete}
          style={{ background: "transparent", border: "none", color: "var(--text-tertiary)", cursor: "pointer" }}
        >
          <Trash2 size={13} />
        </button>
      </div>

      {/* Inputs based on Action Type */}
      {actionType === "SUMMON_GROUP" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1.5fr 1.5fr 1fr", gap: 6 }}>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Vanilla Entity</label>
              <select
                value={act.vanilla_type || "ZOMBIE"}
                onChange={(e) => onUpdate("vanilla_type", e.target.value)}
                style={{ fontSize: 11, padding: "2px 6px" }}
              >
                {VANILLA_ENTITIES.map((ent) => (
                  <option key={ent.id} value={ent.id}>
                    {ent.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Mob ID / Tag</label>
              <input
                type="text"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.mob_id || ""}
                onChange={(e) => onUpdate("mob_id", e.target.value)}
                placeholder="e.g. void_crawler"
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Amount</label>
              <input
                type="number"
                min="1"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.amount || 3}
                onChange={(e) => onUpdate("amount", Number(e.target.value) || 1)}
              />
            </div>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr 1.2fr", gap: 6, background: "rgba(255,255,255,0.02)", padding: 6, borderRadius: 4 }}>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Center X</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.center?.x ?? 0}
                onChange={(e) => onUpdate("center", { ...(act.center || {}), x: Number(e.target.value) || 0 })}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Center Y</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.center?.y ?? 64}
                onChange={(e) => onUpdate("center", { ...(act.center || {}), y: Number(e.target.value) || 64 })}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Center Z</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.center?.z ?? 0}
                onChange={(e) => onUpdate("center", { ...(act.center || {}), z: Number(e.target.value) || 0 })}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Radius (r)</label>
              <input
                type="number"
                min="1"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.radius ?? 5}
                onChange={(e) => onUpdate("radius", Number(e.target.value) || 5)}
              />
            </div>
          </div>
        </div>
      )}

      {actionType === "SUMMON_MOB" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1.5fr 1.5fr 1fr", gap: 6 }}>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Vanilla Entity</label>
              <select
                value={act.vanilla_type || "ZOMBIE"}
                onChange={(e) => onUpdate("vanilla_type", e.target.value)}
                style={{ fontSize: 11, padding: "2px 6px" }}
              >
                {VANILLA_ENTITIES.map((ent) => (
                  <option key={ent.id} value={ent.id}>
                    {ent.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Mob ID / Tag</label>
              <input
                type="text"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.mob_id || ""}
                onChange={(e) => onUpdate("mob_id", e.target.value)}
                placeholder="e.g. void_crawler"
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Amount</label>
              <input
                type="number"
                min="1"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.amount || 1}
                onChange={(e) => onUpdate("amount", Number(e.target.value) || 1)}
              />
            </div>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 6, background: "rgba(255,255,255,0.02)", padding: 6, borderRadius: 4 }}>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>X</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.x ?? 0}
                onChange={(e) => onUpdate("x", Number(e.target.value) || 0)}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Y</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.y ?? 64}
                onChange={(e) => onUpdate("y", Number(e.target.value) || 64)}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Z</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.z ?? 0}
                onChange={(e) => onUpdate("z", Number(e.target.value) || 0)}
              />
            </div>
          </div>
        </div>
      )}

      {actionType === "SUMMON_BOSS" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1.2fr 1.2fr 1fr 1fr", gap: 6 }}>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Vanilla Base</label>
              <select
                value={act.vanilla_type || "WITHER_SKELETON"}
                onChange={(e) => onUpdate("vanilla_type", e.target.value)}
                style={{ fontSize: 11, padding: "2px 6px" }}
              >
                {VANILLA_ENTITIES.map((ent) => (
                  <option key={ent.id} value={ent.id}>
                    {ent.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Boss Name</label>
              <input
                type="text"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.name || ""}
                onChange={(e) => onUpdate("name", e.target.value)}
                placeholder="§4§lThe Overseer"
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Boss ID</label>
              <input
                type="text"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.boss_id || ""}
                onChange={(e) => onUpdate("boss_id", e.target.value)}
                placeholder="void_boss"
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Max HP</label>
              <input
                type="number"
                min="10"
                style={{ fontSize: 11, padding: "2px 6px" }}
                value={act.max_health || 200}
                onChange={(e) => onUpdate("max_health", Number(e.target.value) || 200)}
              />
            </div>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 6, background: "rgba(255,255,255,0.02)", padding: 6, borderRadius: 4 }}>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Spawn X</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.x ?? 0}
                onChange={(e) => onUpdate("x", Number(e.target.value) || 0)}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Spawn Y</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.y ?? 64}
                onChange={(e) => onUpdate("y", Number(e.target.value) || 64)}
              />
            </div>
            <div className="form-group" style={{ margin: 0 }}>
              <label style={{ fontSize: 10 }}>Spawn Z</label>
              <input
                type="number"
                step="0.5"
                style={{ fontSize: 11, padding: "2px 4px" }}
                value={act.z ?? 0}
                onChange={(e) => onUpdate("z", Number(e.target.value) || 0)}
              />
            </div>
          </div>
        </div>
      )}

      {actionType === "SPAWN_DUMMY" && (
        <div style={{ display: "grid", gridTemplateColumns: "1.5fr 1.5fr 1fr 1fr 1fr", gap: 6 }}>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>Dummy ID</label>
            <input
              type="text"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.dummy_id || ""}
              onChange={(e) => onUpdate("dummy_id", e.target.value)}
              placeholder="crystal_switch_1"
            />
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>Display Label</label>
            <input
              type="text"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.name || ""}
              onChange={(e) => onUpdate("name", e.target.value)}
              placeholder="§b[ Ancient Switch ]"
            />
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>X</label>
            <input
              type="number"
              step="0.5"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.x ?? 0}
              onChange={(e) => onUpdate("x", Number(e.target.value) || 0)}
            />
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>Y</label>
            <input
              type="number"
              step="0.5"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.y ?? 64}
              onChange={(e) => onUpdate("y", Number(e.target.value) || 64)}
            />
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>Z</label>
            <input
              type="number"
              step="0.5"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.z ?? 0}
              onChange={(e) => onUpdate("z", Number(e.target.value) || 0)}
            />
          </div>
        </div>
      )}

      {actionType === "DESPAWN_DUMMY" && (
        <div className="form-group" style={{ margin: 0 }}>
          <label style={{ fontSize: 10 }}>Dummy ID to remove</label>
          <input
            type="text"
            style={{ fontSize: 11, padding: "2px 6px" }}
            value={act.dummy_id || ""}
            onChange={(e) => onUpdate("dummy_id", e.target.value)}
            placeholder="crystal_switch_1"
          />
        </div>
      )}

      {actionType === "BROADCAST_MESSAGE" && (
        <div className="form-group" style={{ margin: 0 }}>
          <label style={{ fontSize: 10 }}>Chat Message</label>
          <input
            type="text"
            style={{ fontSize: 11, padding: "2px 6px" }}
            value={act.message || ""}
            onChange={(e) => onUpdate("message", e.target.value)}
            placeholder="§aBarrier unlocked!"
          />
        </div>
      )}

      {actionType === "BROADCAST_TITLE" && (
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6 }}>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>Title</label>
            <input
              type="text"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.title || ""}
              onChange={(e) => onUpdate("title", e.target.value)}
              placeholder="§c§lSTEP 1"
            />
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label style={{ fontSize: 10 }}>Subtitle</label>
            <input
              type="text"
              style={{ fontSize: 11, padding: "2px 6px" }}
              value={act.subtitle || ""}
              onChange={(e) => onUpdate("subtitle", e.target.value)}
              placeholder="Defeat the void guards"
            />
          </div>
        </div>
      )}

      {actionType === "GIVE_BONUS" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6, background: "rgba(255,255,255,0.02)", padding: 8, borderRadius: 4 }}>
          <label style={{ fontSize: 10, color: "var(--text-secondary)", fontWeight: 600 }}>Bonus Items</label>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {(act.items || []).map((bItem, bIdx) => (
              <div key={bIdx} style={{ display: "grid", gridTemplateColumns: "2fr 1fr 30px", gap: 6, alignItems: "center" }}>
                <ItemSelect
                  value={bItem.item || ""}
                  onChange={(val) => {
                    const newItems = [...(act.items || [])];
                    newItems[bIdx] = { ...newItems[bIdx], item: val };
                    onUpdate("items", newItems);
                  }}
                />
                <input
                  type="number"
                  min="1"
                  style={{ fontSize: 11, padding: "2px 6px" }}
                  value={bItem.qty || 1}
                  onChange={(e) => {
                    const newItems = [...(act.items || [])];
                    newItems[bIdx] = { ...newItems[bIdx], qty: Number(e.target.value) || 1 };
                    onUpdate("items", newItems);
                  }}
                />
                <button
                  type="button"
                  style={{ background: "transparent", border: "none", color: "var(--text-tertiary)", cursor: "pointer", padding: 2 }}
                  onClick={() => {
                    const newItems = (act.items || []).filter((_, i) => i !== bIdx);
                    onUpdate("items", newItems);
                  }}
                >
                  <Trash2 size={13} />
                </button>
              </div>
            ))}
            <button
              type="button"
              className="btn btn-xs btn-outline"
              style={{ alignSelf: "flex-start", marginTop: 4, fontSize: 10, padding: "2px 8px" }}
              onClick={() => {
                const newItems = [...(act.items || []), { item: "iron_nugget", qty: 3 }];
                onUpdate("items", newItems);
              }}
            >
              <Plus size={11} /> Add Bonus Item
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
