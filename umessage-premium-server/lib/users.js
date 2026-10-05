import { kv } from './kv.js';

// Usage counters for the admin dashboard. The app calls /api/register on launch (at most every 6 h),
// which is our "this user is active" signal.
// um:lastseen        -> sorted set userId -> last seen epoch ms (its size = all registered users)
// um:dau:<YYYY-MM-DD> -> set of users seen that day (Tashkent time), kept 400 days
// um:mau:<YYYY-MM>   -> set of users seen that month, kept 400 days
// um:snaps           -> hash day -> {d1,d7,d30,total}: rolling active counts as of that day
// um:signups         -> hash day -> new users that day
// um:backfilled      -> flag: users registered before these counters existed were imported

const SEEN = 'um:lastseen';
const SIGNUPS = 'um:signups';
const DAY_MS = 86400000;
const TZ_OFFSET_MS = 5 * 3600000; // Asia/Tashkent, no DST

export const dayKey = (t = Date.now()) => new Date(t + TZ_OFFSET_MS).toISOString().slice(0, 10);

export async function touchUser(userId, isNew) {
  const now = Date.now();
  const day = dayKey(now);
  const id = String(userId);
  const p = kv.pipeline();
  p.zadd(SEEN, { score: now, member: id });
  p.sadd(`um:dau:${day}`, id);
  p.expire(`um:dau:${day}`, 400 * 86400);
  p.sadd(`um:mau:${day.slice(0, 7)}`, id);
  p.expire(`um:mau:${day.slice(0, 7)}`, 400 * 86400);
  if (isNew) p.hincrby(SIGNUPS, day, 1);
  await p.exec();
  if (now - lastSnapshot > 3600000) await snapshot(now).catch(() => {});
}

let lastSnapshot = 0;

/** Stores today's rolling active counts so weekly/monthly actives can be charted over time. */
async function snapshot(now = Date.now()) {
  lastSnapshot = now;
  const p = kv.pipeline();
  p.zcount(SEEN, now - DAY_MS, '+inf');
  p.zcount(SEEN, now - 7 * DAY_MS, '+inf');
  p.zcount(SEEN, now - 30 * DAY_MS, '+inf');
  p.zcard(SEEN);
  const [d1, d7, d30, total] = await p.exec();
  const snap = { d1: Number(d1 || 0), d7: Number(d7 || 0), d30: Number(d30 || 0), total: Number(total || 0) };
  await kv.hset('um:snaps', { [dayKey(now)]: JSON.stringify(snap) });
  return snap;
}

/** Buckets for a dashboard range: days for 7d/30d, months for 12m. */
export function buckets(range, now = Date.now()) {
  if (range === '12m') {
    const m = dayKey(now).slice(0, 7);
    const y = +m.slice(0, 4), mo = +m.slice(5, 7);
    const out = [];
    for (let i = 11; i >= 0; i--) {
      const d = new Date(Date.UTC(y, mo - 1 - i, 1));
      const key = d.toISOString().slice(0, 7);
      const days = [];
      const dim = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
      for (let k = 1; k <= dim; k++) {
        const dk = `${key}-${String(k).padStart(2, '0')}`;
        if (dk <= dayKey(now)) days.push(dk);
      }
      out.push({ key, days });
    }
    return out;
  }
  const n = range === '7d' ? 7 : 30;
  const out = [];
  for (let i = n - 1; i >= 0; i--) {
    const dk = dayKey(now - i * DAY_MS);
    out.push({ key: dk, days: [dk] });
  }
  return out;
}

async function scanCount(match, limit = 200) {
  let cursor = 0;
  let count = 0;
  const keys = [];
  for (let i = 0; i < limit; i++) {
    const [next, batch] = await kv.scan(cursor, { match, count: 1000 });
    count += batch.length;
    keys.push(...batch);
    cursor = Number(next);
    if (!cursor) break;
  }
  return { count, keys };
}

/** Imports users that registered before the counters existed (score 0 = last activity unknown). */
async function backfill() {
  if (await kv.get('um:backfilled')) return;
  const { keys } = await scanCount('umpremium:*');
  const ids = keys.map((k) => k.slice('umpremium:'.length)).filter((id) => /^\d{1,20}$/.test(id));
  for (let i = 0; i < ids.length; i += 500) {
    const p = kv.pipeline();
    ids.slice(i, i + 500).forEach((id) => p.zadd(SEEN, { nx: true }, { score: 0, member: id }));
    await p.exec();
  }
  await kv.set('um:backfilled', 1);
}

