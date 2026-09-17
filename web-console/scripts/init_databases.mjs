import pg from 'pg';
import fs from 'fs';
import path from 'path';

const admin = new pg.Pool({ user: 'postgres', password: '123456', host: 'localhost', port: 5432, database: 'postgres' });

async function main() {
  const existing = (await admin.query('SELECT datname FROM pg_database')).rows.map(r => r.datname);
  if (!existing.includes('dimensional_content')) {
    await admin.query('CREATE DATABASE dimensional_content');
    console.log('Created database dimensional_content');
  } else {
    console.log('Database dimensional_content already exists');
  }
  if (!existing.includes('dimensional_game')) {
    await admin.query('CREATE DATABASE dimensional_game');
    console.log('Created database dimensional_game');
  } else {
    console.log('Database dimensional_game already exists');
  }
  await admin.end();

  const repoRoot = path.resolve(process.cwd(), '..');

  // Run Content Schema
  const contentPool = new pg.Pool({ user: 'postgres', password: '123456', host: 'localhost', port: 5432, database: 'dimensional_content' });
  const contentSql = fs.readFileSync(path.join(repoRoot, 'database', 'schema_content.sql'), 'utf8');
  await contentPool.query(contentSql);
  console.log('Applied schema_content.sql to dimensional_content');
  const countContent = await contentPool.query('SELECT count(*) FROM content_definitions');
  console.log('Content definitions in dimensional_content:', countContent.rows[0].count);
  await contentPool.end();

  // Run Game Schema
  const gamePool = new pg.Pool({ user: 'postgres', password: '123456', host: 'localhost', port: 5432, database: 'dimensional_game' });
  const gameSql = fs.readFileSync(path.join(repoRoot, 'database', 'schema_game.sql'), 'utf8');
  await gamePool.query(gameSql);
  console.log('Applied schema_game.sql to dimensional_game');

  // Seed default player Axnise
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
    VALUES (gen_random_uuid(), $1, 'COIN', 0), (gen_random_uuid(), $1, 'ENERGY', 0)
    ON CONFLICT (owner_id, currency) DO NOTHING
  `, [accountId]);
  console.log('Seeded Axnise identity and wallets in dimensional_game');

  await gamePool.end();
}

main().catch(err => { console.error(err); process.exit(1); });
