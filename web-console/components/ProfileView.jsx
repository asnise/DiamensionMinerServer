"use client";

import { useState, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import Avatar from "@/components/Avatar";
import Skeleton from "@/components/Skeleton";
import Link from "next/link";
import { ArrowLeft, Package, Database, User, LogOut, Lock, Table, LayoutGrid, History, Sparkles, TrendingUp, TrendingDown } from "lucide-react";
import { formatCompactNumber, nf } from "@/lib/format";
import { useToast } from "@/components/Toast";
import { usePlayerSession, isUuid } from "@/lib/session";
import Pagination from "@/components/Pagination";

function slotLabel(kind, container, slot) {
  if (container && container !== "inv") {
    const armor = {
      HELMET: "Helmet",
      CHESTPLATE: "Chestplate",
      LEGGINGS: "Leggings",
      BOOTS: "Boots",
    };
    return armor[container] || container;
  }
  return kind === "EQUIPPED" ? `Slot ${slot}` : `Inv ${slot}`;
}

function payloadName(p, id) {
  const raw = (p && (p.name || p.display)) || id || "";
  return String(raw).replace(/_/g, " ");
}

export default function ProfileView() {
  const toast = useToast();
  const searchParams = useSearchParams();
  const queryId = searchParams.get("id");

  const { sessionPlayerId, logout } = usePlayerSession();

  const isInspectMode = Boolean(isUuid(queryId) && queryId !== sessionPlayerId);
  const activePlayerId = isUuid(queryId) ? queryId : sessionPlayerId;

  const [profile, setProfile] = useState(null);
  const [inventory, setInventory] = useState([]);
  const [transactions, setTransactions] = useState([]);
  const [errorMsg, setErrorMsg] = useState(null);
  const [viewMode, setViewMode] = useState("table"); // "table" | "grid"
  const [page, setPage] = useState(1);
  const [limit, setLimit] = useState(10); // max 10 rows

  const loading = Boolean(activePlayerId && !errorMsg && profile?.player?.player_id !== activePlayerId);

  useEffect(() => {
    if (!activePlayerId || !isUuid(activePlayerId)) return;

    let cancelled = false;

    Promise.all([
      fetch(`/api/player/${activePlayerId}`).then((r) => r.json()),
      fetch(`/api/player/${activePlayerId}/items`).then((r) => r.json()),
      fetch(`/api/player/${activePlayerId}/transactions`).then((r) => r.json()),
    ])
      .then(([prof, inv, tx]) => {
        if (cancelled) return;
        if (prof.error) {
          setErrorMsg(prof.error);
          setProfile(null);
        } else {
          setProfile(prof);
          setInventory(inv.data || []);
          setTransactions(tx.data || []);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setErrorMsg(String(err?.message || err));
        }
      });

    return () => {
      cancelled = true;
    };
  }, [activePlayerId]);

  const handleLogout = () => {
    logout();
    setProfile(null);
    setInventory([]);
    setErrorMsg(null);
    toast.info("Logged out from profile session", "Logged Out");
  };

  if (!activePlayerId) {
    return (
      <>
        <div className="page-header">
          <h2>Player Profile</h2>
          <p className="page-subtitle">
            Real-time player stats, wallet balances, and inventory assets from PostgreSQL.
          </p>
        </div>

        <div className="card" style={{ maxWidth: 500, margin: "48px auto", padding: "36px 32px", textAlign: "center" }}>
          <div
            style={{
              width: 48,
              height: 48,
              borderRadius: 12,
              background: "var(--accent-subtle)",
              color: "var(--accent)",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              margin: "0 auto 16px",
            }}
          >
            <Lock size={24} />
          </div>

          <h3 style={{ margin: "0 0 8px", fontSize: 18 }}>Authentication Required</h3>
          <p style={{ color: "var(--text-secondary)", fontSize: 13.5, lineHeight: 1.6, margin: "0 0 24px" }}>
            You are currently browsing as a Guest. To view your personal character data, wallets, and inventory, please log in.
          </p>

          <Link
            href="/login"
            className="page-btn primary"
            style={{
              display: "inline-flex",
              alignItems: "center",
              justifyContent: "center",
              padding: "10px 24px",
              fontSize: 14,
              textDecoration: "none",
            }}
          >
            Go to Web Login
          </Link>
        </div>
      </>
    );
  }

  if (loading) {
    return (
      <div>
        <Link className="back-link" href="/">
          <ArrowLeft size={16} /> Back to overview
        </Link>

        <div className="card profile-hero" style={{ marginBottom: 24 }}>
          <div className="profile-hero-main">
            <Skeleton style={{ width: 72, height: 72, borderRadius: 16 }} />
            <div className="profile-info">
              <Skeleton style={{ width: 140, height: 20 }} />
              <Skeleton style={{ width: 220, height: 12, marginTop: 10 }} />
              <Skeleton style={{ width: 160, height: 12, marginTop: 10 }} />
            </div>
          </div>

          <div className="profile-wallets">
            <div className="stat-card mini">
              <div className="stat-label">COIN</div>
              <Skeleton style={{ width: 72, height: 24, margin: "2px 0" }} />
            </div>
            <div className="stat-card mini">
              <div className="stat-label">ENERGY</div>
              <Skeleton style={{ width: 72, height: 24, margin: "2px 0" }} />
            </div>
          </div>
        </div>

        <div className="card" style={{ padding: 0, overflow: "hidden" }}>
          <div className="card-header-row">
            <Skeleton style={{ width: 120, height: 16 }} />
          </div>
          <table className="data-table">
            <thead>
              <tr>
                <th>Slot</th>
                <th>Name</th>
                <th>Kind</th>
                <th>Qty / Rarity</th>
                <th>Binding</th>
              </tr>
            </thead>
            <tbody>
              {Array.from({ length: 4 }).map((_, i) => (
                <tr key={i}>
                  <td>
                    <Skeleton style={{ width: 64, height: 13 }} />
                  </td>
                  <td>
                    <Skeleton style={{ width: 150, height: 13 }} />
                  </td>
                  <td>
                    <Skeleton style={{ width: 60, height: 20, borderRadius: 999 }} />
                  </td>
                  <td>
                    <Skeleton style={{ width: 70, height: 13 }} />
                  </td>
                  <td>
                    <Skeleton style={{ width: 90, height: 13 }} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  if (errorMsg || !profile) {
    return (
      <div className="profile-error">
        <Link className="back-link" href="/">
          <ArrowLeft size={16} /> Back to overview
        </Link>
        <div className="card" style={{ marginTop: 24, padding: 48, textAlign: "center" }}>
          <div style={{ color: "var(--text-secondary)", fontSize: 15, fontWeight: 600, marginBottom: 6 }}>
            {errorMsg || "Player not found in database."}
          </div>
          <p style={{ color: "var(--text-tertiary)", fontSize: 13, margin: "0 0 20px" }}>
            The requested player may not have connected to the Minecraft server yet.
          </p>
          <Link href="/" className="page-btn" style={{ textDecoration: "none" }}>
            Back to Overview
          </Link>
        </div>
      </div>
    );
  }

  const player = profile.player;
  const wallets = Object.fromEntries(
    (profile.wallets || []).map((r) => [r.currency, r])
  );

  const coin = wallets.COIN?.balance || 0;
  const energy = wallets.ENERGY?.balance || 0;
  const totalStacks = inventory.filter((r) => r.asset_kind === "STACK").length;
  const totalItems = inventory.filter((r) => r.asset_kind === "ITEM").length;

  return (
    <>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <Link className="back-link" href="/" style={{ margin: 0 }}>
          <ArrowLeft size={16} /> Back to overview
        </Link>
        {isInspectMode ? (
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span
              className="status-badge"
              style={{
                background: "var(--surface-muted)",
                color: "var(--text-secondary)",
                border: "1px solid var(--border)",
              }}
            >
              Inspecting Player
            </span>
            {sessionPlayerId && (
              <Link
                href="/profile"
                className="page-btn"
                style={{ fontSize: 12, padding: "5px 10px", textDecoration: "none", display: "inline-flex", alignItems: "center", gap: 5 }}
              >
                <User size={13} /> My Profile
              </Link>
            )}
          </div>
        ) : (
          <button
            onClick={handleLogout}
            className="page-btn"
            style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12.5 }}
          >
            <LogOut size={14} /> Log Out
          </button>
        )}
      </div>

      <div className="card profile-hero">
        <div className="profile-hero-main">
          <Avatar uuid={player.player_id} name={player.username} size={72} />
          <div className="profile-info">
            <div className="profile-name">{player.username}</div>
            <div className="profile-meta">
              <span className="kv">
                <Database size={13} /> {player.player_id}
              </span>
              <span className="kv">
                <span
                  className={`status-badge ${player.status === "ACTIVE" ? "online" : "offline"}`}
                >
                  {player.status}
                </span>
              </span>
            </div>
            <div className="profile-meta">
              <span className="kv">
                Joined{" "}
                {new Date(player.created_at).toLocaleDateString("en-CA", {
                  year: "numeric",
                  month: "short",
                  day: "numeric",
                })}
              </span>
            </div>
          </div>
        </div>

        <div className="profile-wallets">
          {(() => {
            const c = formatCompactNumber(coin);
            const e = formatCompactNumber(energy);
            return (
              <>
                <div className="stat-card mini">
                  <div className="stat-label">COIN</div>
                  <div className="stat-value sm">
                    <span className="stat-tooltip-wrap" data-tooltip={c.full} title={c.full}>
                      {c.display}
                    </span>
                  </div>
                </div>
                <div className="stat-card mini">
                  <div className="stat-label">ENERGY</div>
                  <div className="stat-value sm">
                    <span className="stat-tooltip-wrap" data-tooltip={e.full} title={e.full}>
                      {e.display}
                    </span>
                  </div>
                </div>
              </>
            );
          })()}
        </div>
      </div>

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div className="card-header-row" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 16px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <h3 style={{ margin: 0, fontSize: 15, display: "flex", alignItems: "center", gap: 6 }}>
              <Package size={16} />
              Inventory Assets
            </h3>
            <span className="page-indicator">
              {totalStacks} stacks · {totalItems} items
            </span>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div
              style={{
                display: "inline-flex",
                background: "var(--surface-hover)",
                border: "1px solid var(--border)",
                borderRadius: "var(--radius-sm)",
                padding: 2,
              }}
            >
              <button
                type="button"
                onClick={() => setViewMode("table")}
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 5,
                  padding: "4px 10px",
                  fontSize: 12,
                  fontWeight: 600,
                  border: "none",
                  borderRadius: "var(--radius-sm)",
                  cursor: "pointer",
                  background: viewMode === "table" ? "var(--accent)" : "transparent",
                  color: viewMode === "table" ? "#fff" : "var(--text-secondary)",
                  transition: "all 0.15s",
                }}
              >
                <Table size={14} />
                Table View
              </button>

              <button
                type="button"
                onClick={() => setViewMode("grid")}
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 5,
                  padding: "4px 10px",
                  fontSize: 12,
                  fontWeight: 600,
                  border: "none",
                  borderRadius: "var(--radius-sm)",
                  cursor: "pointer",
                  background: viewMode === "grid" ? "var(--accent)" : "transparent",
                  color: viewMode === "grid" ? "#fff" : "var(--text-secondary)",
                  transition: "all 0.15s",
                }}
              >
                <LayoutGrid size={14} />
                Minecraft Grid
              </button>
            </div>
          </div>
        </div>

        {viewMode === "table" ? (
          <>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Slot</th>
                  <th>Name</th>
                  <th>Kind</th>
                  <th>Qty / Rarity</th>
                  <th>Binding</th>
                </tr>
              </thead>
              <tbody>
                {inventory.length === 0 ? (
                  <tr>
                    <td colSpan={5} style={{ textAlign: "center", color: "var(--text-tertiary)", padding: 32 }}>
                      Inventory is empty.
                    </td>
                  </tr>
                ) : (
                  inventory.slice((page - 1) * limit, page * limit).map((row, idx) => {
                    const isStack = row.asset_kind === "STACK";
                    const label = slotLabel(row.kind, row.container_key, row.slot);
                    const name = payloadName(row.payload, row.definition_id);
                    return (
                      <tr key={row.location_id ? `${row.location_id}-${idx}` : `inv-${idx}`}>
                        <td>
                          <span className="slot-badge">{label}</span>
                        </td>
                        <td>
                          <div style={{ fontWeight: 500, textTransform: "capitalize" }}>{name}</div>
                          {row.payload?.description && (
                            <div style={{ fontSize: 11.5, color: "var(--text-tertiary)" }}>
                              {row.payload.description}
                            </div>
                          )}
                          {!isStack && (
                            <div style={{ fontSize: 10.5, color: "var(--text-tertiary)", marginTop: 2, display: "flex", gap: 6, alignItems: "center" }}>
                              <span style={{ fontFamily: "monospace", opacity: 0.7 }}>
                                {row.item_id ? `#${row.item_id.slice(0, 8)}` : ""}
                              </span>
                              {row.origin_source && (
                                <span style={{ background: "var(--surface-hover)", padding: "1px 5px", borderRadius: 3, fontSize: 10 }}>
                                  Origin: {row.origin_source}
                                </span>
                              )}
                            </div>
                          )}
                        </td>
                        <td>
                          <span className={`asset-kind-badge ${isStack ? "stack" : "item"}`}>
                            {row.asset_kind}
                          </span>
                        </td>
                        <td>
                          {isStack ? (
                            <span style={{ fontWeight: 600 }}>×{nf(row.quantity || 1)}</span>
                          ) : (
                            <span className={`rarity-badge ${String(row.rarity || "").toLowerCase()}`}>
                              {row.rarity || "COMMON"}
                            </span>
                          )}
                        </td>
                        <td>
                          <span className="binding-text">{row.binding || "UNBOUND"}</span>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>

            {inventory.length > 0 && (
              <div style={{ borderTop: "1px solid var(--border)", padding: "12px 16px" }}>
                <Pagination
                  total={inventory.length}
                  page={page}
                  limit={limit}
                  setPage={setPage}
                  setLimit={setLimit}
                />
              </div>
            )}
          </>
        ) : (
          <div style={{ padding: 20, background: "var(--surface-subtle)" }}>
            {/* Minecraft Style 9x4 Inventory Grid */}
            <div style={{ maxWidth: 640, margin: "0 auto" }}>
              <div style={{ marginBottom: 8, fontSize: 12, fontWeight: 600, color: "var(--text-tertiary)", textTransform: "uppercase" }}>
                Main Inventory
              </div>

              {/* Grid 9 Columns x 4 Rows */}
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(9, minmax(48px, 1fr))",
                  gap: 6,
                  background: "var(--surface)",
                  border: "2px solid var(--border)",
                  borderRadius: "var(--radius)",
                  padding: 12,
                }}
              >
                {(() => {
                  // Build lookup map for slots 0 to 35
                  // In Minecraft standard: slots 9..35 are main storage (top 3 rows), 0..8 are hotbar (bottom row)
                  const slotMap = new Map();
                  inventory.forEach((row) => {
                    const cont = String(row.container_key || "").toLowerCase();
                    if (cont === "inv" && typeof row.slot === "number") {
                      slotMap.set(row.slot, row);
                    }
                  });

                  // We display Rows:
                  // Row 1: slots 9..17
                  // Row 2: slots 18..26
                  // Row 3: slots 27..35
                  // Row 4: slots 0..8 (Hotbar)
                  const gridSlots = [
                    ...Array.from({ length: 9 }, (_, i) => i + 9),
                    ...Array.from({ length: 9 }, (_, i) => i + 18),
                    ...Array.from({ length: 9 }, (_, i) => i + 27),
                    ...Array.from({ length: 9 }, (_, i) => i),
                  ];

                  return gridSlots.map((slotIndex, gridPos) => {
                    const item = slotMap.get(slotIndex);
                    const isHotbar = gridPos >= 27;
                    const isStack = item?.asset_kind === "STACK";
                    const displayName = item ? payloadName(item.payload, item.definition_id) : "";
                    const qty = item?.quantity || 1;

                    return (
                      <div
                        key={slotIndex}
                        title={
                          item
                            ? `${displayName} (${item.definition_id})\nSlot: ${slotIndex}${isHotbar ? " [Hotbar]" : ""}\n${isStack ? `Qty: ${qty}` : `Rarity: ${item.rarity || "COMMON"}`}`
                            : `Empty Slot ${slotIndex}${isHotbar ? " [Hotbar]" : ""}`
                        }
                        style={{
                          aspectRatio: "1 / 1",
                          background: item ? "var(--surface-hover)" : "var(--surface-subtle)",
                          border: `1px solid ${item ? "var(--border-strong)" : "var(--border)"}`,
                          borderRadius: 4,
                          position: "relative",
                          display: "flex",
                          flexDirection: "column",
                          alignItems: "center",
                          justifyContent: "center",
                          cursor: item ? "pointer" : "default",
                          transition: "all 0.15s",
                          boxShadow: isHotbar ? "inset 0 0 0 1px rgba(99, 102, 241, 0.2)" : "none",
                        }}
                      >
                        <span
                          style={{
                            position: "absolute",
                            top: 2,
                            left: 4,
                            fontSize: 9,
                            color: "var(--text-tertiary)",
                            fontFamily: "monospace",
                            pointerEvents: "none",
                          }}
                        >
                          {slotIndex}
                        </span>

                        {item ? (
                          <>
                            <div
                              style={{
                                fontSize: 11,
                                fontWeight: 600,
                                textAlign: "center",
                                padding: "0 4px",
                                whiteSpace: "nowrap",
                                overflow: "hidden",
                                textOverflow: "ellipsis",
                                width: "100%",
                                color: "var(--text)",
                              }}
                            >
                              {displayName.slice(0, 7)}
                            </div>

                            {isStack ? (
                              <span
                                style={{
                                  position: "absolute",
                                  bottom: 2,
                                  right: 4,
                                  fontSize: 10,
                                  fontWeight: 700,
                                  color: "var(--accent)",
                                }}
                              >
                                {qty}
                              </span>
                            ) : (
                              <span
                                style={{
                                  position: "absolute",
                                  bottom: 2,
                                  right: 4,
                                  fontSize: 8.5,
                                  fontWeight: 700,
                                  color: "var(--text-secondary)",
                                }}
                              >
                                {String(item.rarity || "C").charAt(0)}
                              </span>
                            )}
                          </>
                        ) : null}
                      </div>
                    );
                  });
                })()}
              </div>

              {/* Equipped Armor & Offhand Row */}
              <div style={{ marginTop: 16 }}>
                <div style={{ marginBottom: 6, fontSize: 12, fontWeight: 600, color: "var(--text-tertiary)", textTransform: "uppercase" }}>
                  Equipped Gear & Offhand
                </div>
                <div style={{ display: "flex", gap: 8 }}>
                  {["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "OFFHAND"].map((gearKey) => {
                    const gearItem = inventory.find(
                      (r) => String(r.container_key || "").toUpperCase() === gearKey
                    );
                    const name = gearItem ? payloadName(gearItem.payload, gearItem.definition_id) : "";

                    return (
                      <div
                        key={gearKey}
                        title={
                          gearItem
                            ? `${name} (${gearKey})\nRarity: ${gearItem.rarity || "COMMON"}`
                            : `Empty ${gearKey}`
                        }
                        style={{
                          flex: 1,
                          padding: "8px 10px",
                          background: gearItem ? "var(--surface-hover)" : "var(--surface)",
                          border: `1px solid ${gearItem ? "var(--accent)" : "var(--border)"}`,
                          borderRadius: "var(--radius-sm)",
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "space-between",
                          gap: 8,
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 10, color: "var(--text-tertiary)", textTransform: "uppercase", fontWeight: 600 }}>
                            {gearKey}
                          </div>
                          <div style={{ fontSize: 12, fontWeight: 600, color: gearItem ? "var(--text)" : "var(--text-tertiary)" }}>
                            {name || "None"}
                          </div>
                        </div>
                        {gearItem && (
                          <span className={`rarity-badge ${String(gearItem.rarity || "").toLowerCase()}`} style={{ fontSize: 10 }}>
                            {gearItem.rarity || "COMMON"}
                          </span>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* ----------------- Transaction Audit Log / Ledger ----------------- */}
      <div className="card" style={{ padding: 0, overflow: "hidden", marginTop: 24 }}>
        <div className="card-header-row" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "14px 16px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <History size={16} />
            <h3 style={{ margin: 0, fontSize: 15 }}>Ledger Transaction History</h3>
          </div>
          <span className="page-indicator">{transactions.length} recorded operations</span>
        </div>

        <table className="data-table">
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Reason / Source</th>
              <th>Command Type</th>
              <th>Amount / Delta</th>
              <th>Idempotency Reference</th>
            </tr>
          </thead>
          <tbody>
            {transactions.length === 0 ? (
              <tr>
                <td colSpan={5} style={{ textAlign: "center", color: "var(--text-tertiary)", padding: 32 }}>
                  No transaction ledger records found.
                </td>
              </tr>
            ) : (
              transactions.map((tx) => {
                const deltaNum = Number(tx.delta);
                const isPositive = deltaNum > 0;
                return (
                  <tr key={tx.entry_id}>
                    <td style={{ fontSize: 12, color: "var(--text-secondary)", whiteSpace: "nowrap" }}>
                      {new Date(tx.created_at).toLocaleString("en-GB", {
                        day: "2-digit",
                        month: "short",
                        hour: "2-digit",
                        minute: "2-digit",
                        second: "2-digit",
                      })}
                    </td>

                    <td>
                      <span
                        style={{
                          fontWeight: 600,
                          fontSize: 12.5,
                          background: "var(--surface-hover)",
                          padding: "3px 8px",
                          borderRadius: 4,
                          border: "1px solid var(--border)",
                          fontFamily: "monospace",
                        }}
                      >
                        {tx.reason}
                      </span>
                    </td>

                    <td style={{ fontSize: 12, color: "var(--text-secondary)" }}>
                      {tx.command_type || "SYSTEM"}
                    </td>

                    <td>
                      <span
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          gap: 4,
                          fontWeight: 700,
                          fontSize: 13,
                          color: isPositive ? "var(--success, #16a34a)" : "var(--danger, #dc2626)",
                        }}
                      >
                        {isPositive ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
                        {isPositive ? `+${nf(deltaNum)}` : nf(deltaNum)} {tx.currency}
                      </span>
                    </td>

                    <td>
                      <span
                        style={{
                          fontSize: 11,
                          fontFamily: "ui-monospace, monospace",
                          color: "var(--text-tertiary)",
                          maxWidth: 240,
                          display: "block",
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                        }}
                        title={tx.idempotency_key}
                      >
                        {tx.idempotency_key || tx.entry_id}
                      </span>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