export async function userStats(range = '30d') {
  await backfill();
  const now = Date.now();
  const bs = buckets(range, now);
  const monthly = range === '12m';
  const allDays = bs.flatMap((b) => b.days);

  const current = await snapshot(now);
  const p = kv.pipeline();
  p.hgetall(SIGNUPS);
  p.hgetall('um:snaps');
  allDays.forEach((d) => p.scard(`um:dau:${d}`));
  if (monthly) bs.forEach((b) => p.scard(`um:mau:${b.key}`));
  const [signupsRaw, snapsRaw, ...rest] = await p.exec();
  const signups = signupsRaw || {}, snaps = snapsRaw || {};
  const dauByDay = {};
  allDays.forEach((d, i) => { dauByDay[d] = Number(rest[i] || 0); });
  const mau = monthly ? rest.slice(allDays.length).map(Number) : [];
  const snapOf = (d) => { const v = snaps[d]; if (!v) return null; return typeof v === 'string' ? JSON.parse(v) : v; };

  const points = bs.map((b, i) => {
    const newUsers = b.days.reduce((s, d) => s + Number(signups[d] || 0), 0);
    if (monthly) {
      const seen = b.days.filter((d) => dauByDay[d] > 0);
      const avgDaily = seen.length ? Math.round(seen.reduce((s, d) => s + dauByDay[d], 0) / seen.length) : null;
      return { key: b.key, dau: avgDaily, wau: null, mau: mau[i] || null, signups: newUsers };
    }
    const snap = snapOf(b.key);
    return { key: b.key, dau: dauByDay[b.key] || null, wau: snap ? snap.d7 : null, mau: snap ? snap.d30 : null, signups: newUsers };
  });
  const mapKeys = await scanCount('umloc:*', 20);

  return {
    range,
    total: current.total,
    active24h: current.d1,
    active7d: current.d7,
    active30d: current.d30,
    newInRange: points.reduce((s, x) => s + x.signups, 0),
    newToday: Number(signups[dayKey(now)] || 0),
    mapSharing: mapKeys.count, // live encrypted Friend Map positions (one per user per friend)
    points,
    generatedAt: now,
  };
}

/** Best-effort Redis memory figure via the REST endpoint (INFO may be unavailable on some plans). */
async function redisMemory() {
  const url = process.env.KV_REST_API_URL, token = process.env.KV_REST_API_TOKEN;
  if (!url || !token) return null;
  try {
    const r = await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: JSON.stringify(['INFO', 'memory']) });
    const data = await r.json();
    const text = typeof data.result === 'string' ? data.result : '';
    const m = text.match(/used_memory:(\d+)/);
    return m ? Number(m[1]) : null;
  } catch {
    return null;
  }
}

/** Mapbox (our own MAU estimate) and database usage for the dashboard. */
export async function infraStats(range = '30d') {
  const now = Date.now();
  const month = dayKey(now).slice(0, 7);
  const prev = new Date(Date.UTC(+month.slice(0, 4), +month.slice(5, 7) - 2, 1)).toISOString().slice(0, 7);
  const bs = buckets(range, now);

  const p = kv.pipeline();
  p.scard(`um:mapmau:${month}`);
  p.scard(`um:mapmau:${prev}`);
  p.hgetall('um:mapopens');
  p.dbsize();
  const [mau, mauPrev, opensRaw, dbsize] = await p.exec();
  const opens = opensRaw || {};

  const [users, ads, map, stats, memory] = await Promise.all([
    scanCount('umpremium:*'),
    scanCount('ad*'),
    scanCount('umloc:*', 20),
    scanCount('um:*'),
    redisMemory(),
  ]);
  const keys = Number(dbsize || 0);
  return {
    mapbox: {
      month,
      mau: Number(mau || 0),
      mauPrev: Number(mauPrev || 0),
      freeMau: 25000, // Mapbox Maps SDK for mobile free tier (check your Mapbox plan)
      opens: bs.map((b) => ({ key: b.key, count: b.days.reduce((s, d) => s + Number(opens[d] || 0), 0) })),
    },
    db: {
      keys,
      memoryBytes: memory,
      groups: [
        { name: 'Foydalanuvchilar', keys: users.count },
        { name: 'Reklamalar', keys: ads.count },
        { name: 'Do‘stlar xaritasi', keys: map.count },
        { name: 'Statistika', keys: stats.count },
        { name: 'Boshqa', keys: Math.max(0, keys - users.count - ads.count - map.count - stats.count) },
      ],
    },
  };
}
