// Adds the test shop product "Training Key" (Task.md 6.2) to server_shop_products once.
// Usage: cd web-console; node --env-file=.env.local scripts/seed_test_shop_key.mjs
import { gamePool } from "../lib/db.js";

const NAME = "[TEST] Training Key";
try {
  const found = await gamePool.query("SELECT product_id FROM server_shop_products WHERE name = $1", [NAME]);
  if (found.rows.length > 0) {
    console.log(`Already there: ${found.rows[0].product_id}`);
  } else {
    const res = await gamePool.query(
      `INSERT INTO server_shop_products (name, description, price_coin, items, is_active)
       VALUES ($1, $2, $3, $4, true) RETURNING product_id`,
      [
        NAME,
        "Opens the [TEST] Training Grounds once. Delivered to your /grants mailbox.",
        50,
        JSON.stringify([{ item_id: "key_training", amount: 1 }]),
      ]
    );
    console.log(`Added product ${res.rows[0].product_id}`);
  }
} finally {
  await gamePool.end();
}
