import { gamePool } from '../lib/db.js';
import crypto from 'crypto';

async function grantCoins(playerId, coinAmount = 100000) {
  const client = await gamePool.connect();
  try {
    await client.query('BEGIN');

    // 1. Fetch wallet
    const accRes = await client.query(
      `SELECT account_id, balance, version FROM accounts WHERE owner_id = $1 AND currency = 'COIN' FOR UPDATE`,
      [playerId]
    );

    if (accRes.rows.length === 0) {
      throw new Error(`COIN wallet not found for player: ${playerId}`);
    }

    const wallet = accRes.rows[0];
    const prevBalance = BigInt(wallet.balance);
    const addAmount = BigInt(coinAmount);
    const newBalance = prevBalance + addAmount;

    // 2. Update account balance
    await client.query(
      `UPDATE accounts SET balance = $1, version = version + 1 WHERE account_id = $2`,
      [newBalance.toString(), wallet.account_id]
    );

    // 3. Record operation
    const opId = crypto.randomUUID();
    await client.query(
      `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
       VALUES ($1, $2, $3, 'admin_grant', 'ADMIN_GRANT_COIN', 'SUCCEEDED', $4, now())`,
      [
        opId,
        playerId,
        `admin/grant/${playerId}/${Date.now()}`,
        JSON.stringify({ granted_coins: coinAmount, new_balance: newBalance.toString() }),
      ]
    );

    // 4. Record double-entry ledger
    await client.query(
      `INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason)
       VALUES (gen_random_uuid(), $1, $2, $3, 'ADMIN_GRANT')`,
      [opId, wallet.account_id, addAmount.toString()]
    );

    await client.query('COMMIT');

    console.log(`Successfully granted ${coinAmount.toLocaleString()} COIN to player ${playerId} (Axnise)!`);
    console.log(`Previous Balance: ${prevBalance.toLocaleString()} COIN`);
    console.log(`New Balance: ${newBalance.toLocaleString()} COIN`);
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Failed to grant coins:', err);
    process.exit(1);
  } finally {
    client.release();
    process.exit(0);
  }
}

const targetId = '6002c2d3-7eee-4697-86b8-15dc8426a964';
const amount = 100000; // 100,000 COIN
grantCoins(targetId, amount);
