import { ok, fail, readJson, isUuid } from "@/lib/api";
import { gamePool } from "@/lib/db";

export async function GET() {
  try {
    const res = await gamePool.query(`
      SELECT product_id, name, description, price_coin, items, is_active, time_limited_until, created_at
      FROM server_shop_products
      ORDER BY created_at DESC
    `);
    return ok({ data: res.rows });
  } catch (err) {
    return fail(err);
  }
}

export async function POST(request) {
  const parsed = await readJson(request);
  if (!parsed.ok) return fail(parsed.error, 400);

  const {
    product_id,
    name,
    description = "",
    price_coin = 0,
    items = [],
    is_active = false,
    time_limited_until = null,
  } = parsed.body;

  if (!name || !name.trim()) {
    return fail("Product name is required", 400);
  }

  const numericPrice = Number(price_coin);
  if (isNaN(numericPrice) || numericPrice < 0) {
    return fail("Price must be a non-negative number", 400);
  }

  try {
    if (product_id && isUuid(product_id)) {
      const updateRes = await gamePool.query(
        `
        UPDATE server_shop_products
        SET name = $1,
            description = $2,
            price_coin = $3,
            items = $4,
            is_active = $5,
            time_limited_until = $6
        WHERE product_id = $7
        RETURNING *
      `,
        [
          name.trim(),
          description.trim(),
          numericPrice,
          JSON.stringify(items),
          Boolean(is_active),
          time_limited_until || null,
          product_id,
        ]
      );
      if (updateRes.rows.length === 0) {
        return fail("Product not found", 404);
      }
      return ok({ success: true, product: updateRes.rows[0] });
    } else {
      const insertRes = await gamePool.query(
        `
        INSERT INTO server_shop_products (name, description, price_coin, items, is_active, time_limited_until)
        VALUES ($1, $2, $3, $4, $5, $6)
        RETURNING *
      `,
        [
          name.trim(),
          description.trim(),
          numericPrice,
          JSON.stringify(items),
          Boolean(is_active),
          time_limited_until || null,
        ]
      );
      return ok({ success: true, product: insertRes.rows[0] });
    }
  } catch (err) {
    return fail(err);
  }
}
