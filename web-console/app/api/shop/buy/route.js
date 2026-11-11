import { ok, fail, readJson, isUuid } from '@/lib/api';
import pool from '@/lib/db';
import crypto from 'crypto';
import { getSessionPlayerId } from '@/lib/auth';
import { lockWallet, transfer } from '@/lib/ledger';

const SHOP_ACCOUNT = 'SHOP_REVENUE';

/**
 * Buys a shop product with COIN. One transaction:
 *   operation (idempotent per player + client key) -> debit player / credit SHOP_REVENUE
 *   (double-entry) -> reward_grants row for /grants -> store the response on the operation.
 *
 * Body: { product_id, idempotency_key }. The client creates idempotency_key (a UUID) once per
 * purchase attempt and re-sends the same key on retry; a repeated key returns the stored
 * result instead of charging again.
 */
export async function POST(request) {
  // The buyer is whoever the signed session cookie says; any player_id in the body is ignored.
  const playerId = getSessionPlayerId(request);
  if (!playerId) {
    return fail(new Error('Please log in with /weblogin before buying.'), 401);
  }

  const { ok: jsonOk, body, error } = await readJson(request);
  if (!jsonOk) return fail(new Error(error), 400);

  const { product_id: productId, idempotency_key: clientKey } = body;
  if (!isUuid(productId)) return fail(new Error('Invalid product_id'), 400);
  if (!isUuid(clientKey)) return fail(new Error('Missing or invalid idempotency_key'), 400);

  const idemKey = `shop/buy/${clientKey.toLowerCase()}`;
  const requestHash = crypto.createHash('sha256').update(productId.toLowerCase()).digest('hex');

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // 1. Claim the idempotency key first. A concurrent duplicate blocks here until we commit.
    const operationId = crypto.randomUUID();
    const claimed = await client.query(
      `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, completed_at)
       VALUES ($1, $2, $3, $4, 'SHOP_BUY', 'SUCCEEDED', now())
       ON CONFLICT (actor_id, idempotency_key) DO NOTHING
       RETURNING operation_id`,
      [operationId, playerId, idemKey, requestHash]
    );
    if (claimed.rows.length === 0) {
      await client.query('ROLLBACK');
      const prev = await pool.query(
        `SELECT request_hash, response FROM operations WHERE actor_id = $1 AND idempotency_key = $2`,
        [playerId, idemKey]
      );
      const row = prev.rows[0];
      if (!row || row.request_hash !== requestHash || !row.response) {
        return fail(new Error('This purchase key was already used for a different request.'), 409);
      }
      return ok({ ...row.response, replayed: true });
    }

    // 2. Product
    const prodRes = await client.query(
      `SELECT product_id, name, price_coin, items
       FROM server_shop_products
       WHERE product_id = $1 AND is_active = true
         AND (time_limited_until IS NULL OR time_limited_until > now())
       FOR SHARE`,
      [productId]
    );
    if (prodRes.rows.length === 0) {
      await client.query('ROLLBACK');
      return fail(new Error('Product not found or has expired'), 404);
    }
    const product = prodRes.rows[0];
    const price = BigInt(product.price_coin);

    // 3. Wallets (locked) and balance check
    const wallet = await lockWallet(client, { ownerId: playerId }, 'COIN');
    if (BigInt(wallet.balance) < price) {
      await client.query('ROLLBACK');
      return fail(new Error(`Insufficient COIN balance (Need ${price}, have ${wallet.balance})`), 400);
    }
    const shop = await lockWallet(client, { systemCode: SHOP_ACCOUNT }, 'COIN');

    // 4. Money: player -> SHOP_REVENUE (free products move no money)
    let newBalance = BigInt(wallet.balance);
    if (price > 0n) {
      const moved = await transfer(client, {
        operationId,
        fromAccountId: wallet.account_id,
        toAccountId: shop.account_id,
        amount: price,
        reason: 'SHOP_BUY',
      });
      newBalance = moved.fromBalance;
    }

    // 5. Items go to the in-game mailbox
    const grantId = crypto.randomUUID();
    await client.query(
      `INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state)
       VALUES ($1, $2, $3, $4, 'AVAILABLE')`,
      [grantId, playerId, `shop/grant/${operationId}`, JSON.stringify({
        type: 'SHOP_PURCHASE',
        product_id: product.product_id,
        product_name: product.name,
        items: product.items,
      })]
    );

    // 6. Store the response so a retry with the same key gets exactly this answer
    const response = {
      success: true,
      message: `Purchased ${product.name}! Items sent to Grants/Mailbox.`,
      new_balance: Number(newBalance),
      grant_id: grantId,
      product_name: product.name,
    };
    await client.query(`UPDATE operations SET response = $1 WHERE operation_id = $2`, [JSON.stringify(response), operationId]);

    await client.query('COMMIT');
    return ok(response);
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    return fail(err);
  } finally {
    client.release();
  }
}
