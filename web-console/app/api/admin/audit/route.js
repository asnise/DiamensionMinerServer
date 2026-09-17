import { ok, fail } from "@/lib/api";
import { gamePool } from "@/lib/db";

export async function GET(request) {
  const { searchParams } = new URL(request.url);
  const type = searchParams.get("type") || "all"; // "all" | "currency" | "item"
  const limit = Math.min(100, Math.max(1, parseInt(searchParams.get("limit") || "50", 10)));

  try {
    if (type === "item") {
      const itemTx = await gamePool.query(
        `
        SELECT 
          t.transaction_id,
          t.definition_id,
          t.source_action,
          t.metadata,
          t.created_at,
          t.item_id,
          p_from.username AS from_player,
          p_to.username AS to_player
        FROM item_transactions t
        LEFT JOIN players p_from ON p_from.player_id = t.from_actor_id
        LEFT JOIN players p_to ON p_to.player_id = t.to_actor_id
        ORDER BY t.created_at DESC
        LIMIT $1
      `,
        [limit]
      );
      return ok({ type: "item", data: itemTx.rows });
    }

    // Default: Currency Ledger
    const ledger = await gamePool.query(
      `
      SELECT 
        l.entry_id,
        l.delta,
        l.reason,
        l.created_at,
        a.currency,
        p.username,
        p.player_id,
        o.command_type,
        o.idempotency_key
      FROM ledger_entries l
      JOIN accounts a ON a.account_id = l.account_id
      JOIN players p ON p.player_id = a.owner_id
      LEFT JOIN operations o ON o.operation_id = l.operation_id
      ORDER BY l.created_at DESC
      LIMIT $1
    `,
      [limit]
    );

    return ok({ type: "currency", data: ledger.rows });
  } catch (err) {
    return fail(err);
  }
}
