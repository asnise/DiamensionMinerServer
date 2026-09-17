import { NextResponse } from "next/server";
import { contentPool as pool } from "@/lib/db";
import { MINECRAFT_VANILLA_ITEMS } from "@/lib/minecraft-items";

export async function GET() {
  try {
    // 1. Fetch custom items from content_definitions
    const res = await pool.query(
      `SELECT definition_id, kind, payload 
       FROM content_definitions 
       WHERE kind IN ('ItemBase', 'MATERIAL', 'ITEM', 'WEAPON', 'ARMOR', 'TOOL')
       ORDER BY definition_id ASC`
    );

    const registryItems = res.rows.map((row) => {
      const display = row.payload?.display || row.payload?.name || row.definition_id;
      const category = row.payload?.category || row.kind;
      return {
        id: row.definition_id,
        name: display,
        group: `Content Registry (${category})`,
        isRegistry: true,
        rarity: row.payload?.rarity || "COMMON",
      };
    });

    // 2. Format vanilla items
    const vanillaItems = MINECRAFT_VANILLA_ITEMS.map((v) => ({
      id: v.id,
      name: v.name,
      group: `Minecraft Vanilla (${v.group})`,
      isRegistry: false,
    }));

    // Registry items first, followed by vanilla items
    const combined = [...registryItems, ...vanillaItems];

    return NextResponse.json({
      success: true,
      items: combined,
      registryCount: registryItems.length,
      vanillaCount: vanillaItems.length,
    });
  } catch (err) {
    return NextResponse.json({ error: err.message }, { status: 500 });
  }
}
