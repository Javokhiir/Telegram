import { rawKv, takeCommandCount } from './kv.js';
import { dayKey } from './users.js';

// Self-measured usage. Vercel's usage/metrics APIs are not available on the Hobby plan, so every
// handler is wrapped: invocations, wall time and response bytes are summed in memory and flushed to
// Redis at most every 15 s (a few commands per flush instead of several per request).
//
// um:usage:<YYYY-MM>   hash: inv, ms, bytes, kv, fn:<name>        (month totals)
// um:daily:<YYYY-MM-DD> hash: inv, kv                               (kept 40 days)
// um:rpm:<epoch hour>  hash: <minute> -> requests                   (kept 3 h)
// um:events            list of recent notable events (newest first, 100 kept)

const FLUSH_MS = 15000;
let acc = { inv: 0, ms: 0, bytes: 0, fn: {}, minutes: {} };
let lastFlush = Date.now();

export function withUsage(name, handler) {
  return async function metered(req, res) {
    const start = Date.now();
    let bytes = 0;
    const end = res.end.bind(res);
    res.end = (chunk, ...rest) => {
      if (chunk) bytes += typeof chunk === 'string' ? Buffer.byteLength(chunk) : chunk.length || 0;
      return end(chunk, ...rest);
    };
    try {
      return await handler(req, res);
    } finally {
      const minute = Math.floor(start / 60000);
      acc.inv++;
      acc.ms += Date.now() - start;
      acc.bytes += bytes;
      acc.fn[name] = (acc.fn[name] || 0) + 1;
      acc.minutes[minute] = (acc.minutes[minute] || 0) + 1;
      if (Date.now() - lastFlush >= FLUSH_MS || name === 'admin') {
        await flush().catch(() => {});
      }
    }
  };
}

export async function flush() {
  const data = acc;
  acc = { inv: 0, ms: 0, bytes: 0, fn: {}, minutes: {} };
  lastFlush = Date.now();
  if (!data.inv) return;
  const now = Date.now();
  const day = dayKey(now);
  const month = day.slice(0, 7);
  const monthKey = `um:usage:${month}`;
  const dailyKey = `um:daily:${day}`;
  const p = rawKv.pipeline();
  p.hincrby(monthKey, 'inv', data.inv);
  p.hincrby(monthKey, 'ms', data.ms);
  p.hincrby(monthKey, 'bytes', data.bytes);
  for (const [fn, n] of Object.entries(data.fn)) p.hincrby(monthKey, `fn:${fn}`, n);
  p.hincrby(dailyKey, 'inv', data.inv);
  p.expire(dailyKey, 40 * 86400);
  for (const [minute, n] of Object.entries(data.minutes)) {
    const hourKey = `um:rpm:${Math.floor(minute / 60)}`;
    p.hincrby(hourKey, String(minute), n);
    p.expire(hourKey, 3 * 3600);
  }
  p.set('um:meter:since', now, { nx: true });
  // this flush's own commands are counted too
  const kvCommands = takeCommandCount() + p.length() + 2;
  p.hincrby(monthKey, 'kv', kvCommands);
  p.hincrby(dailyKey, 'kv', kvCommands);
  await p.exec();
}

/** Appends a line to the live activity feed (2 commands; use for notable events only). */
export async function logEvent(type, text) {
  try {
    const p = rawKv.pipeline();
    p.lpush('um:events', JSON.stringify({ t: Date.now(), type, text }));
    p.ltrim('um:events', 0, 99);
    await p.exec();
  } catch {
    // the feed is best-effort
  }
}
