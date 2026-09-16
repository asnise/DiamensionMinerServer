# UI Design & Feedback Guidelines: Toast vs Modal

This rule establishes strict UX standards for user feedback, dialogs, and notifications across the `web-console` application. All agents and developers must adhere to these conventions.

---

## 1. Zero Tolerance for Outdated Patterns
- **NO Browser Alerts**: Never use `window.alert()`, `window.confirm()`, or `window.prompt()`.
- **NO Inline Alert Banners**: Do not inject dismissible inline alert cards (`.card` with `border-left` and `Dismiss` buttons) for transient feedback or action results.
- **Unified Design Tokens**: Always use existing CSS variables (`var(--surface)`, `var(--border)`, `var(--text)`, `var(--accent)`, `var(--success)`, `var(--danger)`).

---

## 2. When to Use Toast Notifications (`useToast()`)

**Component**: `import { useToast } from "@/components/Toast";`  
**Location**: Fixed floating container in the **top-right** corner (`.toast-container`).

### Applicable Contexts:
1. **Action Results & Receipts**:
   - Feedback after a mutation succeeds (e.g., `"Purchase Successful (Grant ID: ...)"`, `"Changes Saved"`, `"Export Complete"`).
2. **Input Validation Errors**:
   - Client-side validation failures (e.g., `"Please enter a valid UUID format"`).
3. **API & Network Error Messages**:
   - Failed requests that do not require navigation away (e.g., `"Purchase Failed: Insufficient COIN"`, `"Failed to save content definition"`).
4. **Session & State Updates**:
   - Transient announcements (e.g., `"Player UUID connected successfully"`, `"Logged out from shop session"`).

### Toast Usage Example:
```jsx
const toast = useToast();

// Success
toast.success("Items sent to in-game mailbox (/grants).", "Purchase Successful");

// Error
toast.error("Invalid UUID format entered.", "Validation Error");

// Warning & Info
toast.warning("Login required to purchase items.", "Authentication Required");
toast.info("Session disconnected.", "Notice");
```

---

## 3. When to Use Base Modal (`Modal.jsx`)

**Component**: `import Modal from "@/components/Modal";`  
**Characteristics**: Empty container shell where arbitrary HTML/components are rendered. Supports adjustable sizes (`sm`, `md`, `lg`, `xl`, `full` or custom `width`/`maxWidth`).

### Applicable Contexts:
1. **Forms & Content Editors**:
   - Complex entity editors (e.g., `EditorModal` for item definitions, loot tables, crafting recipes).
2. **Deep-Dive Data Inspection**:
   - Multi-field JSON viewers, raw payloads, or manifest audit logs that would clutter the main screen.
3. **Multi-Step Workflows**:
   - Configurations requiring form inputs, tabs, or custom controls before saving.

### Sizing Convention:
- `size="sm"` (440px): Small forms, quick dialogs.
- `size="md"` (540px, default): Standard forms, single-column inputs.
- `size="lg"` (720px): Multi-column editors, code snippets, table summaries.
- `size="xl"` (900px) / `size="full"`: Full schema editors, comprehensive JSON/payload view.

---

## 4. When to Use ConfirmModal (`ConfirmModal.jsx`)

**Component**: `import ConfirmModal from "@/components/modals/ConfirmModal";`  
**Characteristics**: Built directly on `Modal.jsx`. Standardizes confirmation with title, message, variant button, and async loading state.

### Applicable Contexts:
1. **Transactional / Currency Operations**:
   - Deducting currency (e.g., purchasing packages with COIN in `/shop`).
   - Transferring assets or claiming limited offers.
2. **Destructive / Permanent Actions**:
   - Deleting definitions, dropping database records, banning players, resetting stats.
3. **Overwrites & Bulk Operations**:
   - Overwriting existing registry entries or bulk import replacements.

### ConfirmModal Usage Example:
```jsx
<ConfirmModal
  isOpen={!!selectedProduct}
  onClose={() => setSelectedProduct(null)}
  onConfirm={() => handleBuy(selectedProduct)}
  title="Confirm Purchase"
  confirmText="Confirm & Buy"
  variant="primary" // "primary" | "danger" | "warning"
  loading={isPurchasing}
  size="md"
>
  <p>Are you sure you want to buy {selectedProduct?.name} for {selectedProduct?.price} COIN?</p>
</ConfirmModal>
```

---

## 5. Typical Flow (ConfirmModal -> Action -> Toast)
When executing an impactful action (e.g. Shop purchase, deletion):
1. User clicks action button -> **ConfirmModal** opens displaying exact cost/impact.
2. User confirms -> Button enters `loading={true}`.
3. Action completes -> **ConfirmModal** closes.
4. **Toast** fires in the top-right displaying receipt or error message.
