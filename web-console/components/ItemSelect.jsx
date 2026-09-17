"use client";

import { useState, useEffect, useRef, useId } from "react";
import { ChevronDown, Search, Sparkles, Box, Check } from "lucide-react";

// Global memory cache to prevent fetching repeatedly across inputs
let cachedCatalog = null;
let catalogPromise = null;

function fetchCatalog() {
  if (cachedCatalog) return Promise.resolve(cachedCatalog);
  if (!catalogPromise) {
    catalogPromise = fetch("/api/items/catalog")
      .then((r) => r.json())
      .then((data) => {
        cachedCatalog = data.items || [];
        return cachedCatalog;
      })
      .catch(() => []);
  }
  return catalogPromise;
}

export default function ItemSelect({
  value = "",
  onChange,
  placeholder = "Select or type item ID...",
  style = {},
}) {
  const [items, setItems] = useState(cachedCatalog || []);
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const containerRef = useRef(null);

  useEffect(() => {
    fetchCatalog().then((list) => {
      setItems(list);
    });
  }, []);

  // Close dropdown on click outside
  useEffect(() => {
    function handleClickOutside(e) {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setIsOpen(false);
      }
    }
    if (isOpen) {
      document.addEventListener("mousedown", handleClickOutside);
    }
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [isOpen]);

  const selectedItem = items.find((i) => i.id === value);

  const filtered = items.filter((i) => {
    if (!search.trim()) return true;
    const q = search.toLowerCase();
    return i.id.toLowerCase().includes(q) || i.name.toLowerCase().includes(q);
  });

  const registryItems = filtered.filter((i) => i.isRegistry);
  const vanillaItems = filtered.filter((i) => !i.isRegistry);

  const handleSelect = (itemId) => {
    onChange(itemId);
    setIsOpen(false);
    setSearch("");
  };

  return (
    <div ref={containerRef} style={{ position: "relative", width: "100%", ...style }}>
      <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
        {/* Main input where admin can type directly or click to open dropdown */}
        <div style={{ position: "relative", flex: 1 }}>
          <input
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value.toLowerCase().replace(/\s+/g, "_"))}
            placeholder={placeholder}
            style={{
              width: "100%",
              padding: "8px 30px 8px 12px",
              borderRadius: "var(--radius-sm)",
              border: "1px solid var(--border-strong)",
              background: "var(--surface)",
              color: "var(--text)",
              fontSize: 13,
              fontFamily: "ui-monospace, monospace",
              outline: "none",
            }}
          />
          <button
            type="button"
            onClick={() => setIsOpen(!isOpen)}
            style={{
              position: "absolute",
              right: 6,
              top: "50%",
              transform: "translateY(-50%)",
              background: "transparent",
              border: "none",
              color: "var(--text-tertiary)",
              cursor: "pointer",
              padding: 4,
              display: "flex",
              alignItems: "center",
            }}
            title="Browse item catalog"
          >
            <ChevronDown size={14} />
          </button>
        </div>

        {/* Informative source badge if known item */}
        {selectedItem && (
          <span
            style={{
              fontSize: 11,
              padding: "3px 8px",
              borderRadius: 4,
              fontWeight: 600,
              whiteSpace: "nowrap",
              background: selectedItem.isRegistry ? "rgba(99, 102, 241, 0.15)" : "var(--surface-muted)",
              color: selectedItem.isRegistry ? "var(--accent)" : "var(--text-secondary)",
              border: "1px solid var(--border)",
              display: "inline-flex",
              alignItems: "center",
              gap: 4,
            }}
            title={selectedItem.name}
          >
            {selectedItem.isRegistry ? <Sparkles size={11} /> : <Box size={11} />}
            {selectedItem.name}
          </span>
        )}
      </div>

      {/* Dropdown Menu */}
      {isOpen && (
        <div
          style={{
            position: "absolute",
            top: "calc(100% + 4px)",
            left: 0,
            right: 0,
            zIndex: 1000,
            background: "var(--surface)",
            border: "1px solid var(--border-strong)",
            borderRadius: "var(--radius-md)",
            boxShadow: "0 10px 25px -5px rgba(0, 0, 0, 0.3)",
            maxHeight: 280,
            overflowY: "auto",
            display: "flex",
            flexDirection: "column",
          }}
        >
          {/* Search bar inside dropdown */}
          <div
            style={{
              padding: "8px 10px",
              borderBottom: "1px solid var(--border)",
              position: "sticky",
              top: 0,
              background: "var(--surface)",
              zIndex: 2,
            }}
          >
            <div style={{ position: "relative" }}>
              <Search
                size={13}
                style={{
                  position: "absolute",
                  left: 8,
                  top: "50%",
                  transform: "translateY(-50%)",
                  color: "var(--text-tertiary)",
                }}
              />
              <input
                type="text"
                autoFocus
                placeholder="Search materials, ores, gear..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                style={{
                  width: "100%",
                  padding: "5px 8px 5px 26px",
                  fontSize: 12,
                  borderRadius: 4,
                  border: "1px solid var(--border)",
                  background: "var(--surface-muted)",
                  color: "var(--text)",
                  outline: "none",
                }}
              />
            </div>
          </div>

          {/* List items */}
          <div style={{ padding: "4px 0" }}>
            {/* Custom Content Registry Items */}
            {registryItems.length > 0 && (
              <div>
                <div
                  style={{
                    padding: "6px 10px 4px",
                    fontSize: 10.5,
                    fontWeight: 700,
                    textTransform: "uppercase",
                    letterSpacing: 0.5,
                    color: "var(--accent)",
                    background: "rgba(99, 102, 241, 0.08)",
                  }}
                >
                  Content Registry (Custom Items)
                </div>
                {registryItems.map((item) => (
                  <div
                    key={item.id}
                    onClick={() => handleSelect(item.id)}
                    style={{
                      padding: "6px 12px",
                      fontSize: 12.5,
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      cursor: "pointer",
                      background: value === item.id ? "var(--accent-subtle)" : "transparent",
                    }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = "var(--surface-muted)")}
                    onMouseLeave={(e) =>
                      (e.currentTarget.style.background =
                        value === item.id ? "var(--accent-subtle)" : "transparent")
                    }
                  >
                    <div>
                      <span style={{ fontWeight: 600 }}>{item.name}</span>
                      <span
                        style={{
                          marginLeft: 8,
                          fontSize: 11,
                          color: "var(--text-tertiary)",
                          fontFamily: "ui-monospace, monospace",
                        }}
                      >
                        {item.id}
                      </span>
                    </div>
                    {value === item.id && <Check size={13} style={{ color: "var(--accent)" }} />}
                  </div>
                ))}
              </div>
            )}

            {/* Minecraft Vanilla Items */}
            {vanillaItems.length > 0 && (
              <div>
                <div
                  style={{
                    padding: "6px 10px 4px",
                    fontSize: 10.5,
                    fontWeight: 700,
                    textTransform: "uppercase",
                    letterSpacing: 0.5,
                    color: "var(--text-tertiary)",
                    marginTop: registryItems.length > 0 ? 6 : 0,
                  }}
                >
                  Minecraft Vanilla Database
                </div>
                {vanillaItems.map((item) => (
                  <div
                    key={item.id}
                    onClick={() => handleSelect(item.id)}
                    style={{
                      padding: "6px 12px",
                      fontSize: 12.5,
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      cursor: "pointer",
                      background: value === item.id ? "var(--accent-subtle)" : "transparent",
                    }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = "var(--surface-muted)")}
                    onMouseLeave={(e) =>
                      (e.currentTarget.style.background =
                        value === item.id ? "var(--accent-subtle)" : "transparent")
                    }
                  >
                    <div>
                      <span style={{ fontWeight: 500 }}>{item.name}</span>
                      <span
                        style={{
                          marginLeft: 8,
                          fontSize: 11,
                          color: "var(--text-tertiary)",
                          fontFamily: "ui-monospace, monospace",
                        }}
                      >
                        {item.id}
                      </span>
                    </div>
                    {value === item.id && <Check size={13} style={{ color: "var(--accent)" }} />}
                  </div>
                ))}
              </div>
            )}

            {filtered.length === 0 && (
              <div style={{ padding: "12px", textAlign: "center", color: "var(--text-tertiary)", fontSize: 12 }}>
                No matching items. You can still type custom ID "{search}".
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
