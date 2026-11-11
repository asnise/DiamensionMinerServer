// Fixed-window, in-memory rate limiter. Good for a single `next dev` / `next start` process;
// move to the database or Redis if the console ever runs on more than one instance.
const buckets = (globalThis.__dmRateBuckets ??= new Map());

/**
 * Counts one attempt for `key`.
 * @returns {{ allowed: boolean, retryAfterSeconds: number }}
 */
export function hit(key, limit, windowMs) {
  const now = Date.now();
  let b = buckets.get(key);
  if (!b || b.resetAt <= now) {
    b = { count: 0, resetAt: now + windowMs };
    buckets.set(key, b);
  }
  b.count += 1;

  if (buckets.size > 10_000) {
    for (const [k, v] of buckets) if (v.resetAt <= now) buckets.delete(k);
  }

  return {
    allowed: b.count <= limit,
    retryAfterSeconds: Math.max(1, Math.ceil((b.resetAt - now) / 1000)),
  };
}
