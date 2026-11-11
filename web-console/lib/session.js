"use client";

// Client view of the player session. The session itself is an httpOnly signed cookie set by
// /api/auth/login-key; the browser cannot read or forge it, so this hook asks /api/auth/me.
import { useSyncExternalStore, useCallback } from "react";

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(val) {
  return typeof val === "string" && UUID_RE.test(val);
}

const LOGGED_OUT = { loaded: true, playerId: null, username: null };
const SERVER_SNAPSHOT = { loaded: false, playerId: null, username: null };

let state = SERVER_SNAPSHOT;
let inflight = null;
const listeners = new Set();

function emit() {
  for (const l of listeners) l();
}

/** Re-reads the session from the server (call after login). */
export function refreshSession() {
  if (!inflight) {
    inflight = fetch("/api/auth/me", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : { player: null }))
      .catch(() => ({ player: null }))
      .then((data) => {
        const p = data?.player;
        state = p ? { loaded: true, playerId: p.player_id, username: p.username } : LOGGED_OUT;
        inflight = null;
        emit();
      });
  }
  return inflight;
}

function subscribe(callback) {
  listeners.add(callback);
  if (!state.loaded && !inflight) refreshSession();
  return () => listeners.delete(callback);
}

const getSnapshot = () => state;
const getServerSnapshot = () => SERVER_SNAPSHOT;

export function usePlayerSession() {
  const s = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  const login = useCallback(() => refreshSession(), []);

  const logout = useCallback(async () => {
    await fetch("/api/auth/logout", { method: "POST" }).catch(() => {});
    state = LOGGED_OUT;
    emit();
  }, []);

  return {
    sessionPlayerId: s.playerId,
    playerId: s.playerId,
    username: s.username,
    isHydrated: s.loaded,
    login,
    logout,
    isUuid,
  };
}
