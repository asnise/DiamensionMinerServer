import express from 'express';
import pg from 'pg';
import { randomUUID } from 'node:crypto';

const POOL = new pg.Pool({
  host: process.env.PGHOST || 'localhost',
  port: +(process.env.PGPORT || 5432),
  database: process.env.PGDATABASE || 'dimensional_miner',
  user: process.env.PGUSER || 'postgres',
  password: process.env.PGPASSWORD || '123456',
  max: 10,
});

const PORT = +(process.env.PORT || 3000);
const BASE_URL = process.env.BASE_URL || `http://localhost:${PORT}`;

const app = express();
app.disable('x-powered-by');
app.use(express.json());
app.use('/style.css', express.static('public/style.css', { maxAge: '1h', immutable: false }));
app.use('/static', express.static('public', { maxAge: '1h' }));

const route = (fn) => async (req, res) => {
  try {
    const out = await fn(req, res);
    if (!res.headersSent && out !== undefined) res.send(out);
  } catch (e) {
    console.error(e);
    if (res.headersSent) return;
    res.status(500).send(layout('Server Error', '', '', `DB error: ${esc(e.message)}`));
  }
};

const esc = (s) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const nf = (n) => Number(n ?? 0).toLocaleString('en-US');

const ICON_FALLBACK = {
  pickaxe_void: 'netherite_pickaxe',
  helmet_crystal: 'netherite_helmet',
  void_pickaxe: 'netherite_pickaxe',
  void_sword: 'netherite_sword',
  void_ore: 'deepslate_redstone_ore',
  crystal_fragment: 'amethyst_shard',
  mending_tablet: 'honeycomb',
  heal_potion: 'golden_apple',
};

const iconName = (defId, payload) =>
  (payload && payload.icon) || ICON_FALLBACK[defId] || 'barrier';

const itemIcon = (defId, payload, size = 26) =>
  `https://api.mineatar.io/item/${encodeURIComponent(iconName(defId, payload))}?size=${size}`;

const headUrl = (username, size = 34) =>
  `https://minotar.net/helm/${encodeURIComponent(username)}/${size}.png`;

const onerror = 'this.style.visibility="hidden"';

const KIND_ORDER = (k) =>
  ({ EQUIPPED: 0, INVENTORY: 1, STASH: 2, MARKET_ESCROW: 3, JOB_ESCROW: 4, GRANT: 5 }[k] ?? 9);

function layout(title, active, body, error) {
  return `<!doctype html>
<html lang="th"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)} — Dimensional Miner</title>
<link rel="stylesheet" href="/style.css"></head>
<body>
<header class="topbar"><nav>
  <a class="brand" href="/">Dimensional Miner · Console</a>
  <a href="/">Players</a><a href="/#servers">Servers</a><a href="/#mining">Mining</a><a href="/#world-boss">World Boss</a>
</nav></header>
<main>${error ? `<p class="badge err">${esc(error)}</p>` : ''}${body}</main>
<footer>read-only demo console · data from PostgreSQL (dimensional_miner)</footer>
</body></html>`;
}

function emptyRow(colspan, text) {
  return `<tr class="empty"><td colspan="${colspan}">${esc(text)}</td></tr>`;
}

// ---------- overview ----------

