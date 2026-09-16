import pool from '@/lib/db';

export async function getOverviewStats() {
  const res = await pool.query(`SELECT
        (SELECT count(*) FROM players) AS total_players,
        (SELECT count(*) FROM players WHERE status='ACTIVE') AS active_players,
        (SELECT count(*) FROM player_sessions WHERE state='ACTIVE' AND lease_until > now()) AS online_sessions,
        (SELECT coalesce(sum(balance),0) FROM accounts WHERE owner_id IS NOT NULL) AS player_coin,
        (SELECT count(*) FROM items) AS items,
        (SELECT count(*) FROM material_stacks) AS stacks`);
  return res.rows[0] || {};
}

export async function listPlayers() {
  const res = await pool.query(`SELECT p.player_id, p.username, p.status, p.created_at,
        coalesce(jsonb_object_agg(a.currency, a.balance) FILTER (WHERE a.currency IS NOT NULL),'{}') AS wallets
      FROM players p LEFT JOIN accounts a ON a.owner_id=p.player_id
      GROUP BY p.player_id, p.username, p.status, p.created_at ORDER BY p.created_at`);
  return res.rows;
}

export async function getPlayerById(playerId) {
  const res = await pool.query(`SELECT * FROM players WHERE player_id = $1`, [playerId]);
  return res.rows[0] || null;
}

export async function getPlayerWallets(playerId) {
  const res = await pool.query(
    `SELECT currency, balance, version FROM accounts WHERE owner_id = $1`,
    [playerId]
  );
  return res.rows;
}

export async function getPlayerSessions(playerId) {
  const res = await pool.query(
    `SELECT node_id, epoch, state, lease_until, snapshot_version
     FROM player_sessions WHERE player_id = $1 ORDER BY epoch DESC`,
    [playerId]
  );
  return res.rows;
}

export async function getPlayerInventory(playerId) {
  const res = await pool.query(
    `SELECT l.kind, l.container_key, l.slot, l.asset_kind, l.version AS loc_version,
            ms.quantity, ms.binding AS stack_binding, ms.definition_id AS stack_def_id,
            i.rarity, i.durability, i.refine, i.binding AS item_binding, i.definition_id AS item_def_id,
            cd.payload, COALESCE(ms.definition_id, i.definition_id) AS definition_id
     FROM asset_locations l
     LEFT JOIN material_stacks ms ON ms.location_id = l.location_id
     LEFT JOIN items i ON i.location_id = l.location_id
     JOIN content_definitions cd
       ON cd.definition_id = COALESCE(ms.definition_id, i.definition_id)
      AND cd.version     = COALESCE(ms.definition_version, i.definition_version)
     WHERE l.owner_id = $1
     ORDER BY l.kind, l.slot NULLS LAST`,
    [playerId]
  );
  return res.rows;
}