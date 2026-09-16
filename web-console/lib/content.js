import pool from '@/lib/db';

export async function listDefinitions(kind, { limit, offset }) {
  const dataRes = await pool.query(
    `SELECT definition_id, version, payload
     FROM content_definitions
     WHERE kind = $1
     ORDER BY definition_id ASC
     LIMIT $2 OFFSET $3`,
    [kind, limit, offset]
  );

  const countRes = await pool.query(
    `SELECT COUNT(*) AS total FROM content_definitions WHERE kind = $1`,
    [kind]
  );

  return {
    data: dataRes.rows,
    total: parseInt(countRes.rows[0].total, 10),
  };
}

export async function upsertDefinition(definitionId, kind, payload) {
  return pool.query(
    `INSERT INTO content_definitions (definition_id, version, kind, payload)
     VALUES ($1, 1, $2, $3)
     ON CONFLICT (definition_id, version) DO UPDATE SET payload = EXCLUDED.payload`,
    [definitionId, kind, payload]
  );
}

export async function exportDefinitions() {
  const res = await pool.query(
    'SELECT definition_id, version, kind, payload FROM content_definitions ORDER BY kind, definition_id'
  );

  const byKind = {};
  for (const row of res.rows) {
    if (!byKind[row.kind]) byKind[row.kind] = [];
    byKind[row.kind].push(row);
  }
  return byKind;
}