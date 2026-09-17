"use client";

import { useState } from "react";
import Modal from "@/components/Modal";
import ItemSelect from "@/components/ItemSelect";
import { useToast } from "@/components/Toast";
import { Plus, Trash2, Globe, Save } from "lucide-react";

export default function ShopProductModal({ initialData, onClose, onSaved }) {
  const toast = useToast();
  const isEditing = Boolean(initialData?.product_id);

  const [name, setName] = useState(initialData?.name || "");
  const [description, setDescription] = useState(initialData?.description || "");
  const [priceCoin, setPriceCoin] = useState(initialData?.price_coin !== undefined ? String(initialData.price_coin) : "100");
  const [items, setItems] = useState(
    Array.isArray(initialData?.items) && initialData.items.length > 0
      ? initialData.items.map(it => ({
          item_id: it.item_id || it.material || "",
          display: it.display || it.name || "",
          amount: it.amount !== undefined ? Number(it.amount) : 1,
        }))
      : [{ item_id: "", display: "", amount: 1 }]
  );
  const [savingAction, setSavingAction] = useState(null); // "draft" | "release"

  const handleAddItem = () => {
    setItems([...items, { item_id: "", display: "", amount: 1 }]);
  };

  const handleRemoveItem = (idx) => {
    setItems(items.filter((_, i) => i !== idx));
  };

  const handleItemChange = (idx, field, val) => {
    const updated = [...items];
    updated[idx] = { ...updated[idx], [field]: val };
    setItems(updated);
  };

  const handleSubmit = async (releaseDirectly) => {
    if (!name.trim()) {
      toast.warning("Product name is required", "Missing Name");
      return;
    }

    const price = Number(priceCoin);
    if (isNaN(price) || price < 0) {
      toast.warning("Please enter a valid price in COIN", "Invalid Price");
      return;
    }

    const cleanItems = items
      .filter((it) => it.item_id && it.item_id.trim())
      .map((it) => ({
        item_id: it.item_id.trim(),
        name: it.display?.trim() || it.item_id.trim(),
        display: it.display?.trim() || it.item_id.trim(),
        amount: Math.max(1, Number(it.amount) || 1),
      }));

    if (cleanItems.length === 0) {
      toast.warning("Please add at least one valid item to this package", "Missing Items");
      return;
    }

    setSavingAction(releaseDirectly ? "release" : "draft");

    try {
      const res = await fetch("/api/admin/shop", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          product_id: initialData?.product_id || undefined,
          name: name.trim(),
          description: description.trim(),
          price_coin: price,
          items: cleanItems,
          is_active: releaseDirectly ? true : false,
        }),
      });

      const json = await res.json();
      if (res.ok && json.success) {
        if (releaseDirectly) {
          toast.success(
            `"${name.trim()}" is now published and live on the Web Shop!`,
            "Released to Shop"
          );
        } else {
          toast.success(
            `"${name.trim()}" saved to Database as Draft.`,
            "Saved to Database"
          );
        }
        if (onSaved) onSaved(json.product);
        onClose();
      } else {
        toast.error(json.error || "Failed to save shop product", "Error");
      }
    } catch (err) {
      toast.error(err.message, "Network Error");
    } finally {
      setSavingAction(null);
    }
  };

  return (
    <Modal
      isOpen={true}
      title={isEditing ? `Edit Shop Product: ${initialData.name}` : "Create Shop Product"}
      onClose={onClose}
      width={640}
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div>
          <label className="form-label" style={{ fontWeight: 600, display: "block", marginBottom: 6 }}>
            Product Name *
          </label>
          <input
            type="text"
            className="search-input"
            style={{ width: "100%", padding: "10px 12px" }}
            placeholder="e.g. Master Miner Starter Bundle"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div>
          <label className="form-label" style={{ fontWeight: 600, display: "block", marginBottom: 6 }}>
            Description
          </label>
          <textarea
            className="search-input"
            rows={3}
            style={{ width: "100%", padding: "10px 12px", resize: "vertical" }}
            placeholder="Describe what players receive and any special details..."
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>

        <div>
          <label className="form-label" style={{ fontWeight: 600, display: "block", marginBottom: 6 }}>
            Price (COIN) *
          </label>
          <input
            type="number"
            min={0}
            className="search-input"
            style={{ width: "100%", padding: "10px 12px" }}
            value={priceCoin}
            onChange={(e) => setPriceCoin(e.target.value)}
          />
        </div>

        <div style={{ borderTop: "1px solid var(--border)", paddingTop: 16 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
            <div>
              <span style={{ fontWeight: 600, fontSize: 14 }}>Package Contents / Items</span>
              <p style={{ margin: "2px 0 0", fontSize: 12, color: "var(--text-tertiary)" }}>
                Items automatically granted to player mailbox (/grants) upon purchase
              </p>
            </div>
            <button
              type="button"
              className="btn-secondary"
              style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12, padding: "6px 10px" }}
              onClick={handleAddItem}
            >
              <Plus size={14} />
              Add Item
            </button>
          </div>

          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {items.map((item, idx) => (
              <div
                key={idx}
                style={{
                  display: "grid",
                  gridTemplateColumns: "1.5fr 1.2fr 80px 36px",
                  gap: 8,
                  alignItems: "center",
                  background: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "var(--radius-sm)",
                  padding: "8px 10px",
                }}
              >
                <div>
                  <ItemSelect
                    value={item.item_id}
                    placeholder="Select item ID..."
                    onChange={(newId) => {
                      handleItemChange(idx, "item_id", newId);
                      if (!item.display) {
                        handleItemChange(idx, "display", newId);
                      }
                    }}
                  />
                </div>

                <div>
                  <input
                    type="text"
                    className="search-input"
                    placeholder="Display name"
                    style={{ width: "100%", padding: "6px 10px", fontSize: 13 }}
                    value={item.display}
                    onChange={(e) => handleItemChange(idx, "display", e.target.value)}
                  />
                </div>

                <div>
                  <input
                    type="number"
                    min={1}
                    className="search-input"
                    placeholder="Qty"
                    style={{ width: "100%", padding: "6px 8px", fontSize: 13, textAlign: "center" }}
                    value={item.amount}
                    onChange={(e) => handleItemChange(idx, "amount", e.target.value)}
                  />
                </div>

                <div style={{ textAlign: "center" }}>
                  <button
                    type="button"
                    className="icon-btn"
                    style={{ color: "var(--danger, #ef4444)" }}
                    onClick={() => handleRemoveItem(idx)}
                    title="Remove item"
                    disabled={items.length <= 1}
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div
          style={{
            borderTop: "1px solid var(--border)",
            paddingTop: 16,
            marginTop: 8,
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            flexWrap: "wrap",
            gap: 12,
          }}
        >
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>

          <div style={{ display: "flex", gap: 10 }}>
            <button
              type="button"
              className="btn-secondary"
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                fontWeight: 600,
              }}
              disabled={savingAction !== null}
              onClick={() => handleSubmit(false)}
            >
              <Save size={15} />
              {savingAction === "draft" ? "Saving..." : "บันทึก (Save to Database)"}
            </button>

            <button
              type="button"
              className="btn-primary"
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                fontWeight: 600,
                background: "var(--accent)",
              }}
              disabled={savingAction !== null}
              onClick={() => handleSubmit(true)}
            >
              <Globe size={15} />
              {savingAction === "release" ? "Releasing..." : "Release (โพสต์ขึ้น Shop)"}
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
