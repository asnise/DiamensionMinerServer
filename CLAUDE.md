# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Backend and tooling for **Dimensional Miner**, a gear/mining/instance-dungeon MMORPG on **Minecraft Java (Paper 26.2, Java 25)**. Most project docs (`README.md`, `docs/`, `database/README.md`, `Task.md`, `HANDOVER_EXEA_CORE.md`) are written in Thai.

Three code units share one repo:

| Unit | Stack | Role |
|---|---|---|
| `DimensionalMinerCore/` | Paper plugin, Gradle (Kotlin DSL), Java 25 | Game server logic |
| `EXeaEntityCore/` | Paper plugin, Gradle, Java 25, JOML | Standalone animated 3D entity engine (no dependency on DimensionalMinerCore) |
| `web-console/` | Next.js 16 (App Router, JS/JSX), React 19, Tailwind 4, `pg` | Player profile/shop + admin content editor |

`server/` (local Paper runtime) and `Map/` are git-ignored.

## Commands

Use PowerShell, not `cmd` / `cmd /c` (see `.agents/rules/terminal.md`).

```powershell
# Plugins: the jar task writes straight into ../server/plugins/<Name>.jar
cd DimensionalMinerCore; .\gradlew.bat jar
cd EXeaEntityCore;       .\gradlew.bat jar
# Stop the Minecraft server first if the jar is locked.
# DimensionalMinerCore also has run-paper: .\gradlew.bat runServer (MC 26.2)

# Web console (shortcut from repo root: run-web.bat = dev, run-web.bat prod = build + start;
# it checks .env.local / SESSION_SECRET / port 3000 and runs npm install if needed)
cd web-console
npm install
npm run dev          # http://localhost:3000
npm run build
npm run lint         # eslint (flat config, eslint-config-next)

# Database bootstrap (creates both DBs + seed data)
cd web-console; node scripts/init_databases.mjs              # local Postgres
cd web-console; node --env-file=.env.local scripts/init_neon.mjs   # Neon cloud
# Load one content definition file into the content DB + both content_registry.json copies (then /dm reload):
cd web-console; node --env-file=.env.local scripts/load_content_file.mjs ../database/test-content/dungeon_test_training.json
# DB credentials live only in web-console/.env.local (LOCAL_PG* / CLOUD_PG*); lib/db.js throws if missing.

# Switch DB target for server config + web-console .env.local
.\switch-db.ps1 local   # or: cloud ; no arg toggles

# Multiplayer testing alone: Mineflayer bots that auto-accept party invites (see tools/party-bots/README.md).
# Needs the server in offline mode (+ ViaVersion/ViaBackwards while Mineflayer lags the server version):
.\switch-online-mode.ps1 offline   # or: online ; restart the server after switching
cd tools\party-bots; npm install; node bots.mjs --count 2

# World building (in game, admin): /dmb wand|fill|replace|ores|preview|run|undo|save — blueprints are JSON
# in server/plugins/DimensionalMinerCore/blueprints/ (format: tools/blueprints/README.md). All worlds have
# autoSave=false, so /dmb saves what it changes; edits in the hub are mirrored to every channel world.

# EXea model export from Blockbench file
node EXeaEntityCore\tools\exea_exporter.js "<path>.bbmodel" <entity_id>
```

There is no automated test suite in any unit. Verification is in-game (`/reload confirm`, the plugin commands in `README.md`) or through the web console.

## Architecture

### Two separate PostgreSQL databases

- **`dimensional_content`** (`database/schema_content.sql`): versioned, design-time definitions in `content_definitions` (`definition_id`, `version`, `kind`, JSONB `payload`, `checksum`). Kinds: `ItemBase`, `Recipe`, `LootTable`, `Dungeon`, `PassiveGenerator`, `UpgradeTree`. Holds no player data.
- **`dimensional_game`** (`database/schema_game.sql`): runtime state. This includes identity (`user_accounts` ↔ `player_identities` for MINECRAFT/DISCORD/WEB, so the Minecraft UUID is **not** the primary player key), wallets with a double-entry ledger, items/material stacks/asset locations, parties, instance slots/runs, `reward_grants` (mailbox), `web_login_keys`, `server_shop_products`, and operations/outbox.

The Paper plugin connects **only** to `dimensional_game` (HikariCP in `DatabaseManager`). The web console holds both pools: `gamePool` and `contentPool` in `web-console/lib/db.js`.

### Content pipeline

The admin editor (`/admin/editor`, sub-editors in `web-console/components/modals/editors/`) writes to `content_definitions` through `lib/content.js` and `app/api/content/[kind]`. Its export (`app/api/export`) produces `database/content_registry.json`. `ContentRegistryManager` loads that JSON into memory at plugin startup and on `/dm reload`. It probes several paths: the plugin data folder first, then `database/content_registry.json` relative to the server cwd. The plugin never reads the content DB. A content change reaches the game only after you export it and reload.

### Web console → game delivery

