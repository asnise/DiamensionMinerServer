import pool from '@/lib/db';
import Link from 'next/link';

function nf(n) { return Number(n || 0).toLocaleString('en-US'); }

export default async function OverviewPage() {
  const statsRes = await pool.query(`SELECT
        (SELECT count(*) FROM players) AS total_players,
        (SELECT count(*) FROM players WHERE status='ACTIVE') AS active_players,
        (SELECT count(*) FROM player_sessions WHERE state='ACTIVE') AS online_sessions,
        (SELECT coalesce(sum(balance),0) FROM accounts WHERE owner_id IS NOT NULL) AS player_coin,
        (SELECT count(*) FROM items) AS items,
        (SELECT count(*) FROM material_stacks) AS stacks`);
  
  const playersRes = await pool.query(`SELECT p.player_id, p.username, p.status, p.created_at,
        coalesce(jsonb_object_agg(a.currency, a.balance) FILTER (WHERE a.currency IS NOT NULL),'{}') AS wallets
      FROM players p LEFT JOIN accounts a ON a.owner_id=p.player_id
      GROUP BY p.player_id, p.username, p.status, p.created_at ORDER BY p.created_at`);

  const s = statsRes.rows[0] || {};
  const players = playersRes.rows;

  return (
    <>
      <h1>Overview</h1>
      <div className="stat-grid">
        <div><div className="k">Players</div><div className="v">{nf(s.total_players)} ({nf(s.active_players)} active)</div></div>
        <div><div className="k">Online sessions</div><div className="v">{nf(s.online_sessions)}</div></div>
        <div><div className="k">Player COIN</div><div className="v">{nf(s.player_coin)}</div></div>
        <div><div className="k">Items / Stacks</div><div className="v">{nf(s.items)} / {nf(s.stacks)}</div></div>
      </div>

      <h2>Players</h2>
      <table>
        <thead>
          <tr><th>Player</th><th>Status</th><th className="num">COIN</th><th>Joined</th></tr>
        </thead>
        <tbody>
          {players.map(p => {
            const isOnline = p.status === 'ACTIVE' || p.status === 'READY';
            return (
              <tr key={p.player_id}>
                <td>{p.username}</td>
                <td><span className={`dot ${isOnline ? 'online' : 'offline'}`}></span>{p.status}</td>
                <td className="num">{nf(p.wallets.COIN)}</td>
                <td>{new Date(p.created_at).toLocaleDateString('en-CA')}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </>
  );
}
