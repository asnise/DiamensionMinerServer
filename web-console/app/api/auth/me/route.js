import { NextResponse } from 'next/server';
import pool from '@/lib/db';
import { getSessionPlayerId } from '@/lib/auth';

export const runtime = 'nodejs';

// The player behind the session cookie, or { player: null } when logged out.
export async function GET(request) {
  const playerId = getSessionPlayerId(request);
  if (!playerId) return NextResponse.json({ player: null });

  try {
    const res = await pool.query('SELECT player_id, username, status FROM players WHERE player_id = $1', [playerId]);
    return NextResponse.json({ player: res.rows[0] || null });
  } catch (err) {
    console.error('[auth/me]', err);
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 });
  }
}
