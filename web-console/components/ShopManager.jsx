"use client";

import { useState, useEffect } from "react";
import { Plus, Edit2, Trash2, Globe, EyeOff, Package, ShoppingCart } from "lucide-react";
import { useToast } from "@/components/Toast";
import { nf } from "@/lib/format";
import ShopProductModal from "./modals/ShopProductModal";

export default function ShopManager() {
  const toast = useToast();
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editingProduct, setEditingProduct] = useState(null);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [togglingId, setTogglingId] = useState(null);

  const fetchProducts = async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/admin/shop");
      const json = await res.json();
      if (res.ok && json.data) {
        setProducts(json.data);
      }
    } catch (err) {
      toast.error("Failed to load shop products", "Error");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchProducts();
  }, []);

  const handleToggleRelease = async (product) => {
    const nextState = !product.is_active;
    setTogglingId(product.product_id);
    try {
      const res = await fetch("/api/admin/shop/release", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          product_id: product.product_id,
          is_active: nextState,
        }),
      });
      const json = await res.json();
      if (res.ok && json.success) {
        toast.success(
          nextState
            ? `"${product.name}" is now Live on Web Shop!`
            : `"${product.name}" changed to Draft status.`,
          nextState ? "Released" : "Draft"
        );
        setProducts(products.map((p) => (p.product_id === product.product_id ? { ...p, is_active: nextState } : p)));
      } else {
        toast.error(json.error || "Failed to update release status", "Error");
      }
    } catch (err) {
      toast.error(err.message, "Network Error");
    } finally {
      setTogglingId(null);
    }
  };

  const handleDelete = async (product) => {
    if (!confirm(`Are you sure you want to delete package "${product.name}"?`)) {
      return;
    }

    try {
      const res = await fetch(`/api/admin/shop/${product.product_id}`, {
        method: "DELETE",
      });
      const json = await res.json();
      if (res.ok && json.success) {
        toast.success(`Deleted "${product.name}"`, "Success");
        setProducts(products.filter((p) => p.product_id !== product.product_id));
      } else {
        toast.error(json.error || "Failed to delete product", "Error");
      }
    } catch (err) {
      toast.error(err.message, "Network Error");
    }
  };

  const handleOpenCreate = () => {
    setEditingProduct(null);
    setIsModalOpen(true);
  };

  const handleOpenEdit = (product) => {
    setEditingProduct(product);
    setIsModalOpen(true);
  };

  return (
    <div>
      <div className="toolbar" style={{ marginTop: 0 }}>
        <div>
          <span style={{ fontSize: 13, color: "var(--text-secondary)" }}>
            Total Packages: <strong>{products.length}</strong> (
            {products.filter((p) => p.is_active).length} Released / Live,{" "}
            {products.filter((p) => !p.is_active).length} Draft)
          </span>
        </div>
        <button
          className="btn-primary"
          style={{ display: "inline-flex", alignItems: "center", gap: 6 }}
          onClick={handleOpenCreate}
        >
          <Plus size={16} />
          Add Shop Package
        </button>
      </div>

      <div
        className="table-container"
        style={{
          background: "var(--surface)",
          border: "1px solid var(--border)",
          borderRadius: "var(--radius)",
          overflow: "hidden",
        }}
      >
        <table className="data-table" style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: "var(--surface-subtle)", borderBottom: "1px solid var(--border)", textAlign: "left" }}>
              <th style={{ padding: "12px 16px", fontSize: 12, textTransform: "uppercase", letterSpacing: "0.05em" }}>Status</th>
              <th style={{ padding: "12px 16px", fontSize: 12, textTransform: "uppercase", letterSpacing: "0.05em" }}>Package Name</th>
              <th style={{ padding: "12px 16px", fontSize: 12, textTransform: "uppercase", letterSpacing: "0.05em" }}>Price (COIN)</th>
              <th style={{ padding: "12px 16px", fontSize: 12, textTransform: "uppercase", letterSpacing: "0.05em" }}>Items Included</th>
              <th style={{ padding: "12px 16px", fontSize: 12, textTransform: "uppercase", letterSpacing: "0.05em", textAlign: "right" }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={5} style={{ padding: 32, textAlign: "center", color: "var(--text-tertiary)" }}>
                  Loading shop products...
                </td>
              </tr>
            )}

            {!loading && products.length === 0 && (
              <tr>
                <td colSpan={5} style={{ padding: 48, textAlign: "center", color: "var(--text-tertiary)" }}>
                  <ShoppingCart size={32} style={{ opacity: 0.4, marginBottom: 8 }} />
                  <div>No shop packages created yet. Click "Add Shop Package" to create one.</div>
                </td>
              </tr>
            )}

            {!loading &&
              products.map((product) => {
                const items = Array.isArray(product.items) ? product.items : [];
                const isToggling = togglingId === product.product_id;

                return (
                  <tr
                    key={product.product_id}
                    style={{
                      borderBottom: "1px solid var(--border-subtle, var(--border))",
                      transition: "background 0.15s",
                    }}
                  >
                    <td style={{ padding: "12px 16px" }}>
                      {product.is_active ? (
                        <span
                          className="status-badge online"
                          style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: 5,
                            fontSize: 11.5,
                            fontWeight: 600,
                          }}
                        >
                          <Globe size={12} />
                          Live on Shop
                        </span>
                      ) : (
                        <span
                          className="status-badge"
                          style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: 5,
                            fontSize: 11.5,
                            fontWeight: 600,
                            background: "var(--surface-hover)",
                            color: "var(--text-secondary)",
                          }}
                        >
                          <EyeOff size={12} />
                          Draft
                        </span>
                      )}
                    </td>

                    <td style={{ padding: "12px 16px" }}>
                      <div style={{ fontWeight: 600, color: "var(--text)" }}>{product.name}</div>
                      {product.description && (
                        <div
                          style={{
                            fontSize: 12,
                            color: "var(--text-tertiary)",
                            maxWidth: 320,
                            whiteSpace: "nowrap",
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                          }}
                        >
                          {product.description}
                        </div>
                      )}
                    </td>

                    <td style={{ padding: "12px 16px", fontWeight: 700 }}>
                      {nf(product.price_coin)} <span style={{ fontSize: 11, color: "var(--text-secondary)", fontWeight: 500 }}>COIN</span>
                    </td>

                    <td style={{ padding: "12px 16px" }}>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, maxWidth: 350 }}>
                        {items.map((it, idx) => (
                          <span
                            key={idx}
                            style={{
                              display: "inline-flex",
                              alignItems: "center",
                              gap: 4,
                              background: "var(--surface-hover)",
                              padding: "2px 8px",
                              borderRadius: 4,
                              fontSize: 12,
                            }}
                          >
                            <Package size={11} style={{ opacity: 0.6 }} />
                            <span>{it.display || it.name || it.item_id}</span>
                            <span style={{ fontWeight: 600, color: "var(--accent)" }}>x{it.amount}</span>
                          </span>
                        ))}
                      </div>
                    </td>

                    <td style={{ padding: "12px 16px", textAlign: "right" }}>
                      <div style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                        <button
                          type="button"
                          className="btn-secondary"
                          style={{
                            padding: "5px 10px",
                            fontSize: 12,
                            display: "inline-flex",
                            alignItems: "center",
                            gap: 5,
                            borderColor: product.is_active ? "var(--border)" : "var(--accent)",
                            color: product.is_active ? "var(--text-secondary)" : "var(--accent)",
                          }}
                          disabled={isToggling}
                          onClick={() => handleToggleRelease(product)}
                        >
                          {product.is_active ? (
                            <>
                              <EyeOff size={13} />
                              Unrelease
                            </>
                          ) : (
                            <>
                              <Globe size={13} />
                              Release
                            </>
                          )}
                        </button>

                        <button
                          type="button"
                          className="icon-btn"
                          title="Edit Package"
                          onClick={() => handleOpenEdit(product)}
                        >
                          <Edit2 size={16} />
                        </button>

                        <button
                          type="button"
                          className="icon-btn"
                          style={{ color: "var(--danger, #ef4444)" }}
                          title="Delete Package"
                          onClick={() => handleDelete(product)}
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
          </tbody>
        </table>
      </div>

      {isModalOpen && (
        <ShopProductModal
          initialData={editingProduct}
          onClose={() => setIsModalOpen(false)}
          onSaved={() => {
            fetchProducts();
          }}
        />
      )}
    </div>
  );
}
