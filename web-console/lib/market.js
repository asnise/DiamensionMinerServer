// Auction House on the web (Task.md 11.3). Same SQL rules as the plugin's MarketService.java:
// one transaction per buy / cancel, the same operation keys (market_buy/<listing>), the same escrow
// move (asset_locations MARKET_ESCROW → GRANT) and a grant that carries the exact item (item_data).
import crypto from "crypto";
import { lockWallet } from "@/lib/ledger";

export const TAX_ACCOUNT = "MARKET_TAX";

/** One ledger row on a wallet (several parties paid from one wallet = one row each, see uq_ledger_operation_account). */
export async function applyDelta(client, { operationId, accountId, delta, reason }) {
  const d = BigInt(delta);
  const r = await client.query(
    `UPDATE accounts SET balance = balance + $1, version = version + 1 WHERE account_id = $2 RETURNING balance`,
    [d.toString(), accountId]
  );
  await client.query(
    `INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason) VALUES (gen_random_uuid(), $1, $2, $3, $4)`,
    [operationId, accountId, d.toString(), reason]
  );
  return BigInt(r.rows[0].balance);
}

/** Mailbox grant with the exact item; returns the grant id. */
async function mailExact(client, { playerId, sourceKey, type, definitionId, quantity, itemData }) {
  const grantId = crypto.randomUUID();
  const payload = {
    type,
    items: [{ item_id: definitionId, amount: Number(quantity), item_data: itemData ? Buffer.from(itemData).toString("base64") : undefined }],
  };
  await client.query(
    `INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES ($1, $2, $3, $4, 'AVAILABLE')`,
    [grantId, playerId, sourceKey, JSON.stringify(payload)]
  );
  return grantId;
}

async function moveToGrant(client, { locationId, ownerId, grantId }) {
  await client.query(
    `UPDATE asset_locations SET kind = 'GRANT', owner_id = $1, grant_id = $2, container_key = NULL, version = version + 1 WHERE location_id = $3`,
    [ownerId, grantId, locationId]
  );
}

async function itemTx(client, { operationId, definitionId, from, to, action, quantity, ref }) {
  await client.query(
    `INSERT INTO item_transactions (operation_id, item_id, definition_id, from_actor_id, to_actor_id, source_action, metadata)
     VALUES ($1, NULL, $2, $3, $4, $5, $6)`,
    [operationId, definitionId, from, to, action, JSON.stringify({ amount: Number(quantity), ref, via: "web" })]
  );
}

export class MarketError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

/**
 * Buys a listing. Throws MarketError (4xx) for an expected refusal. Runs inside the caller's transaction.
 * @returns {{ price: number, tax: number, newBalance: number, grantId: string, definitionId: string, quantity: number }}
 */
