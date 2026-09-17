import { gamePool } from '../lib/db.js';

async function main() {
  await gamePool.query(`
    CREATE TABLE IF NOT EXISTS server_shop_products (
      product_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      name TEXT NOT NULL,
      description TEXT,
      price_coin BIGINT NOT NULL CHECK(price_coin >= 0),
      items JSONB NOT NULL DEFAULT '[]',
      is_active BOOLEAN NOT NULL DEFAULT true,
      time_limited_until TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS idx_shop_active ON server_shop_products(is_active, time_limited_until);
  `);
  console.log('server_shop_products table created/verified successfully!');

  // If table is empty, insert a sample starter package
  const res = await gamePool.query('SELECT count(*) FROM server_shop_products');
  if (parseInt(res.rows[0].count, 10) === 0) {
    await gamePool.query(`
      INSERT INTO server_shop_products (name, description, price_coin, items, is_active)
      VALUES 
        ('Novice Miner Starter Pack', 'Essential gear for beginning your dimensional adventures: Includes pickaxe, sword, and basic supplies.', 500, '[{"item_id":"wooden_pickaxe","amount":1,"display":"Wooden Pickaxe"},{"item_id":"wooden_sword","amount":1,"display":"Wooden Sword"}]'::jsonb, true),
        ('Nether Exploration Bundle', 'High-end protective gear for treacherous dimensional delves.', 2500, '[{"item_id":"netherite_ingot","amount":2,"display":"Netherite Ingot"},{"item_id":"golden_apple","amount":5,"display":"Golden Apple"}]'::jsonb, false);
    `);
    console.log('Inserted sample shop products (1 active, 1 draft).');
  }

  process.exit(0);
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
