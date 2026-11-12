// Dry run of the Auction House SQL (Task.md 8.4) inside one transaction that is ROLLED BACK:
// list → buy → SOLD, checking the schema constraints. Writes nothing permanent.
// Usage: cd web-console; node --env-file=.env.local scripts/check_market_sql.mjs
import { randomUUID } from "node:crypto";
import { gamePool } from "../lib/db.js";

const c = await gamePool.connect();
try {
  await c.query("BEGIN");
  const acc = await c.query("SELECT account_id FROM user_accounts ORDER BY created_at LIMIT 2");
  if (acc.rows.length < 2) throw new Error("need two accounts in user_accounts");
  const [seller, buyer] = acc.rows.map((r) => r.account_id);
  const listing = randomUUID(), loc = randomUUID(), op1 = randomUUID(), op2 = randomUUID(), grant = randomUUID();
  await c.query(`INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
                 VALUES ($1,$2,$3,'x','MARKET_LIST','SUCCEEDED','{}',now())`, [op1, seller, "check/" + op1]);
  await c.query(`INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key) VALUES ($1,$2,'MARKET_ESCROW','STACK',$3)`, [loc, seller, "market:" + listing]);
  await c.query(`INSERT INTO material_stacks (stack_id, location_id, definition_id, quantity, binding, item_data) VALUES ($1,$2,'prim_metal',3,'UNBOUND','\\x00')`, [randomUUID(), loc]);
  await c.query(`INSERT INTO market_listings (listing_id, seller_id, escrow_location_id, gross_price, tax_basis_points, state, expires_at)
                 VALUES ($1,$2,$3,100,500,'ACTIVE', now() + make_interval(hours => 48))`, [listing, seller, loc]);
  console.log("list: ok");

  await c.query(`INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
                 VALUES ($1,$2,$3,'x','MARKET_BUY','SUCCEEDED','{}',now())`, [op2, buyer, "check/" + op2]);
  // ledger: buyer -95 seller +95, buyer -5 tax +5 (system wallets may go negative)
  const wallet = async (owner, code) => {
    await c.query(owner
      ? "INSERT INTO accounts (account_id, owner_id, currency, balance) VALUES ($1,$2,'COIN',0) ON CONFLICT (owner_id, currency) DO NOTHING"
      : "INSERT INTO accounts (account_id, system_code, currency, balance) VALUES ($1,$2,'COIN',0) ON CONFLICT (system_code, currency) DO NOTHING",
      [randomUUID(), owner || code]);
    const r = await c.query(owner ? "SELECT account_id FROM accounts WHERE owner_id=$1 AND currency='COIN'" : "SELECT account_id FROM accounts WHERE system_code=$1 AND currency='COIN'", [owner || code]);
    return r.rows[0].account_id;
  };
  const wb = await wallet(buyer), ws = await wallet(seller), wt = await wallet(null, "MARKET_TAX");
  // Give the test buyer 100 COIN first (rolled back with everything else).
  const wa = await wallet(null, "ADMIN_GRANTS");
  for (const [w, d] of [[wa, -100], [wb, 100]]) {
    await c.query("UPDATE accounts SET balance = balance + $1 WHERE account_id = $2", [d, w]);
    await c.query("INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason) VALUES ($1,$2,$3,$4,'CHECK_TOPUP')", [randomUUID(), op1, w, d]);
  }
  // Same shape as MarketService.buy: one entry per wallet (buyer −100, seller +95, tax +5).
  for (const [w, d, why] of [[wb, -100, "MARKET_BUY"], [ws, 95, "MARKET_SALE"], [wt, 5, "MARKET_TAX"]]) {
    await c.query("UPDATE accounts SET balance = balance + $1 WHERE account_id = $2", [d, w]);
    await c.query("INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason) VALUES ($1,$2,$3,$4,$5)", [randomUUID(), op2, w, d, why]);
  }
  await c.query(`INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES ($1,$2,$3,'{"items":[]}'::jsonb,'AVAILABLE')`, [grant, buyer, "check:" + grant]);
  await c.query("UPDATE asset_locations SET kind='GRANT', owner_id=$1, grant_id=$2, container_key=NULL, version=version+1 WHERE location_id=$3", [buyer, grant, loc]);
  await c.query("UPDATE market_listings SET state='SOLD', buyer_id=$1, buyer_grant_id=$2, version=version+1 WHERE listing_id=$3", [buyer, grant, listing]);
  await c.query("SET CONSTRAINTS ALL IMMEDIATE"); // run deferred checks (ledger balance) now
  console.log("buy: ok (constraints and ledger balance pass)");
} catch (e) {
  console.error("FAILED:", e.message);
  process.exitCode = 1;
} finally {
  await c.query("ROLLBACK");
  c.release();
  await gamePool.end();
  console.log("rolled back");
}
