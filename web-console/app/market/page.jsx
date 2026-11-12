"use client";

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { Search, RefreshCw } from "lucide-react";
import Skeleton from "@/components/Skeleton";
import { nf, formatCompactNumber } from "@/lib/format";
import { useToast } from "@/components/Toast";
import ConfirmModal from "@/components/modals/ConfirmModal";
import { usePlayerSession } from "@/lib/session";

// Task.md 11.3: the in-game Auction House (/ah) on the web. Browse is public; buying and taking your
// own listings down need the web login. Listing new items is done in game at a Market Clerk.

function timeLeft(iso) {
  const ms = new Date(iso) - Date.now();
  if (ms <= 0) return "expired";
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  return h > 0 ? `${h}h ${m}m left` : `${m}m left`;
}

export default function MarketPage() {
  const toast = useToast();
  const { sessionPlayerId: playerId } = usePlayerSession();
  const [tab, setTab] = useState("all"); // all | mine
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ listings: [], has_next: false });
  const [loading, setLoading] = useState(true);
  const [coin, setCoin] = useState(null);
  const [confirm, setConfirm] = useState(null); // { kind: 'buy'|'cancel', listing }
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const qs = new URLSearchParams({ page: String(page) });
      if (search) qs.set("q", search);
      if (tab === "mine") qs.set("mine", "1");
      const res = await fetch(`/api/market?${qs}`, { cache: "no-store" });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error || "Could not load the market");
      setData(json);
    } catch (err) {
      toast.error(err.message, "Market");
      setData({ listings: [], has_next: false });
    } finally {
      setLoading(false);
    }
  }, [page, search, tab, toast]);

  // Deferred one tick: the effect only schedules the fetch (state is set when the response arrives).
  useEffect(() => {
    const t = setTimeout(load, 0);
    return () => clearTimeout(t);
  }, [load]);

  useEffect(() => {
    if (!playerId) return;
    let cancelled = false;
    fetch(`/api/player/${playerId}`)
      .then((r) => r.json())
      .then((res) => {
        if (cancelled) return;
        const w = (res.wallets || []).find((x) => x.currency === "COIN");
        setCoin(w ? Number(w.balance) : 0);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [playerId]);

  const shownCoin = playerId ? coin : null;

  const submitSearch = (e) => {
    e.preventDefault();
    setLoading(true);
    setPage(0);
    setSearch(query.trim());
  };

  const act = async () => {
    if (!confirm || busy) return;
    const { kind, listing } = confirm;
    setBusy(true);
    try {
      const res = await fetch(kind === "buy" ? "/api/market/buy" : "/api/market/cancel", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(kind === "buy" ? { listing_id: listing.listing_id, price: listing.price } : { listing_id: listing.listing_id }),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error || "Request failed");
      setConfirm(null);
      if (kind === "buy") {
        setCoin(json.newBalance);
        toast.success(`${listing.name} x${listing.quantity} for ${nf(listing.price)} COIN. Run /grants in Minecraft to claim it.`, "Purchased");
      } else {
        toast.success(json.message, "Listing cancelled");
      }
      load();
    } catch (err) {
      setConfirm(null);
      toast.error(err.message, kind === "buy" ? "Purchase failed" : "Cancel failed");
      load();
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="page-header">
        <h2>Market</h2>
        <p className="page-subtitle">
          Player Auction House · fixed prices · bought items arrive in your in-game mailbox (/grants). List items in game at a Market Clerk.
        </p>
      </div>

      <div className="card" style={{ marginBottom: 20, padding: "14px 18px" }}>
        <div style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap", justifyContent: "space-between" }}>
          <div style={{ display: "flex", gap: 8 }}>
            {[
              ["all", "All listings"],
              ["mine", "My listings"],
            ].map(([id, label]) => (
              <button
                key={id}
                type="button"
                className={tab === id ? "btn-primary" : "btn-secondary"}
                style={{ padding: "6px 14px", fontSize: 13 }}
                disabled={id === "mine" && !playerId}
                title={id === "mine" && !playerId ? "Log in to see your listings" : undefined}
                onClick={() => {
                  if (id === tab) return;
                  setLoading(true);
                  setTab(id);
                  setPage(0);
                }}
              >
                {label}
              </button>
            ))}
          </div>
          <form onSubmit={submitSearch} style={{ display: "flex", gap: 8, flex: "1 1 260px", maxWidth: 420 }}>
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search item id, e.g. prim_metal"
              style={{ flex: 1 }}
            />
            <button type="submit" className="btn-secondary" style={{ padding: "6px 12px" }} aria-label="Search">
              <Search size={15} />
            </button>
            <button type="button" className="btn-secondary" style={{ padding: "6px 12px" }} onClick={() => { setLoading(true); load(); }} aria-label="Refresh">
              <RefreshCw size={15} />
            </button>
          </form>
          <div style={{ fontSize: 13, color: "var(--text-secondary)" }}>
            {playerId ? (
              <>
                Wallet: <strong style={{ color: "var(--text)" }}>{shownCoin == null ? "…" : nf(shownCoin)} COIN</strong>
              </>
            ) : (
              <Link href="/login">Log in to buy</Link>
            )}
          </div>
        </div>
      </div>

      <div className="product-grid">
        {loading &&
          Array.from({ length: 6 }).map((_, i) => (
            <div className="product-card" key={i}>
              <Skeleton width={150} height={18} style={{ marginBottom: 10 }} />
              <Skeleton width="70%" height={14} style={{ marginBottom: 18 }} />
              <Skeleton width={90} height={24} />
            </div>
          ))}

        {!loading && data.listings.length === 0 && (
          <div className="card" style={{ gridColumn: "1 / -1", textAlign: "center", padding: 48 }}>
            <div className="empty-state" style={{ padding: 0 }}>
              <div className="empty-title">{tab === "mine" ? "You have no active listings" : "No listings found"}</div>
              <div>{tab === "mine" ? "List items in game at a Market Clerk." : "Try another search, or check back later."}</div>
            </div>
          </div>
        )}

        {!loading &&
          data.listings.map((l) => {
            const canAfford = shownCoin == null || shownCoin >= l.price;
            return (
              <div className="product-card" key={l.listing_id}>
                <div className="product-card-header">
                  <h4 className="product-card-title">
                    {l.name} <span style={{ color: "var(--text-secondary)", fontWeight: 500 }}>x{l.quantity}</span>
                  </h4>
                  <span className="status-badge online">{timeLeft(l.expires_at)}</span>
                </div>
                <p className="product-card-desc">
                  <code>{l.definition_id}</code>
                  {l.rarity ? ` · ${l.rarity}` : ""} · seller <strong>{l.seller_name}</strong>
                </p>
                <div className="product-card-footer">
                  <div>
                    <div className="product-price-label">Price</div>
                    <div className="product-price-value" title={`${nf(l.price)} COIN`}>
                      {formatCompactNumber(l.price).display}
                      <span className="product-price-unit">COIN</span>
                    </div>
                  </div>
                  {l.own ? (
                    <button type="button" className="btn-secondary" style={{ padding: "8px 14px", fontSize: 13 }}
                      onClick={() => setConfirm({ kind: "cancel", listing: l })}>
                      Take down
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="btn-primary"
                      style={{ padding: "8px 16px", fontSize: 13 }}
                      disabled={!playerId || !canAfford}
                      title={!playerId ? "Log in to buy" : !canAfford ? "Not enough COIN" : undefined}
                      onClick={() => setConfirm({ kind: "buy", listing: l })}
                    >
                      {!playerId ? "Log in to buy" : canAfford ? "Buy" : "Not enough COIN"}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
      </div>

      {!loading && (page > 0 || data.has_next) && (
        <div style={{ display: "flex", justifyContent: "center", gap: 10, marginTop: 20 }}>
          <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => { setLoading(true); setPage((p) => p - 1); }}>
            Previous
          </button>
          <span style={{ alignSelf: "center", fontSize: 13, color: "var(--text-secondary)" }}>Page {page + 1}</span>
          <button type="button" className="btn-secondary" disabled={!data.has_next} onClick={() => { setLoading(true); setPage((p) => p + 1); }}>
            Next
          </button>
        </div>
      )}

      <ConfirmModal
        isOpen={!!confirm}
        onClose={() => !busy && setConfirm(null)}
        onConfirm={act}
        loading={busy}
        title={confirm?.kind === "buy" ? "Confirm purchase" : "Take listing down"}
        confirmText={confirm?.kind === "buy" ? `Buy for ${confirm ? nf(confirm.listing.price) : ""} COIN` : "Take it down"}
        variant={confirm?.kind === "buy" ? "primary" : "warning"}
        size="md"
      >
        {confirm && (
          <div style={{ fontSize: 13.5, color: "var(--text-secondary)" }}>
            <p style={{ margin: "0 0 12px" }}>
              <strong style={{ color: "var(--text)" }}>
                {confirm.listing.name} x{confirm.listing.quantity}
              </strong>{" "}
              from {confirm.listing.seller_name}
            </p>
            {confirm.kind === "buy" ? (
              <>
                <p style={{ margin: "0 0 8px" }}>
                  Price: <strong style={{ color: "var(--text)" }}>{nf(confirm.listing.price)} COIN</strong>
                  {shownCoin != null && <> · wallet after: {nf(Math.max(0, shownCoin - confirm.listing.price))} COIN</>}
                </p>
                <p style={{ margin: 0, fontSize: 12, color: "var(--text-tertiary)" }}>
                  The exact item (gear keeps its upgrades) goes to your /grants mailbox. Run /grants in Minecraft to claim it.
                </p>
              </>
            ) : (
              <p style={{ margin: 0 }}>The item goes back to your /grants mailbox. No fee is charged.</p>
            )}
          </div>
        )}
      </ConfirmModal>
    </>
  );
}
