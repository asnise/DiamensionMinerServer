import { ok, fail } from '@/lib/api';
import pool from '@/lib/db';

export async function GET() {
  try {
    const res = await pool.query(`
      SELECT product_id, name, description, price_coin, items, time_limited_until, created_at
      FROM server_shop_products
      WHERE is_active = true
        AND (time_limited_until IS NULL OR time_limited_until > now())
      ORDER BY price_coin ASC
    `);
    return ok({ data: res.rows });
  } catch (err) {
    return fail(err);
  }
}