The web console never writes to the player's live inventory. Shop purchases (`app/api/shop/buy`) and dungeon rewards insert rows into `reward_grants`. The player then claims them in-game with `/grants` (`GrantsCommand` / `GrantsMenu`). Web login works through an OTP that `/weblogin` creates in `web_login_keys`. Sessions are HMAC-signed httpOnly cookies from `lib/auth.js`, signed with `SESSION_SECRET` in `.env.local`:
- `dm_session` is the player cookie. It is set when the one-time key is redeemed. Server routes get the player from `getSessionPlayerId(request)`; never trust a `player_id` sent by the client. The browser reads the session through `/api/auth/me` (`lib/session.js`).
- `dm_admin` is the admin cookie. It is issued after an `ADMIN_PASSWORD` login, which has no default. `proxy.js` (Next 16's replacement for `middleware.js`) guards `/admin/*` and `/api/admin/*`, and every admin or content handler also calls `requireAdmin(request)`.

`lib/api.js` `fail()` hides 5xx details from clients.

### DimensionalMinerCore runtime

- `DimensionalMinerCore.onEnable` wires everything: DB, then content registry, then listeners, then managers (Party, Channel, DungeonInstance), then commands. Commands are also declared in `src/main/resources/plugin.yml`. HikariCP and the Postgres driver are `compileOnly` and loaded through `plugin.yml` `libraries:`.
- All worlds are treated as stateless: `keepInventory=true` and `autoSave=false`. Persistence lives in the DB. `InventorySync` snapshots online players every 15s and writes only changed slots (full serialized ItemStack in `material_stacks.item_data`). It also saves on quit, channel switch, mailbox claim and shutdown. Per-player loads/saves run in order on its own IO pool, and nothing is saved before the player's load succeeded.
- Shared world-folder copy/delete helpers live in `util/WorldFiles` (blocking I/O, off the main thread). Instance and channel worlds are dimensions of the hub level: `overworld_t0/dimensions/minecraft/<name>`.
- DB work runs in `runTaskAsynchronously`, and Bukkit API calls hop back to the main thread. Follow this pattern.
- Dungeons (`dungeon/`, documented in `docs/instance-dungeon-system.md`):
  1. `DungeonInstanceManager` copies a template world folder (e.g. `ind_t0`, skipping `session.lock`/`uid.dat`) to `<template>_<8-hex sessionId>`.
  2. It loads the copy and teleports the party in.
  3. It drives a `DungeonInstanceSession` through `Dungeon` definition `steps`. Each step has goals (`KILL_MOBS`, `KILL_BOSS`, `HOLD_ZONE`, `SURVIVE_TIME`, `INTERACT_DUMMY`) and `on_start`/`on_current`/`on_end` actions.
  4. On clear, it writes `reward_grants`, returns players to hub `overworld_t0`, unloads the world, and deletes its folder.
- Solo players get an auto-created party on `/dungeon enter`.
- Mining (`mining/`): content kinds `OreType` (block, depleted block, loot table, respawn, pickaxe tier) and `LootTable`. `MineNodeManager` keeps `mine_nodes` (one row per channel world) in memory.
  - Node breaks are cancelled at LOWEST, so map protection still blocks everything else.
  - A break reserves the node, then one transaction records operation `mine/<channel>/<node>/<generation>` and flips READY→DEPLETED for that generation. Loot is given only after commit: inventory first, overflow to `reward_grants`.
  - Respawn uses a time queue that bumps the generation in the DB first. Admin placement is `/mine node add <ore>` while looking at a block.

### EXeaEntityCore

Renders Blockbench models as hierarchies of `ItemDisplay` bones that ride a root driver mob (Husk/ArmorStand). The pieces:
- `core/model/`: loaders.
- `core/animation/`: keyframe interpolation.
- `core/render/DisplayRenderer`: self-healing respawn and `forceSync`.
- `core/manager/EXActiveEntity`: state machine and chunk tickets.

Models are exported as `[id]_bones.json` / `[id]_anims.json` with `tools/blockbench_plugin/exea_entity_exporter.js`, then inspected with `tools/inspector.html`. Test commands: `/exea spawn <id>`, `/exea anim play <name>`, `/exea kill all`. See `HANDOVER_EXEA_CORE.md`.

## Task tracking

`Task.md` is the handoff board (in Thai). Read the row for a task before you start it. When you finish a task:
- Set its status to 🔍 (awaiting user review), never ✅. Only the user marks ✅.
- Fill in the evidence files and how to verify.
- Update the summary counts.
- Add a line at the top of the Handoff Log.

## Rules that must hold

- **Profile URL coupling:** `ProfileCommand.java` links to `http://localhost:3000/profile?id=<uuid>`. If the web-console port changes, update both in the same change.
- **Web console Next.js is v16 with breaking changes.** Read `web-console/node_modules/next/dist/docs/` before writing Next code (see `web-console/AGENTS.md`).
- **Web console UI feedback** (`.agents/rules/ui-guidelines.md`):
  - Never use `alert`/`confirm`/`prompt` or inline dismissible alert cards.
  - Use `useToast()` from `components/Toast.jsx` for results and errors.
  - Use `components/Modal.jsx` for forms and inspection.
  - Use `components/modals/ConfirmModal.jsx` for currency, destructive, or overwrite actions. Flow: ConfirmModal, then loading, then close, then Toast.
  - Use existing CSS variables (`--surface`, `--border`, `--text`, `--accent`, `--success`, `--danger`).
- Imports in web-console use the `@/` alias, which maps to the `web-console/` root.
- **DB profile:** `local` vs `cloud` (Neon) is chosen by `database.profile` in the plugin `config.yml` and by `DB_PROFILE` in `web-console/.env.local`. Use `switch-db.ps1` so the two stay in sync.
