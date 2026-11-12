import { ok, fail } from "@/lib/api";
import pool from "@/lib/db";
import { getSessionPlayerId } from "@/lib/auth";
import { itemNames, recipeNames } from "@/lib/item-names";

/**
 * Task.md 11.1: the logged-in player's processing jobs (Workspace, 8.2): every RUNNING one and those
 * that ended in the last 7 days. Times are absolute (started_at / finishes_at) so the browser counts
 * down on its own clock; the server is only asked again when the list may have changed.
 */
export async function GET(request) {
  const playerId = getSessionPlayerId(request);
  if (!playerId) return fail(new Error("Please log in with /weblogin."), 401);
  try {
    const res = await pool.query(
      `SELECT j.job_id, j.recipe_id, j.state, j.started_at, j.finishes_at,
              (j.output_spec->>'pad')::int AS pad, j.output_spec->'result' AS result,
              (SELECT jsonb_agg(jsonb_build_object('item_id', e->>'item_id', 'amount', (e->>'amount')::int))
                 FROM jsonb_array_elements(j.input_escrow) e) AS inputs,
              u.username AS workspace_owner
         FROM processing_jobs j
         JOIN workspaces w ON w.workspace_id = j.workspace_id
         JOIN user_accounts u ON u.account_id = w.owner_id
        WHERE j.player_id = $1
          AND (j.state = 'RUNNING' OR j.finishes_at > now() - interval '7 days')
        ORDER BY (j.state = 'RUNNING') DESC, j.finishes_at DESC
        LIMIT 100`,
      [playerId]
    );
    const ids = [];
    for (const r of res.rows) {
      for (const it of r.inputs || []) ids.push(it.item_id);
      for (const it of r.result || []) ids.push(it.item_id);
    }
    const [items, recipes] = await Promise.all([itemNames(ids), recipeNames(res.rows.map((r) => r.recipe_id))]);
    return ok({
      server_time: new Date().toISOString(), // lets the browser correct for a wrong local clock
      jobs: res.rows.map((r) => ({
        job_id: r.job_id,
        recipe_id: r.recipe_id,
        recipe_name: recipes[r.recipe_id],
        state: r.state,
        started_at: r.started_at,
        finishes_at: r.finishes_at,
        pad: r.pad,
        workspace_owner: r.workspace_owner,
        inputs: (r.inputs || []).map((it) => ({ ...it, name: items[String(it.item_id).toLowerCase()]?.name })),
        result: (r.result || []).map((it) => ({ ...it, name: items[String(it.item_id).toLowerCase()]?.name })),
      })),
    });
  } catch (err) {
    return fail(err);
  }
}
