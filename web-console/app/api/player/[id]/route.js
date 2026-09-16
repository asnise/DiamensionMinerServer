import { ok, fail, isUuid } from '@/lib/api';
import { getPlayerById, getPlayerWallets, getPlayerSessions } from '@/lib/players';

export async function GET(_request, { params }) {
  const { id } = await params;

  if (!isUuid(id)) {
    return fail('Invalid player ID format', 400);
  }

  try {
    const player = await getPlayerById(id);
    if (!player) return fail('Player not found', 404);

    const [wallets, sessions] = await Promise.all([
      getPlayerWallets(id),
      getPlayerSessions(id),
    ]);

    return ok({ player, wallets, sessions });
  } catch (err) {
    return fail(err);
  }
}