import { NextResponse } from 'next/server';
import pool from '@/lib/db';

function kindToSQLKind(k) {
  if (k === 'items') return 'MATERIAL';
  if (k === 'generators') return 'GENERATOR';
  if (k === 'mining') return 'ORE';
  if (k === 'crafting') return 'RECIPE';
  if (k === 'loot_tables') return 'LOOT_TABLE';
  return 'MATERIAL';
}

export async function GET(request, { params }) {
  const resolvedParams = await params;
  const kindParam = resolvedParams.kind;
  
  const searchParams = request.nextUrl.searchParams;
  const page = parseInt(searchParams.get('page')) || 1;
  const limit = parseInt(searchParams.get('limit')) || 10;
  const offset = (page - 1) * limit;

  const sqlKind = kindToSQLKind(kindParam);
  
  try {
    const dataRes = await pool.query(
      `SELECT definition_id, version, payload 
       FROM content_definitions 
       WHERE kind = $1
       ORDER BY definition_id ASC
       LIMIT $2 OFFSET $3`,
      [sqlKind, limit, offset]
    );

    const countRes = await pool.query(
      `SELECT COUNT(*) FROM content_definitions WHERE kind = $1`,
      [sqlKind]
    );

    return NextResponse.json({
      data: dataRes.rows,
      total: parseInt(countRes.rows[0].count, 10),
      page, limit
    });
  } catch (err) {
    return NextResponse.json({ error: err.message }, { status: 500 });
  }
}

export async function POST(request, { params }) {
  const resolvedParams = await params;
  const kindParam = resolvedParams.kind;
  
  try {
    const { definition_id, payload } = await request.json();
    const sqlKind = kindToSQLKind(kindParam);

    await pool.query(
      `INSERT INTO content_definitions (definition_id, version, kind, payload)
       VALUES ($1, 1, $2, $3)
       ON CONFLICT (definition_id, version) DO UPDATE SET payload = EXCLUDED.payload`,
      [definition_id, sqlKind, payload]
    );

    return NextResponse.json({ success: true });
  } catch (err) {
    return NextResponse.json({ error: err.message }, { status: 500 });
  }
}
