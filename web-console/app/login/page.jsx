"use client";

import { useState, useEffect, useRef, use } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Key, ShieldCheck, Loader2 } from "lucide-react";
import { useToast } from "@/components/Toast";
import { usePlayerSession } from "@/lib/session";

// Redeems the one-time key; the server sets an httpOnly session cookie on success.
async function redeemKey(key) {
  const res = await fetch("/api/auth/login-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ key }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Invalid or expired key");
  return data;
}

export default function LoginPage({ searchParams }) {
  const router = useRouter();
  const toast = useToast();
  const resolvedParams = use(searchParams);
  const queryCode = (resolvedParams?.code || resolvedParams?.key || "").trim().toUpperCase();

  const { login } = usePlayerSession();

  const [inputCode, setInputCode] = useState(queryCode);
  const [loading, setLoading] = useState(Boolean(queryCode));
  const [errorMsg, setErrorMsg] = useState(null);

  const hasExecutedRef = useRef(false);

  useEffect(() => {
    if (!queryCode || hasExecutedRef.current) return;
    hasExecutedRef.current = true;

    redeemKey(queryCode)
      .then(async (data) => {
        await login();
        toast.success(`Welcome back, ${data.username}!`, "Login Successful");
        router.replace("/profile");
      })
      .catch((err) => {
        setErrorMsg(err.message || "Network error");
        toast.error(err.message || "Could not verify key.", "Login Failed");
      })
      .finally(() => setLoading(false));
  }, [queryCode, login, router, toast]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    const clean = inputCode.trim().toUpperCase();
    if (!clean) {
      toast.warning("Please enter your Web Login Key", "Input Required");
      return;
    }

    setLoading(true);
    setErrorMsg(null);

    try {
      const data = await redeemKey(clean);
      await login();
      toast.success(`Welcome back, ${data.username}!`, "Connected");
      router.replace("/profile");
    } catch (err) {
      setErrorMsg(err.message);
      toast.error(err.message, "Login Failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <Link className="back-link" href="/">
        <ArrowLeft size={16} /> Back to overview
      </Link>

      <div className="card" style={{ maxWidth: 460, margin: "40px auto", padding: "36px 32px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 16 }}>
          <div
            style={{
              width: 42,
              height: 42,
              borderRadius: 10,
              background: "var(--accent-subtle)",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "var(--accent)",
            }}
          >
            <Key size={22} />
          </div>
          <div>
            <h3 style={{ margin: 0, fontSize: 17 }}>Web Console Login</h3>
            <p style={{ margin: "2px 0 0", fontSize: 13, color: "var(--text-secondary)" }}>
              Authenticate with your Minecraft account
            </p>
          </div>
        </div>

        <div
          style={{
            background: "var(--surface-muted)",
            border: "1px solid var(--border)",
            borderRadius: "var(--radius-sm)",
            padding: "12px 14px",
            marginBottom: 20,
            fontSize: 13,
            lineHeight: 1.5,
            color: "var(--text-secondary)",
          }}
        >
          Join the Minecraft server and type{" "}
          <code
            style={{
              background: "var(--surface)",
              padding: "2px 6px",
              borderRadius: 4,
              border: "1px solid var(--border-strong)",
              color: "var(--text)",
              fontWeight: 600,
            }}
          >
            /login
          </code>{" "}
          or{" "}
          <code
            style={{
              background: "var(--surface)",
              padding: "2px 6px",
              borderRadius: 4,
              border: "1px solid var(--border-strong)",
              color: "var(--text)",
              fontWeight: 600,
            }}
          >
            /weblogin
          </code>{" "}
          to receive your 6-character Login Key link.
        </div>

        {errorMsg && (
          <div
            style={{
              padding: "10px 14px",
              borderRadius: "var(--radius-sm)",
              background: "rgba(239, 68, 68, 0.08)",
              border: "1px solid rgba(239, 68, 68, 0.2)",
              color: "var(--danger)",
              fontSize: 13,
              marginBottom: 16,
            }}
          >
            {errorMsg}
          </div>
        )}

        <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <label style={{ fontSize: 12, fontWeight: 600, color: "var(--text-secondary)", textTransform: "uppercase" }}>
            Web Login Key
          </label>
          <input
            type="text"
            placeholder="e.g. 43TZRJ"
            value={inputCode}
            onChange={(e) => setInputCode(e.target.value.toUpperCase())}
            disabled={loading}
            style={{
              padding: "10px 14px",
              borderRadius: "var(--radius-sm)",
              border: "1px solid var(--border-strong)",
              background: "var(--surface)",
              color: "var(--text)",
              fontSize: 16,
              fontWeight: 600,
              letterSpacing: 2,
              textTransform: "uppercase",
              fontFamily: "ui-monospace, monospace",
            }}
          />

          <button
            type="submit"
            className="page-btn primary"
            disabled={loading}
            style={{
              padding: "10px 16px",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 8,
              fontSize: 14,
            }}
          >
            {loading ? (
              <>
                <Loader2 size={16} className="spinner" /> Verifying Key...
              </>
            ) : (
              <>
                <ShieldCheck size={16} /> Connect Account
              </>
            )}
          </button>
        </form>
      </div>
    </>
  );
}
