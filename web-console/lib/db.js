import { Pool } from 'pg';

const host = process.env.PGHOST || 'localhost';
const port = parseInt(process.env.PGPORT || '5432', 10);
const user = process.env.PGUSER || 'postgres';
const password = process.env.PGPASSWORD || '123456';

export const gamePool = new Pool({
  host,
  port,
  user,
  password,
  database: process.env.PGDATABASE_GAME || 'dimensional_game',
});

export const contentPool = new Pool({
  host,
  port,
  user,
  password,
  database: process.env.PGDATABASE_CONTENT || 'dimensional_content',
});

export default gamePool;
