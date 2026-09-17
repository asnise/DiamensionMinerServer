"use client";

import { useState, useEffect } from "react";
import { Package, Layers, Shield } from "lucide-react";
import ItemSelect from "@/components/ItemSelect";

export default function ItemEditor({ payload = {}, onChange }) {
  const [form, setForm] = useState(() => {
    const stats = payload.base_stats || {};
    return {
      display: payload.display || payload.name || "",
      icon: payload.icon || "",
      category: payload.category || "WEAPON",
      rarity: payload.rarity || "COMMON",
      stack: payload.stack !== undefined ? payload.stack : 1,
      durability: payload.durability !== undefined ? payload.durability : "",
      refine_max: payload.refine_max !== undefined ? payload.refine_max : "",
      binding: payload.binding || "UNBOUND",
      description: payload.description || "",
      attack_damage: stats.attack_damage ?? payload.attack_damage ?? "",
      attack_speed: stats.attack_speed ?? payload.attack_speed ?? "",
      defense: stats.defense ?? payload.defense ?? "",
      mining_speed: stats.mining_speed ?? payload.mining_speed ?? "",
      crit_chance: stats.crit_chance ?? payload.crit_chance ?? "",
      luck: stats.luck ?? payload.luck ?? "",
      model_path: payload.model_path || "",
      tags: Array.isArray(payload.tags) ? payload.tags.join(", ") : payload.tags || "",
    };
  });

  const updateField = (key, value) => {
    const updated = { ...form, [key]: value };
    setForm(updated);
    notifyParent(updated);
  };

  const notifyParent = (f) => {
    const out = {
      name: f.display,
      display: f.display,
      icon: f.icon || undefined,
      category: f.category,
      rarity: f.rarity,
      stack: Number(f.stack) || 1,
      binding: f.binding,
    };

    if (f.durability !== "") out.durability = Number(f.durability);
    if (f.refine_max !== "") out.refine_max = Number(f.refine_max);
    if (f.description) out.description = f.description;
    if (f.model_path) out.model_path = f.model_path;

    const baseStats = {};
    if (f.attack_damage !== "") baseStats.attack_damage = Number(f.attack_damage);
    if (f.attack_speed !== "") baseStats.attack_speed = Number(f.attack_speed);
    if (f.defense !== "") baseStats.defense = Number(f.defense);
    if (f.mining_speed !== "") baseStats.mining_speed = Number(f.mining_speed);
    if (f.crit_chance !== "") baseStats.crit_chance = Number(f.crit_chance);
    if (f.luck !== "") baseStats.luck = Number(f.luck);

    if (Object.keys(baseStats).length > 0) {
      out.base_stats = baseStats;
    }

    if (f.tags) {
      out.tags = f.tags
        .split(",")
        .map((t) => t.trim())
        .filter(Boolean);
    }

    onChange(out);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      {/* Basic Identity */}
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Package size={16} style={{ color: "var(--accent)" }} /> Item Identity & Visuals
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
          <div>
            <label style={labelStyle}>Display Name *</label>
            <input
              type="text"
              value={form.display}
              onChange={(e) => updateField("display", e.target.value)}
              required
              placeholder="e.g. Mithril Pickaxe"
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Minecraft Material / Icon</label>
            <ItemSelect
              value={form.icon}
              onChange={(val) => updateField("icon", val)}
              placeholder="e.g. netherite_pickaxe"
            />
          </div>
          <div>
            <label style={labelStyle}>Category</label>
            <select
              value={form.category}
              onChange={(e) => updateField("category", e.target.value)}
              style={inputStyle}
            >
              <option value="WEAPON">Weapon</option>
              <option value="ARMOR">Armor</option>
              <option value="TOOL">Mining Tool</option>
              <option value="MATERIAL">Crafting Material</option>
              <option value="CONSUMABLE">Consumable</option>
            </select>
          </div>
          <div>
            <label style={labelStyle}>Rarity</label>
            <select
              value={form.rarity}
              onChange={(e) => updateField("rarity", e.target.value)}
              style={inputStyle}
            >
              <option value="COMMON">Common</option>
              <option value="UNCOMMON">Uncommon</option>
              <option value="RARE">Rare</option>
              <option value="EPIC">Epic</option>
              <option value="LEGENDARY">Legendary</option>
            </select>
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <label style={labelStyle}>Description / Lore</label>
          <textarea
            rows={2}
            value={form.description}
            onChange={(e) => updateField("description", e.target.value)}
            placeholder="e.g. Infused with celestial dimensional energy."
            style={inputStyle}
          />
        </div>
      </div>

      {/* Properties & Stacking */}
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Layers size={16} style={{ color: "var(--accent)" }} /> Item Rules & Binding
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 14 }}>
          <div>
            <label style={labelStyle}>Max Stack</label>
            <input
              type="number"
              min="1"
              max="64"
              value={form.stack}
              onChange={(e) => updateField("stack", Number(e.target.value))}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Durability</label>
            <input
              type="number"
              placeholder="e.g. 1561 (blank = unbreakable)"
              value={form.durability}
              onChange={(e) => updateField("durability", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Binding Type</label>
            <select
              value={form.binding}
              onChange={(e) => updateField("binding", e.target.value)}
              style={inputStyle}
            >
              <option value="UNBOUND">Unbound (Tradable)</option>
              <option value="SOULBOUND">Soulbound (Character)</option>
              <option value="ACCOUNT">Account Bound</option>
            </select>
          </div>
        </div>
      </div>

      {/* Combat & Mining Stats */}
      <div className="card" style={{ padding: 18 }}>
        <h4 style={{ margin: "0 0 14px", fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          <Shield size={16} style={{ color: "var(--accent)" }} /> Combat & Mining Stats
        </h4>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 14 }}>
          <div>
            <label style={labelStyle}>Attack Damage</label>
            <input
              type="number"
              step="0.5"
              placeholder="e.g. 12"
              value={form.attack_damage}
              onChange={(e) => updateField("attack_damage", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Attack Speed</label>
            <input
              type="number"
              step="0.1"
              placeholder="e.g. 1.6"
              value={form.attack_speed}
              onChange={(e) => updateField("attack_speed", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Defense / Armor</label>
            <input
              type="number"
              placeholder="e.g. 8"
              value={form.defense}
              onChange={(e) => updateField("defense", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Mining Speed</label>
            <input
              type="number"
              step="0.5"
              placeholder="e.g. 9.5"
              value={form.mining_speed}
              onChange={(e) => updateField("mining_speed", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Critical Chance (%)</label>
            <input
              type="number"
              step="1"
              placeholder="e.g. 15"
              value={form.crit_chance}
              onChange={(e) => updateField("crit_chance", e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label style={labelStyle}>Mining Luck</label>
            <input
              type="number"
              placeholder="e.g. 3"
              value={form.luck}
              onChange={(e) => updateField("luck", e.target.value)}
              style={inputStyle}
            />
          </div>
        </div>
      </div>

      {/* Extra Tags */}
      <div>
        <label style={labelStyle}>Tags (Comma separated)</label>
        <input
          type="text"
          placeholder="e.g. fire_aspect, dimensional, ore_doubler"
          value={form.tags}
          onChange={(e) => updateField("tags", e.target.value)}
          style={inputStyle}
        />
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