export async function buyListing(client, { buyerId, listingId, expectedPrice }) {
  const sel = await client.query(
    `SELECT ml.seller_id, ml.escrow_location_id, ml.gross_price, ml.tax_basis_points, ms.definition_id, ms.quantity, ms.item_data
       FROM market_listings ml JOIN material_stacks ms ON ms.location_id = ml.escrow_location_id
      WHERE ml.listing_id = $1 AND ml.state = 'ACTIVE' AND ml.expires_at > now()
      FOR UPDATE OF ml`,
    [listingId]
  );
  if (sel.rows.length === 0) throw new MarketError("That listing is gone (sold, cancelled or expired).", 409);
  const l = sel.rows[0];
  if (l.seller_id === buyerId) throw new MarketError("That is your own listing.", 400);
  const price = BigInt(l.gross_price);
  if (expectedPrice != null && BigInt(expectedPrice) !== price) throw new MarketError("The price changed. Refresh and try again.", 409);

  const operationId = crypto.randomUUID();
  const tax = (price * BigInt(l.tax_basis_points)) / 10000n; // floor, as in the plugin
  const claimed = await client.query(
    `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
     VALUES ($1, $2, $3, $4, 'MARKET_BUY', 'SUCCEEDED', $5, now())
     ON CONFLICT (actor_id, idempotency_key) DO NOTHING RETURNING operation_id`,
    [operationId, buyerId, `market_buy/${listingId}`, price.toString(),
      JSON.stringify({ listing_id: listingId, price: Number(price), tax: Number(tax), via: "web" })]
  );
  if (claimed.rows.length === 0) throw new MarketError("You already bought this listing.", 409);

  // Lock the two player wallets in a fixed order (same as the plugin: no deadlock between crossing buys).
  let buyerW, sellerW;
  if (buyerId < l.seller_id) {
    buyerW = await lockWallet(client, { ownerId: buyerId }, "COIN");
    sellerW = await lockWallet(client, { ownerId: l.seller_id }, "COIN");
  } else {
    sellerW = await lockWallet(client, { ownerId: l.seller_id }, "COIN");
    buyerW = await lockWallet(client, { ownerId: buyerId }, "COIN");
  }
  if (BigInt(buyerW.balance) < price) throw new MarketError(`Not enough COIN (have ${buyerW.balance}, need ${price}).`, 400);

  const newBalance = await applyDelta(client, { operationId, accountId: buyerW.account_id, delta: -price, reason: "MARKET_BUY" });
  if (price - tax > 0n) await applyDelta(client, { operationId, accountId: sellerW.account_id, delta: price - tax, reason: "MARKET_SALE" });
  if (tax > 0n) {
    const taxW = await lockWallet(client, { systemCode: TAX_ACCOUNT }, "COIN");
    await applyDelta(client, { operationId, accountId: taxW.account_id, delta: tax, reason: "MARKET_TAX" });
  }
  const grantId = await mailExact(client, {
    playerId: buyerId, sourceKey: `market_buy:${listingId}`, type: "MARKET_PURCHASE",
    definitionId: l.definition_id, quantity: l.quantity, itemData: l.item_data,
  });
  await moveToGrant(client, { locationId: l.escrow_location_id, ownerId: buyerId, grantId });
  await client.query(
    `UPDATE market_listings SET state = 'SOLD', buyer_id = $1, buyer_grant_id = $2, version = version + 1 WHERE listing_id = $3`,
    [buyerId, grantId, listingId]
  );
  await itemTx(client, { operationId, definitionId: l.definition_id, from: l.seller_id, to: buyerId, action: "MARKET_BUY", quantity: l.quantity, ref: listingId });
  return { price: Number(price), tax: Number(tax), newBalance: Number(newBalance), grantId, definitionId: l.definition_id, quantity: Number(l.quantity) };
}

/** Seller takes an ACTIVE listing down; the item goes to their /grants. Runs inside the caller's transaction. */
export async function cancelListing(client, { sellerId, listingId }) {
  const sel = await client.query(
    `SELECT ml.escrow_location_id, ms.definition_id, ms.quantity, ms.item_data
       FROM market_listings ml JOIN material_stacks ms ON ms.location_id = ml.escrow_location_id
      WHERE ml.listing_id = $1 AND ml.seller_id = $2 AND ml.state = 'ACTIVE' FOR UPDATE OF ml`,
    [listingId, sellerId]
  );
  if (sel.rows.length === 0) throw new MarketError("That listing is not active any more.", 409);
  const l = sel.rows[0];
  const operationId = crypto.randomUUID();
  await client.query(
    `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
     VALUES ($1, $2, $3, $4, 'MARKET_CANCELLED', 'SUCCEEDED', '{"via":"web"}', now())`,
    [operationId, sellerId, `market_cancelled/${listingId}`, listingId]
  );
  const grantId = await mailExact(client, {
    playerId: sellerId, sourceKey: `market_return:${listingId}`, type: "MARKET_RETURN",
    definitionId: l.definition_id, quantity: l.quantity, itemData: l.item_data,
  });
  await moveToGrant(client, { locationId: l.escrow_location_id, ownerId: sellerId, grantId });
  await client.query(`UPDATE market_listings SET state = 'CANCELLED', version = version + 1 WHERE listing_id = $1`, [listingId]);
  await itemTx(client, { operationId, definitionId: l.definition_id, from: null, to: sellerId, action: "MARKET_CANCELLED", quantity: l.quantity, ref: listingId });
  return { grantId };
}
