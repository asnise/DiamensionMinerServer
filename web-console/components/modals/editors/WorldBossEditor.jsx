"use client";

import { useState } from "react";
import { Skull, Plus, Trash2, Award, Gem } from "lucide-react";
import ItemSelect from "@/components/ItemSelect";

const MOBS = [
  "IRON_GOLEM", "RAVAGER", "WARDEN", "WITHER_SKELETON", "EVOKER", "VINDICATOR",
  "PIGLIN_BRUTE", "ZOMBIE", "HUSK", "SKELETON", "BLAZE", "ENDERMAN", "MAGMA_CUBE", "SLIME",
];

const num = (v, d) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : d;
};

// Content kind WorldBoss (Task.md 7.x). Fields match WorldBossDefinition.java.
export default function WorldBossEditor({ payload = {}, onChange }) {
  const [form, setForm] = useState(() => ({
    display_name: payload.display_name || "",
    tier: payload.tier ?? 0,
    vanilla_type: payload.vanilla_type || "IRON_GOLEM",
    stats: { hp: 500, attack: 10, defense: 20, ...(payload.stats || {}) },
    spawn: { x: 0, y: 64, z: 0, ...(payload.spawn || {}) },
    leash_radius: payload.leash_radius ?? 32,
    interval_minutes: payload.interval_minutes ?? 60,
    lifetime_minutes: payload.lifetime_minutes ?? 15,
    eligibility: { min_damage_percent: 1, min_active_seconds: 30, ...(payload.eligibility || {}) },
    server_xp_pool: payload.server_xp_pool ?? 300,
    rewards: {
      standard: payload.rewards?.standard || [],
      rare: payload.rewards?.rare || [],
    },
  }));

  const update = (next) => {
    setForm(next);
    onChange(next);
  };
  const set = (key, value) => update({ ...form, [key]: value });
  const setIn = (group, key, value) => update({ ...form, [group]: { ...form[group], [key]: value } });

  const numberField = (label, value, onSet, props = {}) => (
    <div className="form-group">
      <label>{label}</label>
      <input type="number" value={value} onChange={(e) => onSet(num(e.target.value, 0))} {...props} />
    </div>
  );

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Skull size={16} /> World Boss
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "2fr 1fr 1.5fr", gap: 12 }}>
          <div className="form-group">
            <label>Display Name</label>
            <input type="text" value={form.display_name} onChange={(e) => set("display_name", e.target.value)} placeholder="e.g. Stone Colossus" />
          </div>
          {numberField("Tier (0 = Hub)", form.tier, (v) => set("tier", Math.max(0, Math.floor(v))), { min: 0 })}
          <div className="form-group">
            <label>Vanilla Mob</label>
            <select value={form.vanilla_type} onChange={(e) => set("vanilla_type", e.target.value)}>
              {MOBS.map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          </div>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 12, marginTop: 12 }}>
          {numberField("HP", form.stats.hp, (v) => setIn("stats", "hp", Math.max(1, v)), { min: 1 })}
          {numberField("Attack", form.stats.attack, (v) => setIn("stats", "attack", Math.max(0, v)), { min: 0 })}
          {numberField("Defense", form.stats.defense, (v) => setIn("stats", "defense", Math.max(0, v)), { min: 0 })}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 12, marginTop: 12 }}>
          {numberField("Spawn X", form.spawn.x, (v) => setIn("spawn", "x", v), { step: 0.5 })}
          {numberField("Spawn Y", form.spawn.y, (v) => setIn("spawn", "y", v), { step: 0.5 })}
          {numberField("Spawn Z", form.spawn.z, (v) => setIn("spawn", "z", v), { step: 0.5 })}
          {numberField("Leash Radius", form.leash_radius, (v) => set("leash_radius", Math.max(4, v)), { min: 4 })}
        </div>
        <p style={{ margin: "6px 0 0", fontSize: 12, color: "var(--text-secondary)" }}>
          Spawns in every channel of the tier. Keep the spawn point outside Safe Zones, or nobody can hit it.
        </p>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 12, marginTop: 12 }}>
          {numberField("Every (minutes)", form.interval_minutes, (v) => set("interval_minutes", Math.max(1, Math.floor(v))), { min: 1 })}
          {numberField("Leaves after (minutes)", form.lifetime_minutes, (v) => set("lifetime_minutes", Math.max(1, Math.floor(v))), { min: 1 })}
          {numberField("Server XP Pool", form.server_xp_pool, (v) => set("server_xp_pool", Math.max(0, Math.floor(v))), { min: 0 })}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: 12, marginTop: 12 }}>
          {numberField("Min damage (% of HP)", form.eligibility.min_damage_percent, (v) => setIn("eligibility", "min_damage_percent", Math.max(0, v)), { min: 0, step: 0.5 })}
          {numberField("Min active time (seconds)", form.eligibility.min_active_seconds, (v) => setIn("eligibility", "min_active_seconds", Math.max(0, Math.floor(v))), { min: 0 })}
        </div>
      </div>

      <RewardList
        title="Standard Rewards (every eligible player)"
        icon={<Award size={16} />}
        list={form.rewards.standard}
        onSet={(l) => setIn("rewards", "standard", l)}
      />
      <RewardList
        title="Rare Rewards (Top 3 damage + Last Shot, once per player)"
        icon={<Gem size={16} />}
        list={form.rewards.rare}
        onSet={(l) => setIn("rewards", "rare", l)}
      />
    </div>
  );
}

function RewardList({ title, icon, list, onSet }) {
  const setAt = (i, v) => {
    const next = [...list];
    next[i] = v;
    onSet(next);
  };
  return (
    <div className="card" style={{ padding: 18 }}>
      <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
        {icon} {title}
      </h4>
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {list.map((r, i) => {
          const isItem = r.currency === undefined;
          return (
            <div key={i} style={{ display: "grid", gridTemplateColumns: "1fr 3fr 1fr 40px", gap: 8, alignItems: "center" }}>
              <select
                value={isItem ? "item" : "currency"}
                onChange={(e) => setAt(i, e.target.value === "item" ? { item: "", qty: 1 } : { currency: "COIN", amount: 50 })}
              >
                <option value="item">Item</option>
                <option value="currency">Currency</option>
              </select>
              {isItem ? (
                <ItemSelect value={r.item || ""} onChange={(val) => setAt(i, { ...r, item: val })} />
              ) : (
                <select value={r.currency} onChange={(e) => setAt(i, { ...r, currency: e.target.value })}>
                  <option value="COIN">COIN</option>
                  <option value="ENERGY">ENERGY</option>
                </select>
              )}
              <input
                type="number"
                min="1"
                value={isItem ? r.qty || 1 : r.amount || 1}
                onChange={(e) => {
                  const n = Math.max(1, Math.floor(num(e.target.value, 1)));
                  setAt(i, isItem ? { ...r, qty: n } : { ...r, amount: n });
                }}
              />
              <button
                type="button"
                style={{ background: "transparent", border: "none", color: "var(--text-tertiary)", cursor: "pointer" }}
                onClick={() => onSet(list.filter((_, j) => j !== i))}
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
          onClick={() => onSet([...list, { item: "", qty: 1 }])}
        >
          <Plus size={14} /> Add Reward
        </button>
      </div>
    </div>
  );
}