async function overview(ctx) {
  const [nodes, stats, players, mines, encounters] = await Promise.all([
    POOL.query(`SELECT node_id, role, status, heartbeat_at, capacity FROM nodes ORDER BY role, node_id`),
    POOL.query(`SELECT
        (SELECT count(*) FROM players) AS total_players,
        (SELECT count(*) FROM players WHERE status='ACTIVE') AS active_players,
        (SELECT count(*) FROM player_sessions WHERE state='ACTIVE') AS online_sessions,
        (SELECT coalesce(sum(balance),0) FROM accounts WHERE owner_id IS NOT NULL) AS player_coin,
        (SELECT count(*) FROM items) AS items,
        (SELECT count(*) FROM material_stacks) AS stacks,
        (SELECT count(*) FROM reward_grants WHERE state='AVAILABLE') AS grants_open,
        (SELECT count(*) FROM mine_nodes WHERE state='DEPLETED') AS nodes_respawning`),
    POOL.query(`SELECT p.player_id, p.username, p.status, p.created_at,
        ps.node_id, ps.state AS session_state, ps.epoch,
        coalesce(jsonb_object_agg(a.currency, a.balance) FILTER (WHERE a.currency IS NOT NULL),'{}') AS wallets,
        (SELECT count(*) FROM asset_locations l WHERE l.owner_id=p.player_id AND l.kind='EQUIPPED') AS equipped
      FROM players p
      LEFT JOIN player_sessions ps USING (player_id)
      LEFT JOIN accounts a ON a.owner_id=p.player_id
      GROUP BY p.player_id, p.username, p.status, p.created_at, ps.node_id, ps.state, ps.epoch
      ORDER BY p.created_at`),
    POOL.query(`SELECT channel_id, node_id, dimension_id, block_x, block_y, block_z,
        generation, state, respawn_at, ore_definition_id
      FROM mine_nodes ORDER BY channel_id, node_id`),
    POOL.query(`SELECT e.encounter_id, e.channel_id, e.boss_type, e.schedule_window, e.state, e.last_hit_player_id, e.outcome,
        p.username AS last_hit_username
      FROM world_encounters e LEFT JOIN players p ON p.player_id=e.last_hit_player_id
      ORDER BY e.schedule_window DESC`),
  ]);

  const s = stats.rows[0] ?? {};
  const headCell = (name) =>
    `<img class="head" src="${headUrl(name)}" onerror="${onerror}" alt=""/>`;
  const statusDot = (st) =>
    st === 'ACTIVE' || st === 'READY' || st === 'ONLINE'
      ? '<span class="dot online"></span>'
      : st === 'OFFLINE' || st === 'DRAINING'
        ? '<span class="dot offline"></span>'
        : '<span class="dot active"></span>';

  const playerRows = players.rows.length
    ? players.rows.map((p) => {
        const w = p.wallets || {};
        return `<tr>
          <td>${headCell(p.username)} <a class="player" href="/player/${p.player_id}">${esc(p.username)}</a></td>
          <td>${statusDot(p.status)}${esc(p.status)}</td>
          <td>${esc(p.session_state ?? '—')}${p.epoch ? ` · e${p.epoch}` : ''}${p.node_id ? ` @ ${esc(p.node_id)}` : ''}</td>
          <td class="num">${nf(w.COIN)}</td>
          <td class="num">${nf(w.ENERGY)}</td>
          <td class="num">${nf(w.SERVER_XP)}</td>
          <td class="num">${nf(p.equipped)}</td>
          <td>${new Date(p.created_at).toLocaleDateString('en-CA')}</td>
        </tr>`;
      }).join('')
    : emptyRow(8, 'ยังไม่มีผู้เล่น');

  const serverRows = nodes.rows.length
    ? nodes.rows.map((n) => {
        const cap = (n.capacity && n.capacity.players) != null ? ` / ${n.capacity.players}` : '';
        return `<tr>
          <td>${statusDot(n.status)} <code>${esc(n.node_id)}</code></td>
          <td><span class="badge">${esc(n.role)}</span></td>
          <td>
            ${n.role === 'MAIN'
              ? `<code>paper</code> · hub + mining + workspace`
              : n.role === 'INSTANCE'
                ? `<code>paper</code> · boss worlds`
                : n.role === 'PROXY'
                  ? `<code>velocity</code> · routing`
                  : `workers`}
          </td>
          <td><span class="badge ${n.status === 'READY' ? 'ok' : n.status === 'DRAINING' ? 'warn' : 'err'}">${esc(n.status)}</span></td>
          <td class="num">${new Date(n.heartbeat_at).toLocaleTimeString()}</td>
        </tr>`;
      }).join('')
    : emptyRow(5, 'ไม่พบ node');

  const mineRows = mines.rows.length
    ? mines.rows.map((m) => `<tr>
        <td><code>${esc(m.node_id)}</code></td>
        <td><code>${esc(m.channel_id)}</code></td>
        <td>${esc(m.dimension_id)} · ${m.block_x},${m.block_y},${m.block_z}</td>
        <td class="num">${nf(m.generation)}</td>
        <td><span class="badge ${m.state === 'READY' ? 'ok' : 'warn'}">${esc(m.state)}</span></td>
        <td>${m.respawn_at ? new Date(m.respawn_at).toLocaleTimeString() : '—'}</td>
        <td>${esc(m.ore_definition_id)}</td>
      </tr>`).join('')
    : emptyRow(7, 'ไม่พบ mine node');

  const encounterRows = encounters.rows.length
    ? encounters.rows.map((e) => `<tr>
        <td><code>${esc(e.boss_type)}</code></td>
        <td><code>${esc(e.channel_id)}</code></td>
        <td>${esc(e.schedule_window)}</td>
        <td><span class="badge ${e.state === 'FINISHED' ? 'ok' : e.state === 'ACTIVE' ? 'warn' : 'err'}">${esc(e.state)}</span></td>
        <td>${e.last_hit_username ? `${headCell(e.last_hit_username)} ${esc(e.last_hit_username)}` : '—'}</td>
        <td><pre class="json">${esc(JSON.stringify(e.outcome ?? {}))}</pre></td>
      </tr>`).join('')
    : emptyRow(6, 'ยังไม่มี encounter');

  // contributions for latest encounter
  let contribHtml = '';
  if (encounters.rows.length) {
    const last = encounters.rows[0];
    const c = await POOL.query(
      `SELECT c.damage_units, c.support_units, c.active_ms, p.username
       FROM encounter_contributions c JOIN players p ON p.player_id=c.player_id
       WHERE c.encounter_id=$1 ORDER BY c.damage_units DESC`,
      [last.encounter_id]
    );
    contribHtml = c.rows.length
      ? `<h2 id="world-boss">World Boss · ผลงานล่าสุด (${esc(last.boss_type)})</h2>
         <table><thead><tr><th>Player</th><th class="num">Damage</th><th class="num">Support</th><th class="num">Active</th></tr></thead><tbody>
         ${c.rows.map((r) => `<tr>
             <td>${headCell(r.username)} ${esc(r.username)}</td>
             <td class="num">${nf(r.damage_units)}</td>
             <td class="num">${nf(r.support_units)}</td>
             <td class="num">${nf(Math.round(r.active_ms / 60000))} min</td></tr>`).join('')}
         </tbody></table>`
      : '';
  }

  return layout(
    'Overview',
    'overview',
    `<h1>Overview</h1>
     <div class="stat-grid">
       <div><div class="k">Players</div><div class="v">${nf(s.total_players)} <span style="font-size:13px;color:var(--muted)">(${nf(s.active_players)} active)</span></div></div>
       <div><div class="k">Online sessions</div><div class="v">${nf(s.online_sessions)}</div></div>
       <div><div class="k">Player-held COIN</div><div class="v">${nf(s.player_coin)}</div></div>
       <div><div class="k">Items / stacks</div><div class="v">${nf(s.items)} / ${nf(s.stacks)}</div></div>
       <div><div class="k">Mail unclaimed</div><div class="v">${nf(s.grants_open)}</div></div>
       <div><div class="k">Mine nodes respawning</div><div class="v">${nf(s.nodes_respawning)}</div></div>
     </div>

     <h2>Players</h2>
     <table><thead><tr>
       <th>Player</th><th>Status</th><th>Session</th>
       <th class="num">COIN</th><th class="num">Energy</th><th class="num">Server XP</th>
       <th class="num">Equipped</th><th>Joined</th>
     </tr></thead><tbody>${playerRows}</tbody></table>

     <h2 id="servers">Servers &amp; Nodes</h2>
     <table><thead><tr><th>Node</th><th>Role</th><th>Runtime</th><th>Status</th><th class="num">Heartbeat</th></tr></thead>
     <tbody>${serverRows}</tbody></table>

     <h2 id="mining">Mine Nodes</h2>
     <table><thead><tr><th>Node</th><th>Channel</th><th>Position</th><th class="num">Gen</th><th>State</th><th>Respawn</th><th>Ore</th></tr></thead>
     <tbody>${mineRows}</tbody></table>

     <h2>World Encounters</h2>
     <table><thead><tr><th>Boss</th><th>Channel</th><th>Window</th><th>State</th><th>Last hit</th><th>Outcome</th></tr></thead>
     <tbody>${encounterRows}</tbody></table>
     ${contribHtml}`
  );
}

