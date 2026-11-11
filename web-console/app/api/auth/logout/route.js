import { NextResponse } from 'next/server';
import { PLAYER_COOKIE, sessionCookieOptions } from '@/lib/auth';

export const runtime = 'nodejs';

export async function POST() {
  const res = NextResponse.json({ success: true });
  res.cookies.set(PLAYER_COOKIE, '', sessionCookieOptions(0));
  return res;
}
