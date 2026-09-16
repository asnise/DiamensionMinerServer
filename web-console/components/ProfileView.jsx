"use client";

import { useState, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import Avatar from "@/components/Avatar";
import Skeleton from "@/components/Skeleton";
import Link from "next/link";
import { ArrowLeft, Package, Database, User, LogOut, Lock } from "lucide-react";
import { formatCompactNumber, nf } from "@/lib/format";
import { useToast } from "@/components/Toast";
import { usePlayerSession, isUuid } from "@/lib/session";

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
  const [errorMsg, setErrorMsg] = useState(null);

  const loading = Boolean(activePlayerId && !errorMsg && profile?.player?.player_id !== activePlayerId);

  useEffect(() => {
    if (!activePlayerId || !isUuid(activePlayerId)) return;

    let cancelled = false;

    Promise.all([
      fetch(`/api/player/${activePlayerId}`).then((r) => r.json()),
      fetch(`/api/player/${activePlayerId}/items`).then((r) => r.json()),
    ])
      .then(([prof, inv]) => {
        if (cancelled) return;
        if (prof.error) {
          setErrorMsg(prof.error);
          setProfile(null);
        } else {
          setProfile(prof);
          setInventory(inv.data || []);
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
        <div className="card-header-row">
          <h3 style={{ margin: 0, fontSize: 15 }}>
            <Package size={16} style={{ verticalAlign: -2, marginRight: 8 }} />
            Inventory
          </h3>
          <span className="page-indicator">
            {totalStacks} stacks · {totalItems} items
          </span>
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
            {inventory.length === 0 ? (
              <tr>
                <td colSpan={5} style={{ textAlign: "center", color: "var(--text-tertiary)", padding: 32 }}>
                  Inventory is empty.
                </td>
              </tr>
            ) : (
              inventory.map((row, idx) => {
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
      </div>
    </>
  );
}
