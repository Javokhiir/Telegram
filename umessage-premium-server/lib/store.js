import { kv } from '@vercel/kv';

// One record per user: whether U message premium was granted, until when, whether the user shows it,
// and which premium sticker/emoji set they picked. This is an app-only flag, unrelated to Telegram Premium.
const key = (userId) => `umpremium:${userId}`;

export async function getRecord(userId) {
  const raw = await kv.get(key(userId));
  if (!raw) return null;
  return typeof raw === 'string' ? JSON.parse(raw) : raw;
}

export async function putRecord(userId, record) {
  await kv.set(key(userId), JSON.stringify(record));
}

/** Public view of a user's premium state. `premium` is true only while a grant is active. */
export function publicView(userId, record) {
  const now = Date.now();
  const active = !!record && record.granted === true && (!record.until || record.until > now);
  return {
    userId: String(userId),
    premium: active,
    // A premium user may hide the badge/features; default on.
    enabled: active && record.enabled !== false,
    stickerSet: (record && record.stickerSet) || null,
    until: (record && record.until) || null,
  };
}

export function parseIds(value) {
  if (!value) return [];
  return String(value)
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^\d{1,20}$/.test(s))
    .slice(0, 100);
}