// ---------- player profile ----------

async function findPlayer(id) {
  const r = await POOL.query(
    `SELECT player_id, username, status, created_at
       FROM players WHERE player_id::text = $1 OR username = $1`,
    [id]
  );
  return r.rows[0] ?? null;
}

async function profile(ctx) {
  const pid = ctx.params.id;
  const player = await findPlayer(pid);
  if (!player) {
    ctx.status(404);
    return layout('ไม่พบผู้เล่น', 'players', `<p class="badge err">ไม่พบผู้เล่น</p>`, 'player not found');
  }

  const [session, wallets, items, stacks, quests, unlocks, grants, ledger, defs] = await Promise.all([
    POOL.query(`SELECT node_id, epoch, state, lease_until, snapshot_version FROM player_sessions WHERE player_id=$1`, [player.player_id]),
    POOL.query(`SELECT currency, balance, version FROM accounts WHERE owner_id=$1 ORDER BY currency`, [player.player_id]),
    POOL.query(`SELECT l.kind, l.container_key, l.slot, l.asset_kind,
        i.item_id, i.rarity, i.advancement, i.refine, i.durability, i.binding, i.engraves, i.sockets,
        cd.definition_id, cd.payload
      FROM asset_locations l
      LEFT JOIN items i ON i.location_id=l.location_id
      LEFT JOIN content_definitions cd ON cd.definition_id=i.definition_id AND cd.version=i.definition_version
      WHERE l.owner_id=$1 AND l.kind IN ('EQUIPPED','INVENTORY','STASH','MARKET_ESCROW','JOB_ESCROW','GRANT')
      ORDER BY ${KIND_ORDER_SQL}, l.container_key, l.slot`, [player.player_id]),
    POOL.query(`SELECT l.kind, l.container_key, l.slot, ms.stack_id, ms.quantity, ms.binding,
        cd.definition_id, cd.payload
      FROM asset_locations l
      JOIN material_stacks ms ON ms.location_id=l.location_id
      JOIN content_definitions cd ON cd.definition_id=ms.definition_id AND cd.version=ms.definition_version
      WHERE l.owner_id=$1 AND l.kind IN ('EQUIPPED','INVENTORY','STASH','MARKET_ESCROW','JOB_ESCROW','GRANT')
      ORDER BY ${KIND_ORDER_SQL}, l.container_key, l.slot`, [player.player_id]),
    POOL.query(`SELECT quest_id, definition_version, state, progress, version FROM quest_progress WHERE player_id=$1`, [player.player_id]),
    POOL.query(`SELECT unlock_id, granted_at, source_operation_id FROM player_unlocks WHERE player_id=$1`, [player.player_id]),
    POOL.query(`SELECT grant_id, source_key, payload, state, created_at, claimed_at FROM reward_grants WHERE player_id=$1 ORDER BY created_at DESC`, [player.player_id]),
    POOL.query(`SELECT le.created_at, le.delta, le.reason, a.currency, o.command_type, o.idempotency_key
      FROM ledger_entries le
      JOIN accounts a ON a.account_id=le.account_id
      LEFT JOIN operations o ON o.operation_id=le.operation_id
      WHERE le.account_id IN (SELECT account_id FROM accounts WHERE owner_id=$1)
      ORDER BY le.created_at DESC, le.entry_id DESC LIMIT 25`, [player.player_id]),
    POOL.query(`SELECT definition_id, kind, payload FROM content_definitions WHERE kind IN ('MATERIAL','CONSUMABLE') ORDER BY definition_id`),
  ]);

  const sess = session.rows[0] ?? null;
  const head = headUrl(player.username, 64);
  const statusDot = (st) =>
    st === 'ACTIVE' || st === 'READY' || st === 'ONLINE'
      ? '<span class="dot online"></span>'
      : st === 'OFFLINE'
        ? '<span class="dot offline"></span>'
        : '<span class="dot active"></span>';

  const walletRows = wallets.rows.length
    ? wallets.rows.map((a) => `<tr>
        <td>${esc(a.currency)}</td>
        <td class="num">${nf(a.balance)}</td>
        <td class="num">${a.version}</td></tr>`).join('')
    : emptyRow(3, 'ยังไม่มี wallet');

  const renderItem = (row) => {
    const payload = row.payload || {};
    const name = payload.display || row.definition_id;
    const pct = Math.max(0, Math.min(100, Math.round((row.durability ?? 0) / 1))); // durability scalar placeholder
    const dur = row.durability != null
      ? `<span class="durabar"><span style="width:100%;height:100%;background:${pct > 33 ? 'var(--ok)' : pct > 10 ? 'var(--warn)' : 'var(--err)'}"></span></span>`
      : '';
    const eng = Array.isArray(row.engraves) && row.engraves.length ? ` · ${row.engraves.map((e) => e.affix).join(', ')}` : '';
    return `<span class="cell-title">
        <img class="icon" src="${itemIcon(row.definition_id, payload)}" onerror="${onerror}" alt=""/>
        ${esc(name)}
      </span>
      <span class="rar-${row.rarity ?? ''}">${esc(row.rarity ?? '')}</span>${dur}${eng}`;
  };

  const combined = [
    ...items.rows.map((r) => ({ ...r, __t: 'item' })),
    ...stacks.rows.map((r) => ({ ...r, __t: 'stack' })),
  ].sort(
    (a, b) =>
      KIND_ORDER(a.kind) - KIND_ORDER(b.kind) ||
      (a.container_key || '').localeCompare(b.container_key || '') ||
      (a.slot ?? 0) - (b.slot ?? 0)
  );

  const bucketTitle = (k, ck) =>
    k === 'EQUIPPED' ? `Equipped` : k === 'INVENTORY' ? `Inventory (${esc(ck)})` : k === 'STASH' ? `Stash` : k === 'MARKET_ESCROW' ? `Market escrow` : `Other`;

  let invHtml = '';
  let curBucket = null;
  let rows = [];
  const addSection = (title, rowsHtml) => {
    invHtml += `<table><thead><tr><th>Slot</th><th>Item</th><th>Qty</th><th>Binding</th><th class="num">Version</th></tr></thead><tbody>${rowsHtml || emptyRow(5, `${title} ว่าง`)}</tbody></table>`;
  };

  for (let i = 0; i < combined.length; i++) {
    const r = combined[i];
    const b = bucketTitle(r.kind, r.container_key);
    if (b !== curBucket) {
      if (curBucket) addSection(curBucket, rows);
      curBucket = b;
      rows = [];
    }
    rows.push(`<tr><td class="num">${r.slot ?? '—'}</td><td>${renderItem(r)}</td>
      <td class="num">${r.__t === 'stack' ? nf(r.quantity) + ' x' : ''}</td>
      <td>${esc(r.binding ?? '—')}</td>
      <td class="num">${r.slot != null ? (r.__t === 'item' ? r.version ?? 1 : 1) : '—'}</td></tr>`);
  }
  if (curBucket) addSection(curBucket, rows);
  if (!combined.length) invHtml = '<p class="badge">ยังไม่มีของใน inventory</p>';

  const defOptions = defs.rows.map((d) => `<option value="${esc(d.definition_id)}">${esc((d.payload && d.payload.display) || d.definition_id)} (${esc(d.kind)})</option>`).join('');
  const grantForm = `<form id="grant-form" onsubmit="grantItem(event)" style="display:flex;gap:8px;align-items:center;margin:6px 0 12px;font-size:13px">
      <label>Item <select id="grant-def" style="padding:4px 6px">${defOptions}</select></label>
      <label>Qty <input id="grant-qty" type="number" min="1" max="6400" value="1" style="width:64px;padding:4px 6px"></label>
      <button type="submit" style="padding:4px 10px">Grant → inv (API write)</button>
      <span id="grant-msg" style="font-size:12px"></span>
    </form>
    <script>
    async function grantItem(ev) {
      ev.preventDefault();
      const msg = document.getElementById('grant-msg');
      msg.textContent = 'writing…';
      const res = await fetch('/api/player/${esc(player.player_id)}/items', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ definition_id: document.getElementById('grant-def').value, quantity: +document.getElementById('grant-qty').value })
      });
      const data = await res.json();
      if (!res.ok) { msg.textContent = 'ERR: ' + data.error; return; }
      msg.textContent = 'ok → ' + data.asset_kind + ' @ slot ' + data.slot + (data.idempotent ? ' (replay)' : '') + ' op ' + String(data.operation_id).slice(0,8);
      setTimeout(() => location.reload(), 500);
    }
    </script>`;

  const questRows = quests.rows.length
    ? quests.rows.map((q) => {
        const t = q.progress && q.progress.target;
        const done = q.progress && q.progress.smelted != null ? q.progress.smelted : 0;
        const pct = t ? Math.round((done / t) * 100) : null;
        const bar = pct != null
          ? `<span class="durabar" style="width:90px"><span style="width:${pct}%;background:var(--accent)"></span></span> ${pct}%`
          : ''; 
        return `<tr>
          <td><code>${esc(q.quest_id)}</code></td>
          <td><span class="badge ${q.state === 'COMPLETE' ? 'ok' : q.state === 'CLAIMED' ? 'ok' : 'warn'}">${esc(q.state)}</span></td>
          <td>${bar || `<pre class="json">${esc(JSON.stringify(q.progress))}</pre>`}</td>
          <td class="num">v${q.definition_version}</td></tr>`;
      }).join('')
    : emptyRow(4, 'ยังไม่มีเควส');

  const unlockRows = unlocks.rows.length
    ? unlocks.rows.map((u) => `<tr>
        <td><code>${esc(u.unlock_id)}</code></td>
        <td>${new Date(u.granted_at).toLocaleString()}</td>
        <td class="num">${u.source_operation_id ? esc(u.source_operation_id.slice(0, 8)) : '—'}</td></tr>`).join('')
    : emptyRow(3, 'ยังไม่มีสิทธิ์ปลดล็อก');

  const grantRows = grants.rows.length
    ? grants.rows.map((g) => {
        const p = g.payload || {};
        const label = p.definition_id
          ? `${esc(p.definition_id)}${p.quantity ? ` × ${nf(p.quantity)}` : ''}`
          : `<pre class="json">${esc(JSON.stringify(g.payload))}</pre>`;
        return `<tr>
          <td><code>${esc(g.grant_id.slice(0, 8))}…</code></td>
          <td>${label}</td>
          <td><code>${esc(g.source_key)}</code></td>
          <td><span class="badge ${g.state === 'AVAILABLE' ? 'warn' : 'ok'}">${esc(g.state)}</span></td>
          <td>${new Date(g.created_at).toLocaleString()}</td></tr>`;
      }).join('')
    : emptyRow(5, 'กล่องจดหมายว่าง');

  const ledgerRows = ledger.rows.length
    ? ledger.rows.map((l) => `<tr>
        <td>${new Date(l.created_at).toLocaleString()}</td>
        <td>${esc(l.reason)}</td>
        <td>${esc(l.command_type ?? '—')}</td>
        <td>${esc(l.currency)}</td>
        <td class="num" style="color:${l.delta >= 0 ? 'var(--ok)' : 'var(--err)'}">${l.delta >= 0 ? '+' : ''}${nf(l.delta)}</td></tr>`).join('')
    : emptyRow(5, 'ยังไม่มีรายการ ledger');

  const sessBadge = sess
    ? `<tr>
        <td>${esc(sess.state)}</td>
        <td><code>${esc(sess.node_id ?? '—')}</code></td>
        <td class="num">${sess.epoch}</td>
        <td>${sess.lease_until ? new Date(sess.lease_until).toLocaleString() : '—'}</td>
        <td class="num">${sess.snapshot_version}</td></tr>`
    : emptyRow(5, 'ไม่มีการเชื่อม session');

  const profileUrl = `${BASE_URL}/player/${player.player_id}`;

  return layout(
    player.username,
    'players',
    `<div class="breadcrumb"><a href="/">Players</a> / ${esc(player.username)}</div>
     <input id="profile-url" type="text" readonly value="${esc(profileUrl)}"
       style="position:absolute;left:-9999px" aria-hidden="true"/>
     <div class="profile-head">
       <img class="head" src="${head}" alt=""/> 
       <div>
         <h1 style="margin:0">${esc(player.username)} <span class="badge ${player.status === 'ACTIVE' ? 'ok' : 'err'}">${esc(player.status)}</span></h1>
         <div class="id">${esc(player.player_id)}</div>
       </div>
       <div style="margin-left:auto;text-align:right">
         <div style="font-size:12px;color:var(--muted)">/profile URL (game link)</div>
         <code class="profile-url">${esc(profileUrl)}</code>
       </div>
     </div>

     <div class="row">
       <div class="col">
         <h2>Wallet</h2>
         <table><thead><tr><th>Currency</th><th class="num">Balance</th><th class="num">Version</th></tr></thead>
         <tbody>${walletRows}</tbody></table>

         <h2>Session (fencing)</h2>
         <table><thead><tr><th>State</th><th>Node</th><th class="num">Epoch</th><th>Lease until</th><th class="num">Snapshot</th></tr></thead>
         <tbody>${sessBadge}</tbody></table>

         <h2>Quest Progress</h2>
         <table><thead><tr><th>Quest</th><th>State</th><th>Progress</th><th class="num">Def</th></tr></thead>
         <tbody>${questRows}</tbody></table>

         <h2>Unlocks</h2>
         <table><thead><tr><th>Unlock</th><th>Granted</th><th class="num">Op</th></tr></thead>
         <tbody>${unlockRows}</tbody></table>
       </div>
       <div class="col">
         <h2>Inventory &amp; Gear</h2>
         ${grantForm}
         ${invHtml}

         <h2>Mailbox (reward grants)</h2>
         <table><thead><tr><th>Grant</th><th>Payload</th><th>Source</th><th>State</th><th>Created</th></tr></thead>
         <tbody>${grantRows}</tbody></table>

         <h2>Ledger</h2>
         <table><thead><tr><th>Time</th><th>Reason</th><th>Command</th><th>Currency</th><th class="num">Delta</th></tr></thead>
         <tbody>${ledgerRows}</tbody></table>
       </div>
     </div>`
  );
}

