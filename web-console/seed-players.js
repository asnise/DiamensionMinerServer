import pool from './lib/db.js';

async function run() {
  await pool.query(`
    INSERT INTO players (player_id, username, status) VALUES 
    ('069a79f4-44e9-4726-a5be-fca90e38aaf5', 'Notch', 'ACTIVE'),
    ('853c80ef-3c37-49fd-aa49-938b674adae6', 'jeb_', 'ACTIVE'),
    ('61699b2e-d327-4a01-9f1e-0ea8c3f06bc6', 'Dinnerbone', 'ACTIVE')
    ON CONFLICT DO NOTHING;
    
    INSERT INTO accounts (account_id, owner_id, currency, balance) VALUES 
    (gen_random_uuid(), '069a79f4-44e9-4726-a5be-fca90e38aaf5', 'COIN', 500000),
    (gen_random_uuid(), '853c80ef-3c37-49fd-aa49-938b674adae6', 'COIN', 125000),
    (gen_random_uuid(), '61699b2e-d327-4a01-9f1e-0ea8c3f06bc6', 'COIN', 9500)
    ON CONFLICT DO NOTHING;
  `);
  console.log('Mock players injected!');
  pool.end();
}
run();
