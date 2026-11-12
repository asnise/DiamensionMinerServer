import { contentPool } from "@/lib/db";

/** "raw_iron" → "Raw Iron" (vanilla ids and unknown content ids). */
export function prettyId(id) {
  return String(id || "")
    .split("_")
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(" ");
}

/**
 * Display info for item definition ids from the content DB (latest version of each ItemBase),
 * falling back to a prettified id for vanilla items. Returns { [id]: { name, rarity } }.
 */
export async function itemNames(ids) {
  const unique = [...new Set(ids.filter(Boolean).map((s) => String(s).toLowerCase()))];
  const out = {};
  for (const id of unique) out[id] = { name: prettyId(id), rarity: null };
  if (unique.length === 0) return out;
  try {
    const res = await contentPool.query(
      `SELECT DISTINCT ON (definition_id) definition_id, payload->>'display' AS display, payload->>'name' AS name,
              payload->>'rarity' AS rarity
         FROM content_definitions
        WHERE kind = 'ItemBase' AND definition_id = ANY($1)
        ORDER BY definition_id, version DESC`,
      [unique]
    );
    for (const r of res.rows) out[r.definition_id] = { name: r.display || r.name || prettyId(r.definition_id), rarity: r.rarity };
  } catch {
    // Content DB down: ids are still readable.
  }
  return out;
}

/** Display names of ProcessingRecipe definitions: { [id]: name }. */
export async function recipeNames(ids) {
  const unique = [...new Set(ids.filter(Boolean))];
  const out = {};
  for (const id of unique) out[id] = prettyId(id);
  if (unique.length === 0) return out;
  try {
    const res = await contentPool.query(
      `SELECT DISTINCT ON (definition_id) definition_id, payload->>'name' AS name, payload->>'station' AS station
         FROM content_definitions
        WHERE kind = 'ProcessingRecipe' AND definition_id = ANY($1)
        ORDER BY definition_id, version DESC`,
      [unique]
    );
    for (const r of res.rows) out[r.definition_id] = r.name || prettyId(r.definition_id);
  } catch {
    // ignore
  }
  return out;
}
