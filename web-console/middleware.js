import { NextResponse } from 'next/server';

export function middleware(req) {
  if (req.nextUrl.pathname.startsWith('/admin')) {
    const basicAuth = req.headers.get('authorization');
    if (basicAuth) {
      const authValue = basicAuth.split(' ')[1];
      const [user, pwd] = atob(authValue).split(':');
      const adminPwd = process.env.ADMIN_PASSWORD || 'admin123';

      if (user === 'admin' && pwd === adminPwd) {
        return NextResponse.next();
      }
    }
    return new NextResponse('Auth required', {
      status: 401,
      headers: { 'WWW-Authenticate': 'Basic realm="Admin"' },
    });
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/admin/:path*'],
};
