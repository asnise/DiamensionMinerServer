import { ok, fail, readJson, isUuid } from "@/lib/api";
import { gamePool } from "@/lib/db";
import { requireAdmin } from "@/lib/auth";

export async function POST(request) {
  const denied = requireAdmin(request);
  if (denied) return denied;

  const parsed = await readJson(request);
  if (!parsed.ok) return fail(parsed.error, 400);

  const { product_id, is_active } = parsed.body;
  if (!isUuid(product_id)) {
    return fail("Valid product_id UUID is required", 400);
  }

  try {
    const res = await gamePool.query(
      `
      UPDATE server_shop_products
      SET is_active = $1
      WHERE product_id = $2
      RETURNING *
    `,
      [Boolean(is_active), product_id]
    );

    if (res.rows.length === 0) {
      return fail("Product not found", 404);
    }

    return ok({
      success: true,
      product: res.rows[0],
      message: res.rows[0].is_active ? "Product released to shop" : "Product saved as draft",
    });
  } catch (err) {
    return fail(err);
  }
}
