import { ok, fail } from '@/lib/api';
import { listPlayers } from '@/lib/players';

export async function GET() {
  try {
    return ok({ data: await listPlayers() });
  } catch (err) {
    return fail(err);
  }
}