const KIND_ORDER_SQL = `CASE l.kind WHEN 'EQUIPPED' THEN 0 WHEN 'INVENTORY' THEN 1 WHEN 'STASH' THEN 2 WHEN 'MARKET_ESCROW' THEN 3 WHEN 'JOB_ESCROW' THEN 4 ELSE 5 END`;

// ---------- JSON api ----------

app.get('/api/health', async (req, res) => {
  try {
    await POOL.query('SELECT 1');
    res.json({ ok: true, db: 'connected' });
  } catch (e) {
    res.status(503).json({ ok: false, db: 'unreachable', error: String(e.message) });
  }
});

app.get('/api/players', async (req, res) => {
  const r = await POOL.query(`SELECT p.player_id, p.username, p.status,
      (SELECT count(*) FROM items i JOIN asset_locations l ON l.location_id=i.location_id WHERE l.owner_id=p.player_id) AS items
    FROM players p ORDER BY p.created_at`);
  res.json(r.rows);
});

app.get('/api/player/:id', async (req, res) => {
  try {
    const player = await findPlayer(req.params.id);
    if (!player) return res.status(404).json({ error: 'player not found' });
    const [wallets, items, stacks] = await Promise.all([
      POOL.query(`SELECT currency, balance, version FROM accounts WHERE owner_id=$1`, [player.player_id]),
      POOL.query(`SELECT l.kind, l.container_key, l.slot, i.rarity, i.advancement, i.refine,
        cd.definition_id, cd.payload, 'item' AS asset_kind FROM asset_locations l
      JOIN items i ON i.location_id=l.location_id
      JOIN content_definitions cd ON cd.definition_id=i.definition_id AND cd.version=i.definition_version
      WHERE l.owner_id=$1 AND l.kind IN ('EQUIPPED','INVENTORY','STASH','MARKET_ESCROW','JOB_ESCROW','GRANT')`, [player.player_id]),
      POOL.query(`SELECT l.kind, l.container_key, l.slot, ms.quantity,
        cd.definition_id, cd.payload, 'stack' AS asset_kind FROM asset_locations l
      JOIN material_stacks ms ON ms.location_id=l.location_id
      JOIN content_definitions cd ON cd.definition_id=ms.definition_id AND cd.version=ms.definition_version
      WHERE l.owner_id=$1`, [player.player_id]),
    ]);
    res.json({ ...player, profileUrl: `${BASE_URL}/player/${player.player_id}`, wallets: wallets.rows, inventory: [...items.rows, ...stacks.rows] });
  } catch (e) {
    res.status(500).json({ error: String(e.message) });
  }
});

