import { NextResponse } from 'next/server';
import pool from '@/lib/db';
import { PLAYER_COOKIE, PLAYER_TTL_SECONDS, clientIp, sessionCookieOptions, signToken } from '@/lib/auth';
import { hit } from '@/lib/rate-limit';

export const runtime = 'nodejs';

const KEY_RE = /^[A-Z0-9]{4,32}$/;
const MAX_ATTEMPTS = 10;
const WINDOW_MS = 10 * 60 * 1000;

// Redeems a one-time key from /weblogin and starts a signed player session (httpOnly cookie).
// POST only: a GET would put the key in server logs and browser history.
export async function POST(request) {
  const limit = hit(`login-key:${clientIp(request)}`, MAX_ATTEMPTS, WINDOW_MS);
  if (!limit.allowed) {
    return NextResponse.json(
      { error: `Too many attempts. Try again in ${Math.ceil(limit.retryAfterSeconds / 60)} minute(s).` },
      { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } }
    );
  }

  const body = await request.json().catch(() => ({}));
  const key = String(body.key || body.code || '').trim().toUpperCase();
  if (!KEY_RE.test(key)) {
    return NextResponse.json({ error: 'Please enter a valid login key.' }, { status: 400 });
  }

  try {
    // Check-and-consume in one statement: two concurrent requests cannot both redeem the key.
    const redeemed = await pool.query(
      `UPDATE web_login_keys
          SET used_at = now()
        WHERE UPPER(key_code) = $1 AND expires_at > now() AND used_at IS NULL
        RETURNING player_id`,
      [key]
    );
    if (redeemed.rows.length === 0) {
      return NextResponse.json(
        { error: 'Invalid or expired login key. Type /weblogin in Minecraft to get a fresh key.' },
        { status: 400 }
      );
    }

    const playerId = redeemed.rows[0].player_id;
    const prof = await pool.query('SELECT username, status FROM players WHERE player_id = $1', [playerId]);
    const player = prof.rows[0] || {};

    const res = NextResponse.json({
      success: true,
      player_id: playerId,
      username: player.username || null,
      status: player.status || null,
    });
    res.cookies.set(PLAYER_COOKIE, signToken({ role: 'player', sub: playerId }, PLAYER_TTL_SECONDS),
      sessionCookieOptions(PLAYER_TTL_SECONDS));
    return res;
  } catch (err) {
    console.error('[login-key]', err);
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 });
  }
}
