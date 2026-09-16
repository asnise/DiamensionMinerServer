"use client";

import { useSyncExternalStore, useCallback, useEffect } from "react";

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(val) {
  return typeof val === "string" && UUID_RE.test(val);
}

function subscribe(callback) {
  if (typeof window === "undefined") return () => {};
  window.addEventListener("storage", callback);
  window.addEventListener("dm_session_change", callback);
  return () => {
    window.removeEventListener("storage", callback);
    window.removeEventListener("dm_session_change", callback);
  };
}

function getStoredId() {
  try {
    const val = localStorage.getItem("dm_player_id");
    return isUuid(val) ? val : null;
  } catch {
    return null;
  }
}

function getServerSnapshot() {
  return null;
}

const emptySubscribe = () => () => {};

export function usePlayerSession() {
  const isHydrated = useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false
  );

  const clientStoredId = useSyncExternalStore(subscribe, getStoredId, getServerSnapshot);

  const sessionPlayerId = isHydrated ? clientStoredId : null;

  const login = useCallback((uuid) => {
    if (!isUuid(uuid)) return false;
    try {
      localStorage.setItem("dm_player_id", uuid);
      window.dispatchEvent(new Event("dm_session_change"));
      return true;
    } catch {
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    try {
      localStorage.removeItem("dm_player_id");
      window.dispatchEvent(new Event("dm_session_change"));
    } catch {}
  }, []);

  return { sessionPlayerId, playerId: sessionPlayerId, isHydrated, login, logout, isUuid };
}
