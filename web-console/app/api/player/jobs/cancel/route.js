import { ok, fail, readJson, isUuid } from "@/lib/api";
import pool from "@/lib/db";
import crypto from "crypto";
import { getSessionPlayerId } from "@/lib/auth";

/**
 * Task.md 11.1: cancel one of your own RUNNING jobs from the web. Same rules as the in-game cancel
 * (WorkspaceStore.cancel): RUNNING → CANCELLED and the escrowed inputs (exact items, item_data
 * included) go back to the player's /grants mailbox, in one transaction. The row lock makes this and
 * the in-game job worker exclusive: whichever runs first wins, the other sees the job is no longer RUNNING.
 * Body: { job_id }.
 */
export async function POST(request) {
  const playerId = getSessionPlayerId(request);
  if (!playerId) return fail(new Error("Please log in with /weblogin."), 401);
  const { ok: jsonOk, body, error } = await readJson(request);
  if (!jsonOk) return fail(new Error(error), 400);
  const jobId = body?.job_id;
  if (!isUuid(jobId)) return fail(new Error("Invalid job_id"), 400);

  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const job = await client.query(
      `SELECT player_id, recipe_id, input_escrow FROM processing_jobs WHERE job_id = $1 AND state = 'RUNNING' FOR UPDATE`,
      [jobId]
    );
    if (job.rows.length === 0) {
      await client.query("ROLLBACK");
      return fail(new Error("That job is no longer running (it may have just finished)."), 409);
    }
    const row = job.rows[0];
    if (row.player_id !== playerId) {
      await client.query("ROLLBACK");
      return fail(new Error("You can only cancel jobs you started."), 403);
    }
    const opId = crypto.randomUUID();
    await client.query(
      `INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
       VALUES ($1, $2, $3, $4, 'PROCESSING_CANCEL', 'SUCCEEDED', '{"via":"web"}', now())`,
      [opId, playerId, `ws_job_cancel/${jobId}`, row.recipe_id]
    );
    const items = Array.isArray(row.input_escrow) ? row.input_escrow : [];
    if (items.length > 0) {
      await client.query(
        `INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES ($1, $2, $3, $4, 'AVAILABLE')`,
        [crypto.randomUUID(), playerId, `job_cancel:${jobId}`, JSON.stringify({ type: "PROCESSING_CANCELLED", items })]
      );
    }
    await client.query(`UPDATE processing_jobs SET state = 'CANCELLED' WHERE job_id = $1`, [jobId]);
    await client.query("COMMIT");
    return ok({ success: true, returned: items.length });
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    return fail(err);
  } finally {
    client.release();
  }
}
