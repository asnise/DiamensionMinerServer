import { ok, fail } from "@/lib/api";
import pool from "@/lib/db";
import { getSessionPlayerId } from "@/lib/auth";
import { itemNames } from "@/lib/item-names";

const PAGE = 24;

/**
 * Task.md 11.3: active Auction House listings, newest first. Public (no login needed to browse).
 * Query: ?q=<text> (item id or name contains) · ?page=<n> (0-based) · ?mine=1 (logged-in seller's own).
 * item_data (the serialized ItemStack) never leaves the server.
 */
export async function GET(request) {
  const params = request.nextUrl.searchParams;
  const q = (params.get("q") || "").trim().toLowerCase().slice(0, 40);
  const page = Math.max(0, Math.min(500, Number.parseInt(params.get("page") || "0", 10) || 0));
  const mine = params.get("mine") === "1";
  const playerId = getSessionPlayerId(request);
  if (mine && !playerId) return fail(new Error("Please log in with /weblogin."), 401);

  try {
    const where = ["ml.state = 'ACTIVE'", "ml.expires_at > now()"];
    const args = [];
    if (mine) {
      args.push(playerId);
      where.push(`ml.seller_id = $${args.length}`);
    }
    if (q) {
      args.push(`%${q.replace(/[%_\\]/g, "")}%`);
      where.push(`ms.definition_id ILIKE $${args.length}`);
    }
    args.push(PAGE + 1, page * PAGE);
    const res = await pool.query(
      `SELECT ml.listing_id, ml.gross_price, ml.tax_basis_points, ml.created_at, ml.expires_at,
              ms.definition_id, ms.quantity, ml.seller_id, ua.username AS seller_name
         FROM market_listings ml
         JOIN material_stacks ms ON ms.location_id = ml.escrow_location_id
         JOIN user_accounts ua ON ua.account_id = ml.seller_id
        WHERE ${where.join(" AND ")}
        ORDER BY ml.created_at DESC, ml.listing_id
        LIMIT $${args.length - 1} OFFSET $${args.length}`,
      args
    );
    const rows = res.rows.slice(0, PAGE);
    const names = await itemNames(rows.map((r) => r.definition_id));
    return ok({
      page,
      has_next: res.rows.length > PAGE,
      listings: rows.map((r) => ({
        listing_id: r.listing_id,
        definition_id: r.definition_id,
        name: names[String(r.definition_id).toLowerCase()]?.name,
        rarity: names[String(r.definition_id).toLowerCase()]?.rarity,
        quantity: Number(r.quantity),
        price: Number(r.gross_price),
        tax_percent: r.tax_basis_points / 100,
        seller_name: r.seller_name,
        own: playerId != null && r.seller_id === playerId,
        created_at: r.created_at,
        expires_at: r.expires_at,
      })),
    });
  } catch (err) {
    return fail(err);
  }
}
