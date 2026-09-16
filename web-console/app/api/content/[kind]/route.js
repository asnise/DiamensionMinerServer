import { ok, fail, parsePagination, readJson } from '@/lib/api';
import { listDefinitions, upsertDefinition } from '@/lib/content';

export async function GET(request, { params }) {
  const { kind } = await params;
  const { page, limit, offset } = parsePagination(request.nextUrl.searchParams);

  try {
    const { data, total } = await listDefinitions(kind, { limit, offset });
    return ok({ data, total, page, limit });
  } catch (err) {
    return fail(err);
  }
}

export async function POST(request, { params }) {
  const { kind } = await params;
  const parsed = await readJson(request);
  if (!parsed.ok) return fail(parsed.error, 400);

  try {
    const { definition_id: definitionId, payload } = parsed.body;
    if (!definitionId || typeof payload !== 'object' || Array.isArray(payload)) {
      return fail('definition_id and a JSON object payload are required', 400);
    }
    await upsertDefinition(definitionId, kind, payload);
    return ok({ success: true });
  } catch (err) {
    return fail(err);
  }
}