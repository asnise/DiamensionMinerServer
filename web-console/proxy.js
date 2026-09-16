import { NextResponse } from 'next/server';

const LOGIN_PATH = '/admin/login';

export function proxy(request) {
  const { pathname } = request.nextUrl;
  if (!pathname.startsWith('/admin')) {
    return NextResponse.next();
  }

  const adminPwd = process.env.ADMIN_PASSWORD || 'admin123';
  const expected = Buffer.from(`admin:${adminPwd}`).toString('base64');
  const token = request.cookies.get('dm_admin')?.value;
  const authed = token === expected;

  if (pathname === LOGIN_PATH) {
    if (authed) {
      return NextResponse.redirect(new URL('/admin/editor', request.url));
    }
    return NextResponse.next();
  }

  if (!authed) {
    return NextResponse.redirect(new URL(LOGIN_PATH, request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/admin/:path*'],
};