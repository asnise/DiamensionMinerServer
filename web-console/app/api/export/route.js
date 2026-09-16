import { NextResponse } from 'next/server';
import pool from '@/lib/db';

export async function GET() {
  try {
    const res = await pool.query('SELECT definition_id, version, kind, payload FROM content_definitions ORDER BY kind, definition_id');
    const out = {};
    for (const row of res.rows) {
      if (!out[row.kind]) out[row.kind] = [];
      out[row.kind].push(row);
    }
    return NextResponse.json(out);
  } catch (err) {
    return NextResponse.json({ error: err.message }, { status: 500 });
  }
}
