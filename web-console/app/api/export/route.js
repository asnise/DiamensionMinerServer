import { ok, fail } from '@/lib/api';
import { exportDefinitions } from '@/lib/content';
import { requireAdmin } from '@/lib/auth';

export async function GET(request) {
  const denied = requireAdmin(request);
  if (denied) return denied;

  try {
    return ok(await exportDefinitions());
  } catch (err) {
    return fail(err);
  }
}
