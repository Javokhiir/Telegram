import net from 'node:net';
import { kv } from './kv.js';
import { flush } from './meter.js';
import { dayKey } from './users.js';

// Admin-only read models: plan usage, live activity, Telegram reachability, countries, proxy list.

// Plan limits are editable in the panel; these are the published free/Hobby defaults.
export const DEFAULT_LIMITS = {
  vercelInvocations: 1000000,   // Vercel Hobby: function invocations / month
  vercelGbHours: 360,           // Vercel Hobby: provisioned memory GB-hrs / month
  vercelMemoryGb: 2,            // memory per function used to turn wall time into GB-hrs
  vercelTransferGb: 10,         // Vercel Hobby: fast origin transfer GB / month (function responses)
  redisCommands: 500000,        // Upstash free: commands / month
  redisStorageMb: 256,          // Upstash free: data size
  mapboxMau: 25000,             // Mapbox Maps SDK mobile: free monthly active users
};

export async function getLimits() {
  const saved = (await kv.hgetall('um:limits')) || {};
  const limits = { ...DEFAULT_LIMITS };
  for (const k of Object.keys(limits)) {
    if (saved[k] != null && Number(saved[k]) > 0) limits[k] = Number(saved[k]);
  }
  return limits;
}

export async function setLimits(input) {
  const clean = {};
  for (const k of Object.keys(DEFAULT_LIMITS)) {
    const v = Number(input?.[k]);
    if (v > 0 && v < 1e12) clean[k] = v;
  }
  if (Object.keys(clean).length) await kv.hset('um:limits', clean);
  return getLimits();
}

async function redisInfo() {
  const url = process.env.KV_REST_API_URL, token = process.env.KV_REST_API_TOKEN;
  if (!url || !token) return null;
  try {
    const r = await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: JSON.stringify(['INFO']) });
    const text = (await r.json()).result;
    if (typeof text !== 'string') return null;
    const get = (k) => { const m = text.match(new RegExp(`${k}:(\\d+)`)); return m ? Number(m[1]) : null; };
    return { memoryBytes: get('used_memory'), totalCommands: get('total_commands_processed') };
  } catch {
    return null;
  }
}

/** Month-to-date usage of every paid service we depend on. */
export async function usageStats() {
  await flush().catch(() => {});
  const now = Date.now();
  const day = dayKey(now);
  const month = day.slice(0, 7);
  const daysInMonth = new Date(Date.UTC(+month.slice(0, 4), +month.slice(5, 7), 0)).getUTCDate();
  const dayOfMonth = +day.slice(8, 10);
  const days = [];
  for (let d = 1; d <= dayOfMonth; d++) days.push(`${month}-${String(d).padStart(2, '0')}`);

  const p = kv.pipeline();
  p.hgetall(`um:usage:${month}`);
  p.scard(`um:mapmau:${month}`);
  p.dbsize();
  days.forEach((d) => p.hgetall(`um:daily:${d}`));
  const [usage, mau, dbsize, ...daily] = await p.exec();
  const [limits, info] = await Promise.all([getLimits(), redisInfo()]);
  const u = usage || {};
  const functions = Object.entries(u).filter(([k]) => k.startsWith('fn:')).map(([k, v]) => ({ name: k.slice(3), count: Number(v) })).sort((a, b) => b.count - a.count);
  const hours = Number(u.ms || 0) / 3600000;

  return {
    month, dayOfMonth, daysInMonth, limits,
    vercel: {
      invocations: Number(u.inv || 0),
      hours,
      gbHours: hours * limits.vercelMemoryGb,
      transferBytes: Number(u.bytes || 0),
      functions,
    },
    redis: {
      commands: Number(u.kv || 0),
      keys: Number(dbsize || 0),
      memoryBytes: info?.memoryBytes ?? null,
    },
    mapbox: { mau: Number(mau || 0) },
    daily: days.map((d, i) => ({ day: d, invocations: Number(daily[i]?.inv || 0), commands: Number(daily[i]?.kv || 0) })),
    since: Number((await kv.get('um:meter:since')) || 0) || null,
  };
}

/** Requests per minute for the last hour, the event feed and database round-trip time. */
export async function activityStats() {
  await flush().catch(() => {});
  const now = Date.now();
  const minuteNow = Math.floor(now / 60000);
  const hourNow = Math.floor(minuteNow / 60);
  const t0 = Date.now();
  const p = kv.pipeline();
  p.hgetall(`um:rpm:${hourNow - 1}`);
  p.hgetall(`um:rpm:${hourNow}`);
  p.lrange('um:events', 0, 39);
  const [prev, cur, events] = await p.exec();
  const latency = Date.now() - t0;
  const counts = { ...(prev || {}), ...(cur || {}) };
  const minutes = [];
  for (let m = minuteNow - 59; m <= minuteNow; m++) minutes.push({ t: m * 60000, count: Number(counts[m] || 0) });
  return {
    minutes,
    events: (events || []).map((e) => (typeof e === 'string' ? JSON.parse(e) : e)),
    dbLatencyMs: latency,
    generatedAt: now,
  };
}

const DCS = [
  { dc: 1, ip: '149.154.175.53', place: 'Mayami' },
  { dc: 2, ip: '149.154.167.51', place: 'Amsterdam' },
  { dc: 3, ip: '149.154.175.100', place: 'Mayami' },
  { dc: 4, ip: '149.154.167.91', place: 'Amsterdam' },
  { dc: 5, ip: '91.108.56.130', place: 'Singapur' },
];

function tcpPing(host, port, timeout = 2500) {
  return new Promise((resolve) => {
    const start = Date.now();
    const socket = net.connect({ host, port });
    const done = (ok) => { socket.destroy(); resolve(ok ? Date.now() - start : null); };
    socket.setTimeout(timeout, () => done(false));
    socket.once('connect', () => done(true));
    socket.once('error', () => done(false));
  });
}

/** TCP handshake time to each Telegram data center from this server. */
export async function telegramStatus() {
  const results = await Promise.all(DCS.map(async (d) => ({ ...d, ms: await tcpPing(d.ip, 443) })));
  return { dcs: results, region: process.env.VERCEL_REGION || null, checkedAt: Date.now() };
}

export async function countries() {
  const raw = (await kv.hgetall('um:countries')) || {};
  return Object.entries(raw).map(([code, n]) => ({ code, count: Number(n) })).sort((a, b) => b.count - a.count);
}

const DEFAULT_PROXIES = [{ name: 'Proksi 1', host: '198.13.49.231', port: 443 }];

export async function getProxies() {
  const raw = await kv.get('um:proxies');
  const list = raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : null;
  return Array.isArray(list) && list.length ? list : DEFAULT_PROXIES;
}

export async function setProxies(list) {
  const clean = (Array.isArray(list) ? list : [])
    .map((p) => ({ name: String(p.name || '').trim().slice(0, 40), host: String(p.host || '').trim().slice(0, 100), port: Number(p.port) }))
    .filter((p) => /^[A-Za-z0-9.-]+$/.test(p.host) && p.port > 0 && p.port < 65536)
    .slice(0, 20)
    .map((p, i) => ({ ...p, name: p.name || `Proksi ${i + 1}` }));
  await kv.set('um:proxies', JSON.stringify(clean));
  return getProxies();
}