// --- write path: grant a stack/item into the player's next free slot ---
// One SQL transaction: operation (idempotency) + location + item/stack + outbox event.
app.post('/api/player/:id/items', async (req, res) => {
  const player = await findPlayer(req.params.id);
  if (!player) return res.status(404).json({ error: 'player not found' });
  const { definition_id, quantity = 1, container = 'inv', idempotency_key } = req.body ?? {};
  if (!definition_id) return res.status(400).json({ error: 'definition_id required' });

  const def = await POOL.query(
    `SELECT definition_id, version, kind, payload FROM content_definitions WHERE definition_id=$1 ORDER BY version DESC LIMIT 1`,
    [definition_id]);
  if (!def.rows.length) return res.status(400).json({ error: `unknown definition: ${definition_id}` });

  const isStack = def.rows[0].kind === 'MATERIAL' || def.rows[0].kind === 'CONSUMABLE';
  const assetKind = isStack ? 'STACK' : 'ITEM';
  if (isStack && (!Number.isInteger(quantity) || quantity <= 0)) return res.status(400).json({ error: 'quantity must be a positive integer for stacks' });

  const client = await POOL.connect();
  try {
    await client.query('BEGIN');
    const opKey = idempotency_key || `web-grant-${randomUUID()}`;
    const existing = await client.query(
      `SELECT operation_id, response FROM operations WHERE actor_id=$1 AND idempotency_key=$2 FOR UPDATE`,
      [player.player_id, opKey]);
    if (existing.rows.length) {
      await client.query('COMMIT');
      return res.json({ idempotent: true, operation_id: existing.rows[0].operation_id, ...existing.rows[0].response });
    }

    const opId = randomUUID();
    const response = { granted: true, definition_id, quantity: isStack ? quantity : 1, container };
    await client.query(
      `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response)
       VALUES ($1,$2,$3,$4,'grant_item','SUCCEEDED',$5)`,
      [opId, player.player_id, opKey, JSON.stringify({ definition_id, quantity, container }), JSON.stringify(response)]);

    const used = await client.query(
      `SELECT slot FROM asset_locations WHERE owner_id=$1 AND container_key=$2 AND slot IS NOT NULL`,
      [player.player_id, container]);
    const usedSet = new Set(used.rows.map((r) => r.slot));
    let slot = -1;
    for (let i = 0; i <= 35; i++) if (!usedSet.has(i)) { slot = i; break; }
    if (slot < 0) { await client.query('ROLLBACK'); return res.status(400).json({ error: `container '${container}' full` }); }

    const locId = randomUUID();
    await client.query(
      `INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key, slot, version)
       VALUES ($1,$2,'INVENTORY',$3,$4,$5,1)`,
      [locId, player.player_id, assetKind, container, slot]);

    if (isStack) {
      await client.query(
        `INSERT INTO material_stacks (stack_id, location_id, definition_id, definition_version, quantity, binding)
         VALUES ($1,$2,$3,$4,$5,'UNBOUND')`,
        [randomUUID(), locId, def.rows[0].definition_id, def.rows[0].version, quantity]);
    } else {
      await client.query(
        `INSERT INTO items (item_id, location_id, definition_id, definition_version, rarity, advancement, refine, engraves, sockets, binding, durability, version)
         VALUES ($1,$2,$3,$4,'COMMON',0,0,'[]','[]','UNBOUND',100,1)`,
        [randomUUID(), locId, def.rows[0].definition_id, def.rows[0].version]);
    }

    await client.query(
      `INSERT INTO outbox_events (event_id, operation_id, event_type, schema_version, aggregate_id, aggregate_version, payload)
       VALUES ($1,$2,'inventory.updated',1,$3,1,$4)`,
      [randomUUID(), opId, player.player_id, JSON.stringify({ container, slot, definition_id, quantity: isStack ? quantity : 1, asset_kind: assetKind })]);

    await client.query('COMMIT');
    res.json({ idempotent: false, operation_id: opId, location_id: locId, container, slot, asset_kind: assetKind, definition_id, quantity: isStack ? quantity : 1 });
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    res.status(500).json({ error: String(e.message) });
  } finally {
    client.release();
  }
});

