import { NextResponse } from 'next/server';
import { ADMIN_COOKIE, isAdminToken } from '@/lib/auth';

const LOGIN_PAGE = '/admin/login';
// Must stay reachable without a session.
const PUBLIC_ADMIN_API = new Set(['/api/admin/login', '/api/admin/logout']);

// First line of defence for /admin pages and /api/admin routes. Every protected route handler
// also calls requireAdmin() itself, so a matcher mistake here does not open anything up.
export function proxy(request) {
  const { pathname } = request.nextUrl;
  const authed = isAdminToken(request.cookies.get(ADMIN_COOKIE)?.value);

  if (pathname.startsWith('/api/admin')) {
    if (PUBLIC_ADMIN_API.has(pathname) || authed) return NextResponse.next();
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  if (pathname === LOGIN_PAGE) {
    return authed ? NextResponse.redirect(new URL('/admin/editor', request.url)) : NextResponse.next();
  }

  return authed ? NextResponse.next() : NextResponse.redirect(new URL(LOGIN_PAGE, request.url));
}

export const config = {
  matcher: ['/admin/:path*', '/api/admin/:path*'],
};
