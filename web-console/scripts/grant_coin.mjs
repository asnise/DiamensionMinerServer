// Admin tool: gives COIN to a player with a proper double-entry ledger
// (+amount on the player's wallet, -amount on the ADMIN_GRANTS system account).
//
// Usage (from web-console/):
//   node --env-file=.env.local scripts/grant_coin.mjs <username | player_id> <amount>
//   node --env-file=.env.local scripts/grant_coin.mjs Axnise 100000
import crypto from 'node:crypto';
import { gamePool, contentPool } from '../lib/db.js';
import { lockWallet, transfer } from '../lib/ledger.js';

const [who, amountArg] = process.argv.slice(2);
if (!who || !amountArg || !/^\d+$/.test(amountArg) || BigInt(amountArg) <= 0n) {
  console.error('Usage: node --env-file=.env.local scripts/grant_coin.mjs <username | player_id> <positive amount>');
  process.exit(1);
}
const amount = BigInt(amountArg);

const client = await gamePool.connect();
let exitCode = 0;
try {
  const p = await client.query(
    `SELECT player_id, username FROM players WHERE player_id::text = $1 OR lower(username) = lower($1) OR minecraft_uuid = $1`,
    [who]
  );
  if (p.rows.length !== 1) {
    throw new Error(p.rows.length === 0 ? `No player found for "${who}"` : `"${who}" matches ${p.rows.length} players; use the player_id`);
  }
  const { player_id: playerId, username } = p.rows[0];

  await client.query('BEGIN');
  const operationId = crypto.randomUUID();
  await client.query(
    `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
     VALUES ($1, $2, $3, 'admin_grant', 'ADMIN_GRANT_COIN', 'SUCCEEDED', $4, now())`,
    [operationId, playerId, `admin/grant/${operationId}`, JSON.stringify({ granted_coins: amount.toString() })]
  );
  const source = await lockWallet(client, { systemCode: 'ADMIN_GRANTS' }, 'COIN');
  const target = await lockWallet(client, { ownerId: playerId }, 'COIN');
  const { toBalance } = await transfer(client, {
    operationId,
    fromAccountId: source.account_id,
    toAccountId: target.account_id,
    amount,
    reason: 'ADMIN_GRANT',
  });
  await client.query('COMMIT');

  console.log(`Granted ${amount.toLocaleString()} COIN to ${username} (${playerId}). New balance: ${toBalance.toLocaleString()} COIN`);
} catch (err) {
  await client.query('ROLLBACK').catch(() => {});
  console.error('Failed to grant coins:', err.message);
  exitCode = 1;
} finally {
  client.release();
  await gamePool.end();
  await contentPool.end();
  process.exit(exitCode);
}
