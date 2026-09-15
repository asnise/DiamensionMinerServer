const B = 'http://localhost:3001';
async function get(path) {
  const r = await fetch(B + path, { signal: AbortSignal.timeout(8000) });
  return { status: r.status, text: await r.text() };
}
const profile = await get('/player/550e8400-e29b-41d4-a716-446655440000');
console.log('profile', profile.status);
for (const k of ['Void Sword', 'minotar', 'grant-form', 'pickaxe_void', 'COIN', 'ledger', 'q_tier1_kit'])
  console.log('  has', k, profile.text.includes(k));

process.on('unhandledRejection', () => {});

// grant a stack (idempotent key to prove replay protection)
const body = JSON.stringify({ definition_id: 'crystal_fragment', quantity: 40, idempotency_key: 'demo-grant-1' });
const r1 = await fetch(B + '/api/player/550e8400-e29b-41d4-a716-446655440000/items',
  { method: 'POST', headers: { 'content-type': 'application/json' }, body, signal: AbortSignal.timeout(8000) });
const d1 = await r1.json();
console.log('grant#1', r1.status, JSON.stringify(d1));

const r2 = await fetch(B + '/api/player/550e8400-e29b-41d4-a716-446655440000/items',
  { method: 'POST', headers: { 'content-type': 'application/json' }, body, signal: AbortSignal.timeout(8000) });
const d2 = await r2.json();
console.log('grant#2 (replay)', r2.status, 'idempotent=' + d2.idempotent);

const p2 = await get('/player/550e8400-e29b-41d4-a716-446655440000');
console.log('profile after grant len', p2.text.length, 'shows Crystal Fragment:', p2.text.includes('Crystal Fragment'));