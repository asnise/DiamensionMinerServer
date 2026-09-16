"use client";

import { useState } from "react";
import {
  Gem,
  Sword,
  Settings,
  Pickaxe,
  Hammer,
  Gift,
  Download,
} from "lucide-react";
import DataTable from "@/components/DataTable";

const TABS = [
  { id: "ItemBase", label: "Items & Gear", icon: Sword },
  { id: "PassiveGenerator", label: "Passive Income", icon: Settings },
  { id: "UpgradeTree", label: "Upgrades & Skills", icon: Pickaxe },
  { id: "Recipe", label: "Crafting Recipes", icon: Hammer },
  { id: "LootTable", label: "Loot Tables", icon: Gift },
];

export default function Home() {
  const [activeTab, setActiveTab] = useState("ItemBase");

  const handleExport = async () => {
    try {
      const res = await fetch("/api/export");
      const exportData = await res.json();
      const blob = new Blob([JSON.stringify(exportData, null, 2)], {
        type: "application/json",
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "content_registry.json";
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err) {
      alert("Failed to export");
    }
  };

  const active = TABS.find((t) => t.id === activeTab);

  return (
    <div className="app-container">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-text">
            <h1>Dimensional Miner</h1>
            <p>Content Editor</p>
          </div>
        </div>

        <div className="section-label">Content</div>
        {TABS.map((tab) => {
          const Icon = tab.icon;
          return (
            <div
              key={tab.id}
              className={`nav-item ${activeTab === tab.id ? "active" : ""}`}
              onClick={() => setActiveTab(tab.id)}
            >
              <Icon size={18} strokeWidth={2} />
              {tab.label}
            </div>
          );
        })}

        <div style={{ flex: 1 }} />
        <button className="export-btn" onClick={handleExport}>
          <Download size={16} strokeWidth={2} />
          Export to JSON
        </button>
      </aside>

      <div className="main-content">
        <div className="page-header">
          <h2>Content Registry</h2>
          <p className="page-subtitle">
            {active.label} · definitions stored in PostgreSQL
          </p>
        </div>
        <DataTable kind={activeTab} />
      </div>
    </div>
  );
}
