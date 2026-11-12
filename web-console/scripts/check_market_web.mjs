// Dry run of the web Auction House code (lib/market.js, Task.md 11.3) against the real game DB,
// inside transactions that are ROLLED BACK: create a listing, cancel it; create another, buy it.
// Usage: cd web-console; node --env-file=.env.local scripts/check_market_web.mjs
import { register } from "node:module";
import { pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";

// Resolve the "@/..." import alias (jsconfig) to the web-console root, like Next does.
const root = pathToFileURL(new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1") + "/").href;
register(
  "data:text/javascript," +
    encodeURIComponent(`export async function resolve(s, c, next) {
      if (s.startsWith("@/")) { let u = ${JSON.stringify(root)} + s.slice(2); if (!/\\.[a-z]+$/.test(u)) u += ".js"; return next(u, c); }
      return next(s, c);
    }`)
);

const { gamePool } = await import("../lib/db.js");
const { buyListing, cancelListing } = await import("../lib/market.js");

async function listing(c, seller, price) {
  const listingId = randomUUID(), loc = randomUUID();
  await c.query("INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key) VALUES ($1,$2,'MARKET_ESCROW','STACK',$3)", [loc, seller, "market:" + listingId]);
  await c.query("INSERT INTO material_stacks (stack_id, location_id, definition_id, quantity, binding, item_data) VALUES ($1,$2,'prim_metal',3,'UNBOUND','\\x01020304')", [randomUUID(), loc]);
  await c.query("INSERT INTO market_listings (listing_id, seller_id, escrow_location_id, gross_price, tax_basis_points, state, expires_at) VALUES ($1,$2,$3,$4,500,'ACTIVE', now() + interval '1 hour')", [listingId, seller, loc, price]);
  return listingId;
}

const c = await gamePool.connect();
let failed = false;
try {
  const acc = await c.query("SELECT account_id FROM user_accounts ORDER BY created_at LIMIT 2");
  const [seller, buyer] = acc.rows.map((r) => r.account_id);

  await c.query("BEGIN");
  const l1 = await listing(c, seller, 100);
  await cancelListing(c, { sellerId: seller, listingId: l1 });
  const st = await c.query("SELECT state FROM market_listings WHERE listing_id=$1", [l1]);
  const g = await c.query("SELECT payload FROM reward_grants WHERE source_key=$1", ["market_return:" + l1]);
  console.log("cancel:", st.rows[0].state, "· grant item_data:", g.rows[0].payload.items[0].item_data);
  await c.query("ROLLBACK");

  await c.query("BEGIN");
  const l2 = await listing(c, seller, 100);
  // give the buyer 100 COIN for the test (balanced against ADMIN_GRANTS)
  const op = randomUUID();
  await c.query(`INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
                 VALUES ($1,$2,$3,'x','CHECK','SUCCEEDED','{}',now())`, [op, buyer, "check/" + op]);
  for (const [who, code, d] of [[buyer, null, 100], [null, "ADMIN_GRANTS", -100]]) {
    await c.query(who ? "INSERT INTO accounts (account_id, owner_id, currency, balance) VALUES (gen_random_uuid(),$1,'COIN',0) ON CONFLICT (owner_id, currency) DO NOTHING"
                      : "INSERT INTO accounts (account_id, system_code, currency, balance) VALUES (gen_random_uuid(),$1,'COIN',0) ON CONFLICT (system_code, currency) DO NOTHING", [who || code]);
    const w = await c.query(who ? "SELECT account_id FROM accounts WHERE owner_id=$1 AND currency='COIN'" : "SELECT account_id FROM accounts WHERE system_code=$1 AND currency='COIN'", [who || code]);
    await c.query("UPDATE accounts SET balance = balance + $1 WHERE account_id=$2", [d, w.rows[0].account_id]);
    await c.query("INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason) VALUES (gen_random_uuid(),$1,$2,$3,'CHECK')", [op, w.rows[0].account_id, d]);
  }
  try {
    await buyListing(c, { buyerId: buyer, listingId: l2, expectedPrice: 99 });
    console.log("FAIL: a changed price was accepted");
    failed = true;
  } catch (e) {
    console.log("price guard:", e.message);
  }
  const r = await buyListing(c, { buyerId: buyer, listingId: l2, expectedPrice: 100 });
  await c.query("SET CONSTRAINTS ALL IMMEDIATE");
  console.log("buy:", JSON.stringify({ price: r.price, tax: r.tax, def: r.definitionId, qty: r.quantity }));
  try {
    await buyListing(c, { buyerId: buyer, listingId: l2, expectedPrice: 100 });
    console.log("FAIL: bought twice");
    failed = true;
  } catch (e) {
    console.log("second buy refused:", e.message);
  }
} catch (e) {
  console.error("FAILED:", e.message);
  failed = true;
} finally {
  await c.query("ROLLBACK").catch(() => {});
  c.release();
  await gamePool.end();
  console.log("rolled back");
  if (failed) process.exitCode = 1;
}
