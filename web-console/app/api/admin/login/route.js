import { NextResponse } from 'next/server';
import {
  ADMIN_COOKIE,
  ADMIN_TTL_SECONDS,
  checkAdminPassword,
  clientIp,
  sessionCookieOptions,
  signToken,
} from '@/lib/auth';
import { hit } from '@/lib/rate-limit';

export const runtime = 'nodejs';

const MAX_ATTEMPTS = 10;
const WINDOW_MS = 15 * 60 * 1000;

export async function POST(request) {
  const limit = hit(`admin-login:${clientIp(request)}`, MAX_ATTEMPTS, WINDOW_MS);
  if (!limit.allowed) {
    return NextResponse.redirect(new URL('/admin/login?error=locked', request.url), { status: 303 });
  }

  const formData = await request.formData();
  const username = String(formData.get('username') || '');
  const password = String(formData.get('password') || '');

  // Evaluate both checks so timing does not reveal which one failed.
  const userOk = username === 'admin';
  const passOk = checkAdminPassword(password);
  if (!userOk || !passOk) {
    return NextResponse.redirect(new URL('/admin/login?error=1', request.url), { status: 303 });
  }

  const res = NextResponse.redirect(new URL('/admin/editor', request.url), { status: 303 });
  res.cookies.set(ADMIN_COOKIE, signToken({ role: 'admin', sub: 'admin' }, ADMIN_TTL_SECONDS),
    sessionCookieOptions(ADMIN_TTL_SECONDS));
  return res;
}