// ---------- pages ----------

app.get('/', route(async (req, res) => res.send(await overview())));

app.get('/player/:id', route((req, res) => profile(req, res)));

app.get('/profile/:id', route(async (req, res) => {
  const p = await findPlayer(req.params.id);
  if (!p) return layout('ไม่พบผู้เล่น', 'players', '', 'player not found');
  return res.redirect(`/player/${p.player_id}`);
}));

app.get('/profile', route(async (req, res) => {
  if (req.query.id) {
    const p = await findPlayer(req.query.id);
    return p
      ? res.redirect(`/player/${p.player_id}`)
      : layout('ไม่พบผู้เล่น', 'players', '', `ไม่พบ player: ${esc(req.query.id)}`);
  }
  const r = await POOL.query('SELECT player_id, username, status FROM players ORDER BY username');
  const rows = r.rows.length
    ? r.rows.map((p) => `<tr>
        <td><img class="head" src="${headUrl(p.username)}" onerror="${onerror}" alt=""/></td>
        <td><a class="player" href="/player/${p.player_id}">${esc(p.username)}</a></td>
        <td><span class="badge ${p.status === 'ACTIVE' ? 'ok' : 'err'}">${esc(p.status)}</span></td>
        <td><code>${BASE_URL}/player/${p.player_id}</code></td>
        <td><a href="/profile?id=${esc(p.username)}">/profile?id=${esc(p.username)}</a></td>
      </tr>`).join('')
    : emptyRow(5, 'ยังไม่มีผู้เล่น');
  return layout('Profile', 'players', `<h1>Profile</h1>
    <div class="stat-grid"><div><div class="k">ผู้เล่น</div><div class="v">${r.rows.length}</div></div></div>
    <p style="color:var(--muted)">in-game /profile prints: ${BASE_URL}/profile?id=&lt;username&gt;</p>
    <table><thead><tr><th></th><th>Player</th><th>Status</th><th>Profile URL</th><th>Shortcut</th></tr></thead>
    <tbody>${rows}</tbody></table>`);
}));

app.listen(PORT, () => {
  console.log(`Dimensional Miner console: ${BASE_URL}`);
});

process.on('unhandledRejection', (e) => console.error(e));