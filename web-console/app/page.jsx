"use client";

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import Avatar from '@/components/Avatar';
import Skeleton from '@/components/Skeleton';
import { ChevronRight } from 'lucide-react';
import { formatCompactNumber, nf } from '@/lib/format';

const EMPTY_STATS = { total_players: 0, active_players: 0, online_sessions: 0, player_coin: 0, items: 0, stacks: 0 };

export default function OverviewPage() {
  const router = useRouter();
  const [stats, setStats] = useState(EMPTY_STATS);
  const [players, setPlayers] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      fetch('/api/stats').then(r => r.json()),
      fetch('/api/players').then(r => r.json()),
    ]).then(([s, p]) => {
      if (cancelled) return;
      setStats({ ...EMPTY_STATS, ...(s.error ? {} : s) });
      setPlayers(p.data || []);
      if (s.error || p.error) setError('Could not load player data.');
    }).catch(() => {
      if (!cancelled) setError('Could not load player data.');
    });
    return () => { cancelled = true; };
  }, []);

  const s = stats;
  const loading = !error && players === null;
  const statCards = [
    { label: 'Players', raw: s.total_players, detail: `${nf(s.active_players)} active` },
    { label: 'Online sessions', raw: s.online_sessions, detail: 'current active sessions' },
    { label: 'Player COIN', raw: s.player_coin, detail: 'sum of all player wallets' },
    { label: 'Items / Stacks', raw: `${s.items} / ${s.stacks}`, detail: 'catalogued in PostgreSQL' },
  ];

  return (
    <>
      <div className="page-header">
        <h2>Overview</h2>
        <p className="page-subtitle">Live view of players, wallets and inventory from PostgreSQL.</p>
      </div>

      <div className="stat-grid">
        {loading
          ? Array.from({ length: 4 }).map((_, i) => (
              <div className="stat-card" key={i}>
                <Skeleton width={80} height={12} />
                <Skeleton width={120} height={32} style={{ margin: '4px 0' }} />
                <Skeleton width="90%" height={12} style={{ marginTop: 6 }} />
              </div>
            ))
          : statCards.map(stat => {
              const { display, full } = formatCompactNumber(stat.raw);
              return (
                <div className="stat-card" key={stat.label}>
                  <div className="stat-label">{stat.label}</div>
                  <div className="stat-value">
                    <span className="stat-tooltip-wrap" data-tooltip={full} title={full}>
                      {display}
                    </span>
                  </div>
                  <div className="stat-detail">{stat.detail}</div>
                </div>
              );
            })}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border)' }}>
          <h3 style={{ margin: 0, fontSize: 15 }}>Players</h3>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>Player</th>
              <th>Status</th>
              <th className="num">COIN</th>
              <th className="num">ENERGY</th>
              <th>Joined</th>
            </tr>
          </thead>
          <tbody>
            {error && (
              <tr>
                <td colSpan={5}>
                  <div className="empty-state">
                    <div className="empty-title">Failed to load</div>
                    <div>{error}</div>
                  </div>
                </td>
              </tr>
            )}
            {!error && players === null && (
              <>
                {Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i}>
                    <td>
                      <div className="player-cell">
                        <Skeleton style={{ width: 32, height: 32, borderRadius: 8 }} />
                        <div>
                          <Skeleton style={{ width: 120, height: 14 }} />
                          <Skeleton style={{ width: 72, height: 11, marginTop: 6 }} />
                        </div>
                      </div>
                    </td>
                    <td><Skeleton style={{ width: 64, height: 22, borderRadius: 999 }} /></td>
                    <td className="num"><Skeleton style={{ width: 48, height: 14, marginLeft: 'auto' }} /></td>
                    <td className="num"><Skeleton style={{ width: 48, height: 14, marginLeft: 'auto' }} /></td>
                    <td><Skeleton style={{ width: 80, height: 14 }} /></td>
                  </tr>
                ))}
              </>
            )}
            {!error && players && players.length === 0 && (
              <tr>
                <td colSpan={5}>
                  <div className="empty-state">
                    <div className="empty-title">No players yet</div>
                    <div>Players will appear here once they join the server.</div>
                  </div>
                </td>
              </tr>
            )}
            {!error && players && players.map(p => {
              const isOnline = p.status === 'ACTIVE' || p.status === 'READY';
              return (
                <tr
                  key={p.player_id}
                  className="row-link"
                  tabIndex={0}
                  role="link"
                  title={`Open ${p.username}'s profile`}
                  onClick={() => router.push(`/profile?id=${p.player_id}`)}
                  onKeyDown={e => {
                    if (e.key === 'Enter') router.push(`/profile?id=${p.player_id}`);
                  }}
                >
                  <td>
                    <div className="player-cell">
                      <Avatar uuid={p.player_id} name={p.username} />
                      <div>
                        <div className="player-name">{p.username}</div>
                        <div className="player-id">{p.player_id.split('-')[0]}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span className={`status-badge ${isOnline ? 'online' : 'offline'}`}>{p.status}</span>
                  </td>
                  <td className="num">{nf(p.wallets.COIN)}</td>
                  <td className="num">{nf(p.wallets.ENERGY)}</td>
                  <td>
                    <span className="td-flex">
                      {new Date(p.created_at).toLocaleDateString('en-CA')}
                      <ChevronRight size={16} className="row-icon" />
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}