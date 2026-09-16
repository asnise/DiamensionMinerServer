"use client";

import { useState } from 'react';
import { Sword, Settings, Pickaxe, Hammer, Gift, Download } from 'lucide-react';
import DataTable from '@/components/DataTable';

const TABS = [
  { id: 'items', label: 'Items & Gear', icon: Sword },
  { id: 'generators', label: 'Passive Income', icon: Settings },
  { id: 'mining', label: 'Mining & Dimensions', icon: Pickaxe },
  { id: 'crafting', label: 'Crafting Recipes', icon: Hammer },
  { id: 'loot_tables', label: 'Loot Tables', icon: Gift },
];

export default function Home() {
  const [activeTab, setActiveTab] = useState('items');

  const handleExport = async () => {
    try {
      const res = await fetch('/api/export');
      const exportData = await res.json();
      const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = "content_registry.json";
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err) {
      alert('Failed to export');
    }
  };

  return (
    <div className="app-container">
      <div className="sidebar">
        <div className="brand">
          <h1>Dimensional Miner</h1>
          <p>Internal Editor</p>
        </div>
        {TABS.map(tab => {
          const Icon = tab.icon;
          return (
            <div key={tab.id} className={`nav-item ${activeTab === tab.id ? 'active' : ''}`} onClick={() => setActiveTab(tab.id)}>
              <Icon size={18} /> {tab.label}
            </div>
          );
        })}
        <button className="export-btn" onClick={handleExport} style={{marginTop: 'auto'}}>
          <Download size={16} /> Export to JSON
        </button>
      </div>
      <div className="main-content">
        <h2>Content Registry - {activeTab.replace('_', ' ').toUpperCase()}</h2>
        <DataTable kind={activeTab} />
      </div>
    </div>
  );
}
