import pool from './lib/db.js';

async function setup() {
  try {
    await pool.query(`
      CREATE TABLE IF NOT EXISTS server_shop_products (
        product_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        name text NOT NULL,
        description text,
        price_coin bigint NOT NULL CHECK(price_coin >= 0),
        items jsonb NOT NULL,
        is_active boolean NOT NULL DEFAULT true,
        time_limited_until timestamptz,
        created_at timestamptz NOT NULL DEFAULT now()
      );
    `);

    const countRes = await pool.query(`SELECT count(*) FROM server_shop_products`);
    if (parseInt(countRes.rows[0].count, 10) === 0) {
      await pool.query(`
        INSERT INTO server_shop_products (name, description, price_coin, items, time_limited_until)
        VALUES 
        (
          'Miner Starter Pack',
          'Basic mining gear to get you started deep in the caves.',
          5000,
          '[{"material": "IRON_PICKAXE", "amount": 1, "name": "Reinforced Iron Pickaxe"}, {"material": "TORCH", "amount": 64}, {"material": "BREAD", "amount": 32}]'::jsonb,
          NULL
        ),
        (
          'Diamond Rush Bundle',
          '32 sparkling diamonds ready for crafting or upgrading gear.',
          45000,
          '[{"material": "DIAMOND", "amount": 32, "name": "Pure Dimensional Diamonds"}]'::jsonb,
          NULL
        ),
        (
          'Netherite Titan Cache (Limited Time)',
          'Extremely rare ancient debris and netherite ingots. Available this week only!',
          120000,
          '[{"material": "NETHERITE_INGOT", "amount": 4, "name": "Forged Netherite Ingot"}, {"material": "ENCHANTED_GOLDEN_APPLE", "amount": 2, "name": "Golden Notch Apple"}]'::jsonb,
          now() + interval '7 days'
        );
      `);
      console.log('Sample shop products seeded!');
    } else {
      console.log('Shop products table already contains data.');
    }
  } catch (err) {
    console.error('Error setting up shop table:', err);
  } finally {
    pool.end();
  }
}

setup();
