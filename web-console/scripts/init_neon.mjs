import pg from 'pg';
import fs from 'fs';
import path from 'path';

// Run with: node --env-file=.env.local scripts/init_neon.mjs
const host = process.env.CLOUD_PGHOST;
const user = process.env.CLOUD_PGUSER;
const password = process.env.CLOUD_PGPASSWORD;
const port = parseInt(process.env.CLOUD_PGPORT || '5432', 10);
if (!host || !user || !password) {
  console.error('Missing CLOUD_PGHOST/CLOUD_PGUSER/CLOUD_PGPASSWORD. Run: node --env-file=.env.local scripts/init_neon.mjs');
  process.exit(1);
}
const ssl = { rejectUnauthorized: false };

async function main() {
  const repoRoot = path.resolve(process.cwd(), '..');

  console.log('[Neon] Applying schema_content.sql to dimensional_content...');
  const contentPool = new pg.Pool({ host, port, user, password, database: 'dimensional_content', ssl });
  const contentSql = fs.readFileSync(path.join(repoRoot, 'database', 'schema_content.sql'), 'utf8');
  await contentPool.query(contentSql);
  console.log('[Neon] Applied schema_content.sql successfully!');

  // Seed content_registry.json
  const registryPath = path.join(repoRoot, 'database', 'content_registry.json');
  if (fs.existsSync(registryPath)) {
    const raw = fs.readFileSync(registryPath, 'utf8');
    const content = JSON.parse(raw);
    let seeded = 0;
    for (const [kind, items] of Object.entries(content)) {
      if (!Array.isArray(items)) continue;
      for (const item of items) {
        if (!item.definition_id) continue;
        const version = item.version || 1;
        const checksum = item.checksum || 'init';
        const payload = item.payload || {};
        await contentPool.query(
          `INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)
           VALUES ($1, $2, $3, $4, $5)
           ON CONFLICT (definition_id, version) DO UPDATE 
           SET payload = EXCLUDED.payload, checksum = EXCLUDED.checksum`,
          [item.definition_id, version, kind, JSON.stringify(payload), checksum]
        );
        seeded++;
      }
    }
    console.log(`[Neon] Seeded ${seeded} definitions into dimensional_content`);
  }
  await contentPool.end();

  console.log('[Neon] Applying schema_game.sql to dimensional_game...');
  const gamePool = new pg.Pool({ host, port, user, password, database: 'dimensional_game', ssl });
  const gameSql = fs.readFileSync(path.join(repoRoot, 'database', 'schema_game.sql'), 'utf8');
  await gamePool.query(gameSql);
  console.log('[Neon] Applied schema_game.sql successfully!');

  const contentSqlForGame = fs.readFileSync(path.join(repoRoot, 'database', 'schema_content.sql'), 'utf8');
  await gamePool.query(contentSqlForGame);

  if (fs.existsSync(registryPath)) {
    const raw = fs.readFileSync(registryPath, 'utf8');
    const content = JSON.parse(raw);
    for (const [kind, items] of Object.entries(content)) {
      if (!Array.isArray(items)) continue;
      for (const item of items) {
        if (!item.definition_id) continue;
        const version = item.version || 1;
        const checksum = item.checksum || 'init';
        const payload = item.payload || {};
        await gamePool.query(
          `INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)
           VALUES ($1, $2, $3, $4, $5)
           ON CONFLICT (definition_id, version) DO UPDATE 
           SET payload = EXCLUDED.payload, checksum = EXCLUDED.checksum`,
          [item.definition_id, version, kind, JSON.stringify(payload), checksum]
        );
      }
    }
  }

  // Seed Axnise player
  const accountId = '6002c2d3-7eee-4697-86b8-15dc8426a964';
  await gamePool.query(`
    INSERT INTO user_accounts (account_id, username, status)
    VALUES ($1, 'Axnise', 'ACTIVE')
    ON CONFLICT (account_id) DO UPDATE SET username = EXCLUDED.username
  `, [accountId]);

  await gamePool.query(`
    INSERT INTO player_identities (identity_id, account_id, provider, identifier, display_name)
    VALUES (gen_random_uuid(), $1::uuid, 'MINECRAFT', $2::text, 'Axnise')
    ON CONFLICT (provider, identifier) DO UPDATE SET display_name = EXCLUDED.display_name
  `, [accountId, accountId]);

  await gamePool.query(`
    INSERT INTO accounts (account_id, owner_id, currency, balance)
    VALUES (gen_random_uuid(), $1, 'COIN', 1000), (gen_random_uuid(), $1, 'ENERGY', 100)
    ON CONFLICT (owner_id, currency) DO NOTHING
  `, [accountId]);
  console.log('[Neon] Seeded Axnise identity and initial wallets in dimensional_game');

  const tables = (await gamePool.query(
    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name"
  )).rows.map(r => r.table_name);
  console.log('[Neon] Public tables in dimensional_game:', tables.join(', '));

  await gamePool.end();
  console.log('[Neon] All schemas and seed data ready on Neon Cloud!');
}

main().catch(err => {
  console.error('[Neon Error]:', err);
  process.exit(1);
});
