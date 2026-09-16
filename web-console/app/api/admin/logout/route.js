import { NextResponse } from 'next/server';

export const runtime = 'nodejs';

export async function GET(request) {
  const res = NextResponse.redirect(new URL('/admin/login', request.url), { status: 303 });
  res.cookies.set('dm_admin', '', {
    httpOnly: true,
    sameSite: 'lax',
    path: '/',
    maxAge: 0,
  });
  return res;
}