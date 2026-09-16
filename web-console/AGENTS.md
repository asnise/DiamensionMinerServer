<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

# Base URL rule (must always match)

- This web console is served at `http://localhost:3000` (`next dev` default port).
- The Minecraft plugin's `/profile` command sends players to the profile page via
  `DimensionalMinerCore/src/main/java/com/dimensionalminer/core/dimensionalMinerCore/ProfileCommand.java`.
- The URL there MUST be `http://localhost:3000/profile?id=<uuid>` — nothing else.
- If the console port ever changes, update BOTH the console's run command AND
  `ProfileCommand.java` in the same change. Never change one without the other.

# UI Guidelines: Toast vs Modal (Must Follow)

- **Toast Notifications (`components/Toast.jsx`, `useToast()`)**:
  - Used for transient feedback, operation results (success/error/warning/info), API status, and input validation warnings.
  - Rendered in the top-right corner (`.toast-container`).
  - Auto-dismisses in 4–5 seconds.
  - **Never** use inline alert boxes (`.card` with `border-left` and `Dismiss` buttons) or browser `alert()`.

- **Base Modal (`components/Modal.jsx`)**:
  - Used as an empty container shell for complex forms, deep-dive data inspection, or multi-step workflows.
  - Supports configurable sizing (`size="sm" | "md" | "lg" | "xl" | "full"` or custom width/maxWidth).
  - Handles backdrop click, Escape key, and background scroll locking.

- **ConfirmModal (`components/modals/ConfirmModal.jsx`)**:
  - Built on top of `Modal.jsx`.
  - Used for transactional/currency decisions (e.g. buying packages with COIN in `/shop`), destructive/permanent actions (deletes, bans, resets), or state overwrites.
  - Must display exact item, cost, and destination details.
  - Features loading state on the confirm button.
  - Upon completion, closes and triggers a top-right **Toast**.
