// Server-only session helpers (route handlers and proxy.js; both run on the Node.js runtime).
//
// Sessions are stateless signed cookies: base64url(JSON claims) + "." + HMAC-SHA256.
// The signing key is SESSION_SECRET from .env.local. Rotating it logs everyone out.
import crypto from 'node:crypto';
import { NextResponse } from 'next/server';

export const ADMIN_COOKIE = 'dm_admin';
export const PLAYER_COOKIE = 'dm_session';
export const ADMIN_TTL_SECONDS = 60 * 60 * 8;       // 8 hours
export const PLAYER_TTL_SECONDS = 60 * 60 * 24 * 7; // 7 days

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function secret() {
  const s = process.env.SESSION_SECRET;
  if (!s || s.length < 32) {
    // Fail closed: without a key nobody can be authenticated.
    throw new Error('SESSION_SECRET is missing or shorter than 32 characters (set it in web-console/.env.local)');
  }
  return s;
}

function hmac(data) {
  return crypto.createHmac('sha256', secret()).update(data).digest('base64url');
}

function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ab.length === bb.length && crypto.timingSafeEqual(ab, bb);
}

export function signToken(claims, ttlSeconds) {
  const exp = Math.floor(Date.now() / 1000) + ttlSeconds;
  const body = Buffer.from(JSON.stringify({ ...claims, exp })).toString('base64url');
  return `${body}.${hmac(body)}`;
}

/** Returns the claims of a valid, unexpired token, otherwise null. */
export function verifyToken(token) {
  if (typeof token !== 'string') return null;
  const dot = token.indexOf('.');
  if (dot <= 0) return null;
  const body = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  if (!safeEqual(sig, hmac(body))) return null;
  try {
    const claims = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (typeof claims.exp !== 'number' || claims.exp < Math.floor(Date.now() / 1000)) return null;
    return claims;
  } catch {
    return null;
  }
}

export function isAdminToken(token) {
  return verifyToken(token)?.role === 'admin';
}

export function isAdmin(request) {
  return isAdminToken(request.cookies.get(ADMIN_COOKIE)?.value);
}

/** The logged-in player's account id (from the signed session cookie), or null. */
export function getSessionPlayerId(request) {
  const claims = verifyToken(request.cookies.get(PLAYER_COOKIE)?.value);
  return claims?.role === 'player' && UUID_RE.test(claims.sub || '') ? claims.sub : null;
}

/** Use at the top of admin route handlers: `const denied = requireAdmin(request); if (denied) return denied;` */
export function requireAdmin(request) {
  return isAdmin(request) ? null : NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
}

export function sessionCookieOptions(maxAge) {
  return {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge,
  };
}

/** Constant-time check against ADMIN_PASSWORD. No default: an unset password means nobody can log in. */
export function checkAdminPassword(input) {
  const expected = process.env.ADMIN_PASSWORD;
  if (!expected) return false;
  const a = crypto.createHash('sha256').update(String(input)).digest();
  const b = crypto.createHash('sha256').update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

export function clientIp(request) {
  const fwd = request.headers.get('x-forwarded-for');
  if (fwd) return fwd.split(',')[0].trim();
  return request.headers.get('x-real-ip') || 'local';
}
