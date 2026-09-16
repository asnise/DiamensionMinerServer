import { ok, fail } from '@/lib/api';
import { exportDefinitions } from '@/lib/content';

export async function GET() {
  try {
    return ok(await exportDefinitions());
  } catch (err) {
    return fail(err);
  }
}