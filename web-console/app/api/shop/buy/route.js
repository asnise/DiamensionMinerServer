import { ok, fail, readJson, isUuid } from '@/lib/api';
import pool from '@/lib/db';
import crypto from 'crypto';
import { getSessionPlayerId } from '@/lib/auth';

export async function POST(request) {
  // The buyer is whoever the signed session cookie says; any player_id in the body is ignored.
  const player_id = getSessionPlayerId(request);
  if (!player_id) {
    return fail(new Error('Please log in with /weblogin before buying.'), 401);
  }

  const { ok: jsonOk, body, error } = await readJson(request);
  if (!jsonOk) return fail(new Error(error), 400);

  const { product_id } = body;
  if (!isUuid(product_id)) {
    return fail(new Error('Invalid product_id'), 400);
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const prodRes = await client.query(
      `SELECT product_id, name, price_coin, items, time_limited_until
       FROM server_shop_products
       WHERE product_id = $1 AND is_active = true
         AND (time_limited_until IS NULL OR time_limited_until > now())
       FOR SHARE`,
      [product_id]
    );

    if (prodRes.rows.length === 0) {
      await client.query('ROLLBACK');
      return fail(new Error('Product not found or has expired'), 404);
    }

    const product = prodRes.rows[0];
    const price = BigInt(product.price_coin);

    const accRes = await client.query(
      `SELECT account_id, balance, version
       FROM accounts
       WHERE owner_id = $1 AND currency = 'COIN'
       FOR UPDATE`,
      [player_id]
    );

    if (accRes.rows.length === 0) {
      await client.query('ROLLBACK');
      return fail(new Error('Player wallet not found'), 404);
    }

    const account = accRes.rows[0];
    const currentBalance = BigInt(account.balance);

    if (currentBalance < price) {
      await client.query('ROLLBACK');
      return fail(new Error(`Insufficient COIN balance (Need ${price}, have ${currentBalance})`), 400);
    }

    const operationId = crypto.randomUUID();
    const grantId = crypto.randomUUID();
    const idemKey = `shop/buy/${player_id}/${Date.now()}`;

    const updateAccRes = await client.query(
      `UPDATE accounts
       SET balance = balance - $1, version = version + 1
       WHERE account_id = $2
       RETURNING balance`,
      [price.toString(), account.account_id]
    );

    await client.query(
      `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
       VALUES ($1, $2, $3, 'web_shop', 'SHOP_BUY', 'SUCCEEDED', $4, now())`,
      [
        operationId,
        player_id,
        idemKey,
        JSON.stringify({ product_id: product.product_id, price: price.toString() }),
      ]
    );

    await client.query(
      `INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason)
       VALUES (gen_random_uuid(), $1, $2, $3, 'SHOP_BUY')`,
      [operationId, account.account_id, (-price).toString()]
    );

    const grantPayload = {
      type: 'SHOP_PURCHASE',
      product_id: product.product_id,
      product_name: product.name,
      items: product.items,
    };

    await client.query(
      `INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state)
       VALUES ($1, $2, $3, $4, 'AVAILABLE')`,
      [grantId, player_id, `shop/grant/${operationId}`, JSON.stringify(grantPayload)]
    );

    await client.query('COMMIT');

    return ok({
      success: true,
      message: `Purchased ${product.name}! Items sent to Grants/Mailbox.`,
      new_balance: Number(updateAccRes.rows[0].balance),
      grant_id: grantId,
      product_name: product.name,
    });
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    return fail(err);
  } finally {
    client.release();
  }
}
