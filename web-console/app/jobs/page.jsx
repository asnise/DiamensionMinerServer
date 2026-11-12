"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import Link from "next/link";
import { RefreshCw } from "lucide-react";
import Skeleton from "@/components/Skeleton";
import { useToast } from "@/components/Toast";
import ConfirmModal from "@/components/modals/ConfirmModal";
import { usePlayerSession } from "@/lib/session";

// Task.md 11.1: your Workspace processing jobs. The server sends absolute times (finishes_at) once;
// the countdown below runs on the browser's own clock (corrected by the server's time), so nothing is
// polled while a job runs. The list is re-read only when a job reaches its finish time, or on refresh.

function fmt(sec) {
  if (sec >= 3600) return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`;
  if (sec >= 60) return `${Math.floor(sec / 60)}m ${sec % 60}s`;
  return `${sec}s`;
}

const STATE_BADGE = {
  RUNNING: { label: "Running", cls: "online" },
  READY: { label: "Done · in /grants", cls: "online" },
  CANCELLED: { label: "Cancelled", cls: "offline" },
  CLAIMED: { label: "Claimed", cls: "offline" },
};

export default function JobsPage() {
  const toast = useToast();
  const { sessionPlayerId: playerId, isHydrated: loaded } = usePlayerSession();
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [now, setNow] = useState(() => Date.now());
  const [cancelJob, setCancelJob] = useState(null);
  const [busy, setBusy] = useState(false);
  const [skew, setSkew] = useState(0); // server time − browser time
  const skewRef = useRef(0);
  const refreshAt = useRef(null);

  const load = useCallback(async () => {
    if (!playerId) return;
    try {
      const res = await fetch("/api/player/jobs", { cache: "no-store" });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error || "Could not load your jobs");
      const sk = new Date(json.server_time).getTime() - Date.now();
      skewRef.current = sk;
      setSkew(sk);
      setJobs(json.jobs);
      // Next re-read: a little after the next job is due (the game's worker runs every 10 s).
      const next = json.jobs.filter((j) => j.state === "RUNNING").map((j) => new Date(j.finishes_at).getTime());
      refreshAt.current = next.length ? Math.min(...next) + 12_000 : null;
    } catch (err) {
      toast.error(err.message, "Jobs");
    } finally {
      setLoading(false);
    }
  }, [playerId, toast]);

  // Deferred one tick: the effect only schedules the fetch (state is set when the response arrives).
  useEffect(() => {
    const t = setTimeout(load, 0);
    return () => clearTimeout(t);
  }, [load]);

  // One local ticker for every countdown; no network while it runs.
  useEffect(() => {
    const t = setInterval(() => {
      const n = Date.now();
      setNow(n);
      if (refreshAt.current && n + skewRef.current >= refreshAt.current) {
        refreshAt.current = null;
        load();
      }
    }, 1000);
    return () => clearInterval(t);
  }, [load]);

  const doCancel = async () => {
    if (!cancelJob || busy) return;
    setBusy(true);
    try {
      const res = await fetch("/api/player/jobs/cancel", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ job_id: cancelJob.job_id }),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error || "Cancel failed");
      setCancelJob(null);
      toast.success("Job cancelled. Your inputs are back in /grants.", "Cancelled");
      load();
    } catch (err) {
      setCancelJob(null);
      toast.error(err.message, "Cancel failed");
      load();
    } finally {
      setBusy(false);
    }
  };

  if (loaded && !playerId) {
    return (
      <>
        <div className="page-header">
          <h2>Workspace Jobs</h2>
        </div>
        <div className="card" style={{ padding: 24 }}>
          <p style={{ margin: "0 0 12px", color: "var(--text-secondary)" }}>Log in to see your processing jobs.</p>
          <Link href="/login" className="page-btn primary" style={{ textDecoration: "none" }}>
            Go to Web Login
          </Link>
        </div>
      </>
    );
  }

  const serverNow = now + skew;
  const running = jobs.filter((j) => j.state === "RUNNING");
  const done = jobs.filter((j) => j.state !== "RUNNING");

  const row = (j) => {
    const end = new Date(j.finishes_at).getTime();
    const start = new Date(j.started_at).getTime();
    const left = Math.max(0, Math.ceil((end - serverNow) / 1000));
    const pct = j.state === "RUNNING" ? Math.min(100, Math.max(0, ((serverNow - start) / Math.max(1, end - start)) * 100)) : 100;
    const badge = j.state === "RUNNING" && left === 0 ? { label: "Finishing…", cls: "online" } : STATE_BADGE[j.state] || { label: j.state, cls: "offline" };
    return (
      <div className="card" key={j.job_id} style={{ padding: "16px 20px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div>
            <div style={{ fontWeight: 650, fontSize: 15 }}>{j.recipe_name}</div>
            <div style={{ fontSize: 12, color: "var(--text-tertiary)" }}>
              {j.workspace_owner}&apos;s workspace · pad {j.pad + 1}
            </div>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            {j.state === "RUNNING" && (
              <span style={{ fontVariantNumeric: "tabular-nums", fontWeight: 650, minWidth: 70, textAlign: "right" }}>
                {left > 0 ? fmt(left) : "0s"}
              </span>
            )}
            <span className={`status-badge ${badge.cls}`}>{badge.label}</span>
            {j.state === "RUNNING" && left > 0 && (
              <button type="button" className="btn-secondary" style={{ padding: "5px 12px", fontSize: 12.5 }} onClick={() => setCancelJob(j)}>
                Cancel
              </button>
            )}
          </div>
        </div>
        {j.state === "RUNNING" && (
          <div style={{ height: 6, background: "var(--surface-muted)", borderRadius: 999, marginTop: 12, overflow: "hidden" }}>
            <div style={{ width: `${pct}%`, height: "100%", background: "var(--accent)", transition: "width 1s linear" }} />
          </div>
        )}
        <div style={{ display: "flex", gap: 24, marginTop: 12, fontSize: 12.5, color: "var(--text-secondary)", flexWrap: "wrap" }}>
          <div>
            <span style={{ color: "var(--text-tertiary)" }}>In: </span>
            {j.inputs.map((it) => `${it.amount}x ${it.name}`).join(", ") || "—"}
          </div>
          <div>
            <span style={{ color: "var(--text-tertiary)" }}>Out: </span>
            {j.result.map((it) => `${it.amount}x ${it.name}`).join(", ") || "nothing (unlucky roll)"}
          </div>
        </div>
      </div>
    );
  };

  return (
    <>
      <div className="page-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", gap: 12 }}>
        <div>
          <h2>Workspace Jobs</h2>
          <p className="page-subtitle">
            Processing keeps going while you are offline. Results arrive in your in-game mailbox (/grants). Start jobs in game with /ws.
          </p>
        </div>
        <button type="button" className="btn-secondary" style={{ padding: "6px 12px" }} onClick={() => { setLoading(true); load(); }} aria-label="Refresh">
          <RefreshCw size={15} />
        </button>
      </div>

      {loading && jobs.length === 0 ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {Array.from({ length: 2 }).map((_, i) => (
            <div className="card" key={i} style={{ padding: 20 }}>
              <Skeleton width={180} height={18} style={{ marginBottom: 8 }} />
              <Skeleton width="60%" height={12} />
            </div>
          ))}
        </div>
      ) : (
        <>
          <h3 style={{ fontSize: 15, margin: "0 0 10px" }}>Running ({running.length})</h3>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, marginBottom: 28 }}>
            {running.length === 0 ? (
              <div className="card" style={{ padding: 20, color: "var(--text-secondary)", fontSize: 13 }}>
                Nothing running. Right-click a station in your workspace (/ws) to start one.
              </div>
            ) : (
              running.map(row)
            )}
          </div>
          <h3 style={{ fontSize: 15, margin: "0 0 10px" }}>Last 7 days ({done.length})</h3>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            {done.length === 0 ? (
              <div className="card" style={{ padding: 20, color: "var(--text-secondary)", fontSize: 13 }}>No finished jobs yet.</div>
            ) : (
              done.map(row)
            )}
          </div>
        </>
      )}

      <ConfirmModal
        isOpen={!!cancelJob}
        onClose={() => !busy && setCancelJob(null)}
        onConfirm={doCancel}
        loading={busy}
        title="Cancel job"
        confirmText="Cancel job"
        cancelText="Keep it running"
        variant="danger"
        size="md"
      >
        {cancelJob && (
          <p style={{ margin: 0, fontSize: 13.5, color: "var(--text-secondary)" }}>
            Stop <strong style={{ color: "var(--text)" }}>{cancelJob.recipe_name}</strong>? The inputs (
            {cancelJob.inputs.map((it) => `${it.amount}x ${it.name}`).join(", ")}) go back to your /grants mailbox and the time spent is lost.
          </p>
        )}
      </ConfirmModal>
    </>
  );
}
