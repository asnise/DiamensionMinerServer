import { Pool } from 'pg';

const profile = (process.env.DB_PROFILE || (process.env.PGHOST && !process.env.PGHOST.includes('neon.tech') ? 'local' : 'cloud')).toLowerCase();
const isLocal = profile === 'local';

const host = isLocal
  ? (process.env.LOCAL_PGHOST || 'localhost')
  : (process.env.CLOUD_PGHOST || process.env.PGHOST);

const port = parseInt(
  (isLocal ? process.env.LOCAL_PGPORT : (process.env.CLOUD_PGPORT || process.env.PGPORT)) || '5432',
  10
);

const user = isLocal
  ? (process.env.LOCAL_PGUSER || 'postgres')
  : (process.env.CLOUD_PGUSER || process.env.PGUSER);

const password = isLocal
  ? process.env.LOCAL_PGPASSWORD
  : (process.env.CLOUD_PGPASSWORD || process.env.PGPASSWORD);

if (!host || !user || !password) {
  throw new Error(
    `Missing DB credentials for profile "${profile}". Set ${isLocal ? 'LOCAL_PGHOST/LOCAL_PGUSER/LOCAL_PGPASSWORD' : 'CLOUD_PGHOST/CLOUD_PGUSER/CLOUD_PGPASSWORD'} in web-console/.env.local.`
  );
}

const ssl = (!isLocal || host.includes('neon.tech') || process.env.PGSSLMODE === 'require')
  ? { rejectUnauthorized: false }
  : undefined;

export const gamePool = new Pool({
  host,
  port,
  user,
  password,
  database: process.env.PGDATABASE_GAME || 'dimensional_game',
  ssl,
});

export const contentPool = new Pool({
  host,
  port,
  user,
  password,
  database: process.env.PGDATABASE_CONTENT || 'dimensional_content',
  ssl,
});

export default gamePool;
