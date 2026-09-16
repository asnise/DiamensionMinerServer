import { NextResponse } from 'next/server';

export const runtime = 'nodejs';

export async function POST(request) {
  const formData = await request.formData();
  const username = String(formData.get('username') || '');
  const password = String(formData.get('password') || '');

  const adminPwd = process.env.ADMIN_PASSWORD || 'admin123';
  if (username !== 'admin' || password !== adminPwd) {
    return NextResponse.redirect(new URL('/admin/login?error=1', request.url), { status: 303 });
  }

  const token = Buffer.from(`admin:${adminPwd}`).toString('base64');
  const res = NextResponse.redirect(new URL('/admin/editor', request.url), { status: 303 });
  res.cookies.set('dm_admin', token, {
    httpOnly: true,
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 60 * 8,
  });
  return res;
}