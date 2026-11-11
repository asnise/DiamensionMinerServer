import { ok, fail, isUuid } from "@/lib/api";
import { gamePool } from "@/lib/db";
import { getSessionPlayerId, isAdmin } from "@/lib/auth";

// A player's ledger is private: only that player (session cookie) or an admin may read it.
export async function GET(request, { params }) {
  const { id } = await params;

  if (!isUuid(id)) {
    return fail("Invalid player ID format", 400);
  }
  if (getSessionPlayerId(request) !== id.toLowerCase() && !isAdmin(request)) {
    return fail("You can only view your own transactions", 403);
  }

  const { searchParams } = new URL(request.url);
  const limit = Math.min(100, Math.max(1, parseInt(searchParams.get("limit"), 10) || 50));
  const offset = Math.max(0, parseInt(searchParams.get("offset"), 10) || 0);

  try {
    const res = await gamePool.query(
      `
      SELECT 
        l.entry_id,
        l.delta,
        l.reason,
        l.created_at,
        a.currency,
        o.command_type,
        o.idempotency_key,
        o.request_hash
      FROM ledger_entries l
      JOIN accounts a ON a.account_id = l.account_id
      JOIN players p ON p.player_id = a.owner_id
      LEFT JOIN operations o ON o.operation_id = l.operation_id
      WHERE a.owner_id = $1 OR p.minecraft_uuid = $1::text
      ORDER BY l.created_at DESC
      LIMIT $2 OFFSET $3
    `,
      [id, limit, offset]
    );

    const countRes = await gamePool.query(
      `
      SELECT count(*) AS total
      FROM ledger_entries l
      JOIN accounts a ON a.account_id = l.account_id
      JOIN players p ON p.player_id = a.owner_id
      WHERE a.owner_id = $1 OR p.minecraft_uuid = $1::text
    `,
      [id]
    );

    return ok({
      data: res.rows,
      total: parseInt(countRes.rows[0]?.total || "0", 10),
    });
  } catch (err) {
    return fail(err);
  }
}
