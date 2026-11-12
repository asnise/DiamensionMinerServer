import { ok, fail, readJson, isUuid } from "@/lib/api";
import pool from "@/lib/db";
import { getSessionPlayerId } from "@/lib/auth";
import { buyListing, MarketError } from "@/lib/market";

/**
 * Task.md 11.3: buy an Auction House listing from the web. The buyer comes from the session cookie.
 * One transaction (lib/market.js buyListing, same rules as the in-game /ah): the item goes to the
 * buyer's /grants mailbox. The operation key market_buy/<listing> is shared with the game, so the same
 * listing can never be paid twice. Body: { listing_id, price } (price = what the buyer saw).
 */
export async function POST(request) {
  const playerId = getSessionPlayerId(request);
  if (!playerId) return fail(new Error("Please log in with /weblogin before buying."), 401);
  const { ok: jsonOk, body, error } = await readJson(request);
  if (!jsonOk) return fail(new Error(error), 400);
  const listingId = body?.listing_id;
  const price = Number(body?.price);
  if (!isUuid(listingId)) return fail(new Error("Invalid listing_id"), 400);
  if (!Number.isSafeInteger(price) || price <= 0) return fail(new Error("Invalid price"), 400);

  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await buyListing(client, { buyerId: playerId, listingId, expectedPrice: price });
    await client.query("COMMIT");
    return ok({ success: true, ...r, message: "Bought! Claim it in Minecraft with /grants." });
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    if (err instanceof MarketError) return fail(err, err.status);
    return fail(err);
  } finally {
    client.release();
  }
}
