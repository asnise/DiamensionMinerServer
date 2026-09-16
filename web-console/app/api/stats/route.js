import { ok, fail } from '@/lib/api';
import { getOverviewStats } from '@/lib/players';

export async function GET() {
  try {
    return ok(await getOverviewStats());
  } catch (err) {
    return fail(err);
  }
}