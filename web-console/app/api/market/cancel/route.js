import { ok, fail, readJson, isUuid } from "@/lib/api";
import pool from "@/lib/db";
import { getSessionPlayerId } from "@/lib/auth";
import { cancelListing, MarketError } from "@/lib/market";

/** Task.md 11.3: take your own listing down; the item goes back to your /grants. Body: { listing_id }. */
export async function POST(request) {
  const playerId = getSessionPlayerId(request);
  if (!playerId) return fail(new Error("Please log in with /weblogin."), 401);
  const { ok: jsonOk, body, error } = await readJson(request);
  if (!jsonOk) return fail(new Error(error), 400);
  const listingId = body?.listing_id;
  if (!isUuid(listingId)) return fail(new Error("Invalid listing_id"), 400);

  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await cancelListing(client, { sellerId: playerId, listingId });
    await client.query("COMMIT");
    return ok({ success: true, message: "Listing cancelled. The item is in your /grants mailbox." });
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    if (err instanceof MarketError) return fail(err, err.status);
    return fail(err);
  } finally {
    client.release();
  }
}
