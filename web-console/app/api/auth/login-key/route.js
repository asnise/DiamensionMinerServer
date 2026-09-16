import { NextResponse } from 'next/server';
import pool from '@/lib/db';

export async function POST(req) {
  try {
    const body = await req.json().catch(() => ({}));
    const key = (body.key || body.code || '').trim().toUpperCase();

    if (!key) {
      return NextResponse.json({ error: 'Please enter a valid login key.' }, { status: 400 });
    }

    const res = await pool.query(
      `SELECT w.key_code, w.player_id, w.expires_at, p.username, p.status
       FROM web_login_keys w
       JOIN players p ON p.player_id = w.player_id
       WHERE UPPER(w.key_code) = $1 AND w.expires_at > now() AND w.used_at IS NULL
       LIMIT 1`,
      [key]
    );

    if (res.rows.length === 0) {
      return NextResponse.json(
        { error: 'Invalid or expired login key. Type /weblogin in Minecraft to get a fresh key.' },
        { status: 400 }
      );
    }

    const row = res.rows[0];
    await pool.query('UPDATE web_login_keys SET used_at = now() WHERE key_code = $1', [row.key_code]).catch(() => {});

    return NextResponse.json({
      success: true,
      player_id: row.player_id,
      username: row.username,
      status: row.status,
      expires_at: row.expires_at,
    });
  } catch (err) {
    return NextResponse.json({ error: 'Internal server error: ' + err.message }, { status: 500 });
  }
}

export async function GET(req) {
  try {
    const { searchParams } = new URL(req.url);
    const key = (searchParams.get('key') || searchParams.get('code') || '').trim().toUpperCase();

    if (!key) {
      return NextResponse.json({ error: 'Key is required.' }, { status: 400 });
    }

    const res = await pool.query(
      `SELECT w.key_code, w.player_id, w.expires_at, p.username, p.status
       FROM web_login_keys w
       JOIN players p ON p.player_id = w.player_id
       WHERE UPPER(w.key_code) = $1 AND w.expires_at > now() AND w.used_at IS NULL
       LIMIT 1`,
      [key]
    );

    if (res.rows.length === 0) {
      return NextResponse.json(
        { error: 'Invalid or expired login key. Type /weblogin in Minecraft to get a fresh key.' },
        { status: 400 }
      );
    }

    const row = res.rows[0];
    await pool.query('UPDATE web_login_keys SET used_at = now() WHERE key_code = $1', [row.key_code]).catch(() => {});

    return NextResponse.json({
      success: true,
      player_id: row.player_id,
      username: row.username,
      status: row.status,
      expires_at: row.expires_at,
    });
  } catch (err) {
    return NextResponse.json({ error: 'Internal server error: ' + err.message }, { status: 500 });
  }
}
