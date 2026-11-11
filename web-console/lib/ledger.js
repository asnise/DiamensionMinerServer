// Double-entry helpers for the dimensional_game economy. Use inside an open transaction
// (a pg client after BEGIN). Every money movement writes two ledger_entries under the same
// operation whose deltas sum to zero: one on the player's wallet, one on a system account
// (accounts.system_code, e.g. SHOP_REVENUE). The plugin's dungeon rewards follow the same rule.

/**
 * Returns the wallet id for (owner account | system code, currency), creating it if missing,
 * and locks the row (FOR UPDATE) for the rest of the transaction.
 * @param {import('pg').PoolClient} client
 * @param {{ ownerId?: string, systemCode?: string }} who
 * @param {'COIN'|'ENERGY'|'SERVER_XP'} currency
 */
export async function lockWallet(client, { ownerId, systemCode }, currency) {
  if (!ownerId === !systemCode) throw new Error('lockWallet needs exactly one of ownerId or systemCode');
  if (ownerId) {
    await client.query(
      `INSERT INTO accounts (account_id, owner_id, currency, balance)
       VALUES (gen_random_uuid(), $1, $2, 0) ON CONFLICT (owner_id, currency) DO NOTHING`,
      [ownerId, currency]
    );
    const r = await client.query(
      `SELECT account_id, balance FROM accounts WHERE owner_id = $1 AND currency = $2 FOR UPDATE`,
      [ownerId, currency]
    );
    return r.rows[0];
  }
  await client.query(
    `INSERT INTO accounts (account_id, system_code, currency, balance)
     VALUES (gen_random_uuid(), $1, $2, 0) ON CONFLICT (system_code, currency) DO NOTHING`,
    [systemCode, currency]
  );
  const r = await client.query(
    `SELECT account_id, balance FROM accounts WHERE system_code = $1 AND currency = $2 FOR UPDATE`,
    [systemCode, currency]
  );
  return r.rows[0];
}

/**
 * Moves `amount` (positive bigint) from one wallet to another under one operation:
 * two ledger rows (-amount, +amount) and both balances updated.
 * Player wallets cannot go negative (CHECK on accounts); system wallets can.
 * @returns {Promise<{ fromBalance: bigint, toBalance: bigint }>}
 */
export async function transfer(client, { operationId, fromAccountId, toAccountId, amount, reason }) {
  const amt = BigInt(amount);
  if (amt <= 0n) throw new Error('transfer amount must be positive');

  const from = await client.query(
    `UPDATE accounts SET balance = balance - $1, version = version + 1 WHERE account_id = $2 RETURNING balance`,
    [amt.toString(), fromAccountId]
  );
  const to = await client.query(
    `UPDATE accounts SET balance = balance + $1, version = version + 1 WHERE account_id = $2 RETURNING balance`,
    [amt.toString(), toAccountId]
  );
  await client.query(
    `INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason)
     VALUES (gen_random_uuid(), $1, $2, $3, $5), (gen_random_uuid(), $1, $4, $6, $5)`,
    [operationId, fromAccountId, (-amt).toString(), toAccountId, reason, amt.toString()]
  );
  return { fromBalance: BigInt(from.rows[0].balance), toBalance: BigInt(to.rows[0].balance) };
}
