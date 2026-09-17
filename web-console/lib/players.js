import { gamePool as pool, contentPool } from '@/lib/db';

export async function getOverviewStats() {
  const res = await pool.query(`SELECT
        (SELECT count(*) FROM user_accounts) AS total_players,
        (SELECT count(*) FROM user_accounts WHERE status='ACTIVE') AS active_players,
        (SELECT count(*) FROM player_sessions WHERE state='ACTIVE' AND lease_until > now()) AS online_sessions,
        (SELECT coalesce(sum(balance),0) FROM accounts WHERE owner_id IS NOT NULL) AS player_coin,
        (SELECT count(*) FROM items) AS items,
        (SELECT count(*) FROM material_stacks) AS stacks`);
  return res.rows[0] || {};
}

export async function listPlayers() {
  const res = await pool.query(`SELECT p.player_id, p.username, p.status, p.created_at, p.minecraft_uuid,
        coalesce(jsonb_object_agg(a.currency, a.balance) FILTER (WHERE a.currency IS NOT NULL),'{}') AS wallets
      FROM players p LEFT JOIN accounts a ON a.owner_id=p.player_id
      GROUP BY p.player_id, p.username, p.status, p.created_at, p.minecraft_uuid ORDER BY p.created_at`);
  return res.rows;
}

export async function getPlayerById(playerId) {
  const res = await pool.query(`SELECT * FROM players WHERE player_id = $1 OR minecraft_uuid = $1::text`, [playerId]);
  return res.rows[0] || null;
}

export async function getPlayerWallets(playerId) {
  const res = await pool.query(
    `SELECT a.currency, a.balance, a.version 
     FROM accounts a 
     JOIN players p ON p.player_id = a.owner_id
     WHERE a.owner_id = $1 OR p.minecraft_uuid = $1::text`,
    [playerId]
  );
  return res.rows;
}

export async function getPlayerSessions(playerId) {
  const res = await pool.query(
    `SELECT s.node_id, s.epoch, s.state, s.lease_until, s.snapshot_version
     FROM player_sessions s
     JOIN players p ON p.player_id = s.player_id
     WHERE s.player_id = $1 OR p.minecraft_uuid = $1::text
     ORDER BY s.epoch DESC`,
    [playerId]
  );
  return res.rows;
}

export async function getPlayerInventory(playerId) {
  const res = await pool.query(
    `SELECT l.kind, l.container_key, l.slot, l.asset_kind, l.version AS loc_version,
            ms.quantity, ms.binding AS stack_binding, ms.definition_id AS stack_def_id,
            i.item_id, i.rarity, i.durability, i.refine, i.binding AS item_binding, i.definition_id AS item_def_id,
            i.origin_source, i.origin_context, i.origin_actor_id, i.created_at AS item_created_at,
            COALESCE(ms.definition_id, i.definition_id) AS definition_id,
            COALESCE(ms.definition_version, i.definition_version, 1) AS definition_version
     FROM asset_locations l
     JOIN players p ON p.player_id = l.owner_id
     LEFT JOIN material_stacks ms ON ms.location_id = l.location_id
     LEFT JOIN items i ON i.location_id = l.location_id
     WHERE l.owner_id = $1 OR p.minecraft_uuid = $1::text
     ORDER BY l.kind, l.slot NULLS LAST`,
    [playerId]
  );

  if (res.rows.length > 0) {
    const defIds = [...new Set(res.rows.map(r => r.definition_id).filter(Boolean))];
    if (defIds.length > 0) {
      try {
        const cRes = await contentPool.query(
          `SELECT definition_id, version, payload FROM content_definitions WHERE definition_id = ANY($1)`,
          [defIds]
        );
        const map = new Map(cRes.rows.map(r => [r.definition_id, r.payload]));
        for (const row of res.rows) {
          row.payload = map.get(row.definition_id) || { display: row.definition_id };
        }
      } catch (err) {
        for (const row of res.rows) {
          row.payload = { display: row.definition_id };
        }
      }
    }
  }

  return res.rows;
}