"use client";

import Modal from "@/components/Modal";
import { Loader2 } from "lucide-react";

export default function ConfirmModal({
  isOpen,
  onClose,
  onConfirm,
  title = "Confirm Action",
  message,
  confirmText = "Confirm",
  cancelText = "Cancel",
  variant = "primary",
  loading = false,
  size = "sm",
  children,
}) {
  const getConfirmBtnStyle = () => {
    if (variant === "danger") return { background: "var(--danger)", borderColor: "var(--danger)" };
    if (variant === "warning") return { background: "var(--warning)", borderColor: "var(--warning)" };
    return {};
  };

  const footer = (
    <>
      <button
        type="button"
        className="btn-secondary"
        onClick={onClose}
        disabled={loading}
      >
        {cancelText}
      </button>
      <button
        type="button"
        className="btn-primary"
        style={getConfirmBtnStyle()}
        onClick={onConfirm}
        disabled={loading}
      >
        {loading && <Loader2 size={15} className="spin" style={{ animation: "spin 1s linear infinite" }} />}
        {loading ? "Processing..." : confirmText}
      </button>
    </>
  );

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={title} size={size} footer={footer}>
      {message && (
        <p style={{ margin: "0 0 16px", color: "var(--text-secondary)", fontSize: 13.5, lineHeight: 1.5 }}>
          {message}
        </p>
      )}
      {children}
    </Modal>
  );
}
