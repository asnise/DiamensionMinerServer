import { NextResponse } from 'next/server';

export function ok(data, init) {
  return NextResponse.json(data, init);
}

export function fail(err, status = 500) {
  const message = err?.message || String(err || 'Internal server error');
  return NextResponse.json({ error: message }, { status });
}

export function parsePagination(searchParams, defaults = { page: 1, limit: 10 }) {
  const page = Math.max(1, parseInt(searchParams.get('page'), 10) || defaults.page);
  const limit = Math.min(200, Math.max(1, parseInt(searchParams.get('limit'), 10) || defaults.limit));
  return { page, limit, offset: (page - 1) * limit };
}

export async function readJson(request) {
  try {
    return { ok: true, body: await request.json() };
  } catch {
    return { ok: false, error: 'Invalid JSON body' };
  }
}

export function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}