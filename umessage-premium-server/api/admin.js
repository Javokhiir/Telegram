import { getAd, saveAd, deleteAd, listAds, putImage, getStats, getConfig, setConfig, newId, PLACEMENTS, MAX_IMAGE_BYTES, isLive } from '../lib/ads.js';
import { json, readBody, checkAdmin } from '../lib/http.js';
import { userStats, infraStats } from '../lib/users.js';
import { usageStats, activityStats, telegramStatus, countries, getProxies, setProxies, setLimits } from '../lib/insights.js';
import { withUsage } from '../lib/meter.js';

// Admin API for the ads panel (/admin). Every call needs header x-admin-secret: <ADMIN_SECRET>.
// GET                                   -> { ads: [...with stats, live], config, users: dashboard counters }
// POST { action: "save", ad, logo?, art? }   logo/art = { mime, data(base64) } | "remove"
// POST { action: "delete", id }
// POST { action: "config", config }

const HEX = /^#[0-9a-fA-F]{6}$/;
const MIMES = ['image/png', 'image/jpeg', 'image/webp'];

function cleanAd(input, existing) {
  const ad = existing ? { ...existing } : { id: newId(), createdAt: Date.now(), active: true };
  const str = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
  ad.brand = str(input.brand, 40);
  ad.headline = str(input.headline, 90);
  ad.url = str(input.url, 300);
  if (/^t\.me\//i.test(ad.url)) ad.url = `https://${ad.url}`;
  if (!ad.brand || !ad.headline || !/^https?:\/\/\S+$/i.test(ad.url)) return { error: 'brand, headline va to‘g‘ri url kerak' };
  for (const k of ['colorStart', 'colorEnd', 'ctaColor']) {
    if (!HEX.test(input[k] || '')) return { error: `${k} rangi noto‘g‘ri` };
    ad[k] = input[k].toLowerCase();
  }
  ad.placements = Array.isArray(input.placements) ? input.placements.filter((p) => PLACEMENTS.includes(p)) : PLACEMENTS;
  if (!ad.placements.length) return { error: 'kamida bitta joy tanlang' };
  ad.start = Number(input.start) || null;
  ad.end = Number(input.end) || null;
  if (ad.start && ad.end && ad.end <= ad.start) return { error: 'tugash sanasi boshlanishdan keyin bo‘lsin' };
  ad.active = input.active !== false;
  return { ad };
}

async function applyImage(ad, kind, value) {
  const flag = kind === 'logo' ? 'hasLogo' : 'hasArt';
  if (value === 'remove') {
    ad[flag] = false;
    return null;
  }
  if (!value) return null;
  if (!MIMES.includes(value.mime) || typeof value.data !== 'string') return `${kind}: faqat PNG, JPG yoki WEBP`;
  if (Buffer.byteLength(value.data, 'base64') > MAX_IMAGE_BYTES) return `${kind}: fayl ${MAX_IMAGE_BYTES / 1024} KB dan katta`;
  await putImage(ad.id, kind, value.mime, value.data);
  ad[flag] = true;
  return null;
}

export default withUsage('admin', async function handler(req, res) {
  if (!checkAdmin(req)) {
    return json(res, 401, { error: 'unauthorized' });
  }
  if (req.method === 'GET' && req.query.part === 'usage') {
    return json(res, 200, await usageStats());
  }
  if (req.method === 'GET' && req.query.part === 'status') {
    const [activity, telegram] = await Promise.all([activityStats(), telegramStatus()]);
    return json(res, 200, { activity, telegram });
  }
  if (req.method === 'GET') {
    const range = ['7d', '30d', '12m'].includes(req.query.range) ? req.query.range : '30d';
    const [ads, config, users, infra, countryList, proxies] = await Promise.all([listAds(), getConfig(), userStats(range).catch(() => null), infraStats(range).catch(() => null), countries().catch(() => []), getProxies().catch(() => [])]);
    const withStats = await Promise.all(ads.map(async (ad) => ({ ...ad, live: isLive(ad), stats: await getStats(ad.id) })));
    return json(res, 200, { ads: withStats, config, users, infra, countries: countryList, proxies });
  }
  if (req.method !== 'POST') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  let body;
  try {
    body = await readBody(req);
  } catch {
    return json(res, 400, { error: 'bad_json' });
  }
  const { action } = body || {};

  if (action === 'save') {
    const existing = body.ad?.id ? await getAd(body.ad.id) : null;
    const { ad, error } = cleanAd(body.ad || {}, existing);
    if (error) return json(res, 400, { error });
    for (const kind of ['logo', 'art']) {
      const imgError = await applyImage(ad, kind, body[kind]);
      if (imgError) return json(res, 400, { error: imgError });
    }
    await saveAd(ad);
    return json(res, 200, { ad });
  }
  if (action === 'delete' && typeof body.id === 'string') {
    await deleteAd(body.id);
    return json(res, 200, { ok: true });
  }
  if (action === 'limits') {
    return json(res, 200, { limits: await setLimits(body.limits) });
  }
  if (action === 'proxies') {
    return json(res, 200, { proxies: await setProxies(body.proxies) });
  }
  if (action === 'config') {
    return json(res, 200, { config: await setConfig(body.config || {}) });
  }
  return json(res, 400, { error: 'unknown_action' });
});
