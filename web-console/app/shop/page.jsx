"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Avatar from "@/components/Avatar";
import Skeleton from "@/components/Skeleton";
import { formatCompactNumber, nf } from "@/lib/format";
import { useToast } from "@/components/Toast";
import ConfirmModal from "@/components/modals/ConfirmModal";

import { usePlayerSession } from "@/lib/session";

export default function ShopPage() {
  const router = useRouter();
  const toast = useToast();

  const { sessionPlayerId, logout } = usePlayerSession();
  const playerId = sessionPlayerId;

  const [player, setPlayer] = useState(null);
  const [wallets, setWallets] = useState({});
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [buyingId, setBuyingId] = useState(null);
  const [confirmProduct, setConfirmProduct] = useState(null);
  // One key per purchase attempt: a retry (double click, network error) re-sends the same key,
  // so the server charges at most once. A new key is made each time the confirm dialog opens.
  const [purchaseKey, setPurchaseKey] = useState(null);

  const openConfirm = (product) => {
    setPurchaseKey(crypto.randomUUID());
    setConfirmProduct(product);
  };

  useEffect(() => {
    let cancelled = false;
    fetch("/api/shop/products")
      .then((r) => r.json())
      .then((res) => {
        if (!cancelled) {
          setProducts(res.data || []);
          setLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!playerId) {
      setPlayer(null);
      setWallets({});
      return;
    }

    let cancelled = false;
    fetch(`/api/player/${playerId}`)
      .then((r) => r.json())
      .then((res) => {
        if (cancelled) return;
        if (res.player) {
          setPlayer(res.player);
          const map = {};
          (res.wallets || []).forEach((w) => {
            map[w.currency] = Number(w.balance);
          });
          setWallets(map);
        } else {
          setPlayer(null);
          setWallets({});
        }
      })
      .catch(() => {
        if (!cancelled) {
          setPlayer(null);
          setWallets({});
        }
      });

    return () => {
      cancelled = true;
    };
  }, [playerId]);

  const handleLogout = () => {
    logout();
    setPlayer(null);
    setWallets({});
    toast.info("Logged out from shop session", "Logged Out");
  };

  const handleBuy = async (product) => {
    if (!playerId) {
      toast.warning(
        "Authentication required to make purchases. Please log in first.",
        "Login Required"
      );
      router.push("/login");
      return;
    }

    if (buyingId) return; // a request for this purchase is already in flight
    setBuyingId(product.product_id);

    try {
      const res = await fetch("/api/shop/buy", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // The buyer comes from the session cookie on the server.
        body: JSON.stringify({ product_id: product.product_id, idempotency_key: purchaseKey }),
      });

      const json = await res.json();
      if (res.status === 401) {
        toast.warning(json.error || "Your session expired. Please log in again.", "Login Required");
        router.push("/login");
        return;
      }
      if (!res.ok) {
        throw new Error(json.error || "Purchase failed");
      }

      toast.success(
        `${json.message} (Grant ID: ${json.grant_id.slice(0, 8)}...). Run /grants in Minecraft to claim!`,
        "Purchase Successful"
      );

      setWallets((prev) => ({
        ...prev,
        COIN: json.new_balance,
      }));
      setConfirmProduct(null);
    } catch (err) {
      toast.error(err.message, "Purchase Failed");
    } finally {
      setBuyingId(null);
    }
  };

  const formatRemainingTime = (dateStr) => {
    if (!dateStr) return null;
    const diff = new Date(dateStr) - new Date();
    if (diff <= 0) return "Expired";
    const days = Math.floor(diff / (1000 * 60 * 60 * 24));
    const hours = Math.floor((diff / (1000 * 60 * 60)) % 24);
    if (days > 0) return `${days}d ${hours}h left`;
    const mins = Math.floor((diff / (1000 * 60)) % 60);
    return `${hours}h ${mins}m left`;
  };

  const activePlayer = player?.player_id === playerId ? player : null;
  const activeWallets = player?.player_id === playerId ? wallets : {};
  const playerCoin = activeWallets.COIN || 0;
  const playerLoading = Boolean(playerId && !activePlayer);

  return (
    <>
      <div className="page-header">
        <h2>Supply Shop</h2>
        <p className="page-subtitle">
          Official server store · Purchases deduct COIN and deliver items directly to your in-game mailbox (/grants).
        </p>
      </div>

      {!playerLoading && !activePlayer && (
        <div className="card" style={{ marginBottom: 24, padding: "18px 24px" }}>
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 16,
            }}
          >
            <div>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <h3 style={{ margin: 0, fontSize: 15 }}>Browsing as Guest</h3>
                <span className="status-badge offline">Guest</span>
              </div>
              <p style={{ margin: "4px 0 0", color: "var(--text-secondary)", fontSize: 13 }}>
                To purchase items using your in-game COIN, please authenticate with your account.
              </p>
            </div>

            <Link href="/login" className="page-btn primary" style={{ textDecoration: "none" }}>
              Go to Web Login
            </Link>
          </div>
        </div>
      )}

      {playerLoading && (
        <div className="card" style={{ marginBottom: 24, padding: "16px 20px" }}>
          <div style={{ display: "flex", justifyContent: "space-between" }}>
            <Skeleton width={180} height={20} />
            <Skeleton width={120} height={20} />
          </div>
        </div>
      )}

      {!playerLoading && activePlayer && (
        <div className="card" style={{ marginBottom: 24, padding: "16px 24px" }}>
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 16,
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <Avatar uuid={activePlayer.player_id} name={activePlayer.username} size={36} />
              <div>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <span style={{ fontWeight: 600, fontSize: 15 }}>{activePlayer.username}</span>
                  <span className="status-badge online">Active Session</span>
                </div>
                <div style={{ fontSize: 12, color: "var(--text-tertiary)", fontFamily: "ui-monospace, monospace" }}>
                  {activePlayer.player_id}
                </div>
              </div>
            </div>

            <div style={{ textAlign: "right" }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: "var(--text-tertiary)", textTransform: "uppercase" }}>
                Wallet Balance
              </div>
              <div style={{ fontSize: 18, fontWeight: 700, color: "var(--text)" }}>
                <span
                  className="stat-tooltip-wrap"
                  data-tooltip={`${nf(playerCoin)} COIN`}
                  title={`${nf(playerCoin)} COIN`}
                >
                  {formatCompactNumber(playerCoin).display}{" "}
                  <span style={{ fontSize: 12, color: "var(--text-secondary)", fontWeight: 500 }}>COIN</span>
                </span>
              </div>
            </div>
          </div>
        </div>
      )}

      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginBottom: 16,
        }}
      >
        <div>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 650 }}>Catalog Packages</h3>
          <p style={{ margin: "2px 0 0", fontSize: 12.5, color: "var(--text-tertiary)" }}>
            Purchased items are fulfilled to your in-game mailbox (/grants)
          </p>
        </div>
        <span style={{ fontSize: 12.5, color: "var(--text-secondary)", fontWeight: 500 }}>
          {products.length} packages available
        </span>
      </div>

      <div className="product-grid">
        {loading &&
          Array.from({ length: 3 }).map((_, i) => (
            <div className="product-card" key={i}>
              <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 12 }}>
                <Skeleton width={140} height={18} />
                <Skeleton width={60} height={20} style={{ borderRadius: 999 }} />
              </div>
              <Skeleton width="90%" height={14} style={{ marginBottom: 16 }} />
              <div className="product-items-box">
                <Skeleton width={80} height={11} style={{ marginBottom: 8 }} />
                <Skeleton width="100%" height={16} style={{ marginBottom: 6 }} />
                <Skeleton width="75%" height={16} />
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", marginTop: "auto", paddingTop: 16 }}>
                <Skeleton width={80} height={24} />
                <Skeleton width={96} height={34} style={{ borderRadius: 6 }} />
              </div>
            </div>
          ))}

        {!loading && products.length === 0 && (
          <div className="card" style={{ gridColumn: "1 / -1", textAlign: "center", padding: 48 }}>
            <div className="empty-state" style={{ padding: 0 }}>
              <div className="empty-title">No products available</div>
              <div>Store catalog is currently empty. Check back later.</div>
            </div>
          </div>
        )}

        {!loading &&
          products.map((product) => {
            const items = Array.isArray(product.items) ? product.items : [];
            const canAfford = !player || playerCoin >= product.price_coin;
            const isBuyingThis = buyingId === product.product_id;
            const timeBadge = formatRemainingTime(product.time_limited_until);
            const isLowBalance = player && !canAfford;

            return (
              <div
                className="product-card"
                key={product.product_id}
                style={{
                  opacity: isLowBalance ? 0.72 : 1,
                  filter: isLowBalance ? "grayscale(0.35)" : "none",
                  transition: "opacity 0.2s, filter 0.2s",
                }}
              >
                <div className="product-card-header">
                  <h4 className="product-card-title">{product.name}</h4>
                  {timeBadge ? (
                    <span
                      className="status-badge"
                      style={{
                        background: "#fffbeb",
                        color: "#b45309",
                      }}
                    >
                      {timeBadge}
                    </span>
                  ) : (
                    <span className="status-badge online">In Stock</span>
                  )}
                </div>

                <p className="product-card-desc">{product.description}</p>

                <div className="product-items-box">
                  <div className="product-items-label">Included Items</div>
                  {items.map((it, idx) => (
                    <div key={idx} className="product-item-row">
                      <span className="product-item-name">{it.name || it.material}</span>
                      <span className="product-item-qty">x{it.amount}</span>
                    </div>
                  ))}
                </div>

                <div className="product-card-footer">
                  <div>
                    <div className="product-price-label">Price</div>
                    <div className="product-price-value">
                      <span
                        className="stat-tooltip-wrap"
                        data-tooltip={`${nf(product.price_coin)} COIN`}
                        title={`${nf(product.price_coin)} COIN`}
                      >
                        {formatCompactNumber(product.price_coin).display}
                        <span className="product-price-unit">COIN</span>
                      </span>
                    </div>
                  </div>

                  <button
                    className="btn-primary"
                    style={{
                      padding: "8px 16px",
                      fontSize: 13,
                      background: isLowBalance
                        ? "var(--border-strong)"
                        : "var(--accent)",
                      color: isLowBalance ? "var(--text-tertiary)" : "#fff",
                      cursor: isLowBalance ? "not-allowed" : "pointer",
                      boxShadow: isLowBalance ? "none" : undefined,
                    }}
                    disabled={!player || isLowBalance || isBuyingThis}
                    onClick={() => openConfirm(product)}
                  >
                    {!player ? "Login to Buy" : "Purchase"}
                  </button>
                </div>
              </div>
            );
          })}
      </div>

      <ConfirmModal
        isOpen={!!confirmProduct}
        onClose={() => setConfirmProduct(null)}
        onConfirm={() => handleBuy(confirmProduct)}
        title="Confirm Purchase"
        confirmText="Confirm & Buy"
        loading={buyingId === confirmProduct?.product_id}
        size="md"
      >
        {confirmProduct && (
          <div>
            <p style={{ margin: "0 0 16px", color: "var(--text-secondary)", fontSize: 13.5 }}>
              Are you sure you want to purchase <strong>{confirmProduct.name}</strong> for{" "}
              <strong style={{ color: "var(--text)" }}>{nf(confirmProduct.price_coin)} COIN</strong>?
            </p>

            <div className="product-items-box" style={{ marginBottom: 16 }}>
              <div className="product-items-label">Package Contents</div>
              {(confirmProduct.items || []).map((it, idx) => (
                <div key={idx} className="product-item-row">
                  <span className="product-item-name">{it.name || it.material}</span>
                  <span className="product-item-qty">x{it.amount}</span>
                </div>
              ))}
            </div>

            <div
              style={{
                background: "var(--surface-muted)",
                border: "1px solid var(--border)",
                borderRadius: "var(--radius-sm)",
                padding: "12px 14px",
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                fontSize: 13,
              }}
            >
              <span style={{ color: "var(--text-secondary)" }}>Wallet Balance After Purchase:</span>
              <span style={{ fontWeight: 700, color: "var(--text)" }}>
                {nf(Math.max(0, playerCoin - confirmProduct.price_coin))} COIN
              </span>
            </div>

            <p style={{ margin: "14px 0 0", fontSize: 12, color: "var(--text-tertiary)" }}>
              * Purchased items are immediately queued in PostgreSQL for delivery. Run{" "}
              <code style={{ background: "var(--surface-hover)", padding: "2px 4px", borderRadius: 4 }}>
                /grants
              </code>{" "}
              in Minecraft to claim into inventory.
            </p>
          </div>
        )}
      </ConfirmModal>
    </>
  );
}
