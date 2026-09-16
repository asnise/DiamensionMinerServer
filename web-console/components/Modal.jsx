"use client";

import { useEffect, useCallback } from "react";
import { X } from "lucide-react";

export default function Modal({
  isOpen,
  onClose,
  title,
  header,
  footer,
  children,
  size = "md",
  width,
  maxWidth,
  closeOnBackdrop = true,
  closeOnEsc = true,
  showCloseButton = true,
  className = "",
  containerStyle = {},
}) {
  const handleKeyDown = useCallback(
    (e) => {
      if (closeOnEsc && e.key === "Escape" && isOpen) {
        onClose?.();
      }
    },
    [closeOnEsc, isOpen, onClose]
  );

  useEffect(() => {
    if (!isOpen) return;

    window.addEventListener("keydown", handleKeyDown);
    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = originalOverflow;
    };
  }, [isOpen, handleKeyDown]);

  if (!isOpen) return null;

  const handleBackdropClick = (e) => {
    if (closeOnBackdrop && e.target === e.currentTarget) {
      onClose?.();
    }
  };

  const sizeClass = `modal-size-${size}`;
  const customStyles = {
    ...containerStyle,
    ...(width ? { width } : {}),
    ...(maxWidth ? { maxWidth } : {}),
  };

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick} role="dialog" aria-modal="true">
      <div className={`modal-container ${sizeClass} ${className}`.trim()} style={customStyles}>
        {header ? (
          header
        ) : title || showCloseButton ? (
          <div className="modal-header">
            {title && <h3 className="modal-title">{title}</h3>}
            {showCloseButton && (
              <button
                type="button"
                className="modal-close-btn"
                onClick={onClose}
                aria-label="Close modal"
              >
                <X size={18} />
              </button>
            )}
          </div>
        ) : null}

        <div className="modal-body">{children}</div>

        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}
