import { ok, fail, isUuid } from "@/lib/api";
import { gamePool } from "@/lib/db";
import { requireAdmin } from "@/lib/auth";

export async function DELETE(request, { params }) {
  const denied = requireAdmin(request);
  if (denied) return denied;

  const { id } = await params;
  if (!isUuid(id)) {
    return fail("Invalid product_id", 400);
  }

  try {
    const res = await gamePool.query(
      `DELETE FROM server_shop_products WHERE product_id = $1 RETURNING product_id, name`,
      [id]
    );

    if (res.rows.length === 0) {
      return fail("Product not found", 404);
    }

    return ok({ success: true, deleted: res.rows[0] });
  } catch (err) {
    return fail(err);
  }
}
