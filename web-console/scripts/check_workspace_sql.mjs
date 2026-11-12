// Dry run of the Workspace SQL (Task.md 8.1 / 8.2) inside one transaction that is ROLLED BACK:
// workspace + ACL + stash rows + a processing job that is completed into a grant.
// Usage: cd web-console; node --env-file=.env.local scripts/check_workspace_sql.mjs
import { randomUUID } from "node:crypto";
import { gamePool } from "../lib/db.js";

const c = await gamePool.connect();
try {
  await c.query("BEGIN");
  const acc = await c.query("SELECT account_id FROM user_accounts ORDER BY created_at LIMIT 2");
  if (acc.rows.length < 2) throw new Error("need two accounts in user_accounts");
  const [owner, guest] = acc.rows.map((r) => r.account_id);
  await c.query("INSERT INTO workspaces (workspace_id, owner_id, size_tier, layout) VALUES ($1,$2,1,'{}') ON CONFLICT (owner_id) DO NOTHING", [randomUUID(), owner]);
  const ws = (await c.query("SELECT workspace_id FROM workspaces WHERE owner_id=$1", [owner])).rows[0].workspace_id;
  await c.query(`UPDATE workspaces SET layout='{"pads":{"0":"SMELTER"}}'::jsonb, layout_version=layout_version+1 WHERE workspace_id=$1`, [ws]);
  for (const p of ["VISIT", "USE_STATION", "STORAGE"])
    await c.query("INSERT INTO workspace_acl (workspace_id, player_id, permission) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING", [ws, guest, p]);
  console.log("workspace + acl: ok");

  const loc = randomUUID();
  await c.query("INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key, slot) VALUES ($1,$2,'STASH','STACK',$3,5)", [loc, owner, "ws:" + ws]);
  await c.query("INSERT INTO material_stacks (stack_id, location_id, definition_id, quantity, binding, item_data) VALUES ($1,$2,'prim_metal',7,'UNBOUND','\\x00')", [randomUUID(), loc]);
  console.log("stash row: ok");

  const job = randomUUID(), op = randomUUID();
  await c.query(`INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)
                 VALUES ($1,$2,$3,'x','PROCESSING_START','SUCCEEDED','{}',now())`, [op, guest, "check/" + op]);
  await c.query(`INSERT INTO processing_jobs (job_id, player_id, workspace_id, recipe_id, recipe_version, operation_id, input_escrow, output_spec, state, started_at, finishes_at)
                 VALUES ($1,$2,$3,'proc_smelt_prim_bar',1,$4,'[]'::jsonb,'{"pad":0,"result":[{"item_id":"iron_ingot","amount":3}]}'::jsonb,'RUNNING', now() - interval '5 minutes', now() - make_interval(secs => 1))`,
    [job, guest, ws, op]);
  const busy = await c.query("SELECT 1 FROM processing_jobs WHERE workspace_id=$1 AND state='RUNNING' AND (output_spec->>'pad')::int = 0", [ws]);
  if (busy.rows.length !== 1) throw new Error("pad lookup failed");
  const due = await c.query("SELECT job_id FROM processing_jobs WHERE state='RUNNING' AND finishes_at <= now() AND job_id=$1 FOR UPDATE SKIP LOCKED", [job]);
  if (due.rows.length !== 1) throw new Error("job not due");
  const grant = randomUUID();
  await c.query(`INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES ($1,$2,$3,'{"items":[{"item_id":"iron_ingot","amount":3}]}'::jsonb,'AVAILABLE')`, [grant, guest, "job:" + job]);
  await c.query("UPDATE processing_jobs SET state='READY', output_grant_id=$1 WHERE job_id=$2", [grant, job]);
  await c.query("SET CONSTRAINTS ALL IMMEDIATE");
  console.log("job start → due → READY + grant: ok");
} catch (e) {
  console.error("FAILED:", e.message);
  process.exitCode = 1;
} finally {
  await c.query("ROLLBACK");
  c.release();
  await gamePool.end();
  console.log("rolled back");
}
