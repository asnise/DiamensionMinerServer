import { gamePool } from '../lib/db.js';

async function check() {
  const id = '6002c2d3-7eee-4697-86b8-15dc8426a964';
  const p = await gamePool.query('SELECT * FROM players WHERE player_id = $1', [id]);
  const ua = await gamePool.query('SELECT * FROM user_accounts WHERE account_id = $1', [id]);
  const w = await gamePool.query('SELECT * FROM accounts WHERE owner_id = $1', [id]);
  console.log('Players found:', p.rows);
  console.log('UserAccounts found:', ua.rows);
  console.log('Wallets found:', w.rows);
  process.exit(0);
}

check().catch(err => {
  console.error(err);
  process.exit(1);
});
