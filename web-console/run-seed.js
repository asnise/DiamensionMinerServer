import fs from 'fs';
import pool from './lib/db.js';

async function run() {
  try {
    const generatorSql = `
CREATE TABLE IF NOT EXISTS workspace_generators (
 generator_id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces,
 definition_id text NOT NULL, definition_version integer NOT NULL,
 placed_x integer NOT NULL, placed_y integer NOT NULL, placed_z integer NOT NULL,
 last_collected_at timestamptz NOT NULL DEFAULT now(),
 accumulated_amount bigint NOT NULL DEFAULT 0,
 is_active boolean NOT NULL DEFAULT true,
 FOREIGN KEY(definition_id,definition_version) REFERENCES content_definitions
);
    `;
    const seed = fs.readFileSync('../database/seed.sql', 'utf8');
    await pool.query(generatorSql);
    await pool.query(seed);
    console.log('Database Seeded Successfully!');
  } catch (e) {
    console.error('Error seeding DB:', e);
  } finally {
    pool.end();
  }
}
run();
