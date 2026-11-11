import { ok, fail, parsePagination, readJson } from '@/lib/api';
import { listDefinitions, upsertDefinition } from '@/lib/content';
import { requireAdmin } from '@/lib/auth';

// Content kinds the admin editor manages (see app/admin/editor TABS and docs/database-schema.md).
const KINDS = new Set(['ItemBase', 'PassiveGenerator', 'UpgradeTree', 'Recipe', 'LootTable', 'Dungeon', 'OreType']);
const DEFINITION_ID_RE = /^[a-z0-9_]{1,64}$/;

// Admin-only: definitions include unreleased content, and writes change live item stats.
export async function GET(request, { params }) {
  const denied = requireAdmin(request);
  if (denied) return denied;

  const { kind } = await params;
  if (!KINDS.has(kind)) return fail('Unknown content kind', 400);
  const { page, limit, offset } = parsePagination(request.nextUrl.searchParams);

  try {
    const { data, total } = await listDefinitions(kind, { limit, offset });
    return ok({ data, total, page, limit });
  } catch (err) {
    return fail(err);
  }
}

export async function POST(request, { params }) {
  const denied = requireAdmin(request);
  if (denied) return denied;

  const { kind } = await params;
  if (!KINDS.has(kind)) return fail('Unknown content kind', 400);

  const parsed = await readJson(request);
  if (!parsed.ok) return fail(parsed.error, 400);

  try {
    const { definition_id: definitionId, payload } = parsed.body;
    if (typeof definitionId !== 'string' || !DEFINITION_ID_RE.test(definitionId)) {
      return fail('definition_id must be 1-64 characters of a-z, 0-9 or _', 400);
    }
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      return fail('payload must be a JSON object', 400);
    }
    await upsertDefinition(definitionId, kind, payload);
    return ok({ success: true });
  } catch (err) {
    return fail(err);
  }
}
