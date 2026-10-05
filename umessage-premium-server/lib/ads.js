import { kv } from './kv.js';

// Brand ads shown inside U message (bottom banner on feature pages, full-screen card between stories).
// ad:<id>            -> ad record (JSON)
// adimg:<id>:<kind>  -> { mime, data(base64) } for kind = logo | art
// ads:index          -> set of ad ids
// adstat:<id>:<type> -> counter for type = view | click

const adKey = (id) => `ad:${id}`;
const imgKey = (id, kind) => `adimg:${id}:${kind}`;
const INDEX = 'ads:index';

export const PLACEMENTS = ['banner', 'story'];
export const MAX_IMAGE_BYTES = 700 * 1024;

const parse = (raw) => (raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : null);

export async function getAd(id) {
  return parse(await kv.get(adKey(id)));
}

export async function saveAd(ad) {
  ad.updatedAt = Date.now();
  await kv.set(adKey(ad.id), JSON.stringify(ad));
  await kv.sadd(INDEX, ad.id);
  return ad;
}

export async function deleteAd(id) {
  await kv.del(adKey(id), imgKey(id, 'logo'), imgKey(id, 'art'), `adstat:${id}:view`, `adstat:${id}:click`);
  await kv.srem(INDEX, id);
}

export async function listAds() {
  const ids = (await kv.smembers(INDEX)) || [];
  const ads = await Promise.all(ids.map(getAd));
  return ads.filter(Boolean).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}

export function isLive(ad, now = Date.now()) {
  return ad.active !== false && (!ad.start || ad.start <= now) && (!ad.end || ad.end > now);
}

export async function putImage(id, kind, mime, base64) {
  await kv.set(imgKey(id, kind), JSON.stringify({ mime, data: base64 }));
}

export async function getImage(id, kind) {
  return parse(await kv.get(imgKey(id, kind)));
}

export async function countEvent(id, type) {
  await kv.incr(`adstat:${id}:${type}`);
}

export async function getStats(id) {
  const [views, clicks] = await Promise.all([kv.get(`adstat:${id}:view`), kv.get(`adstat:${id}:click`)]);
  return { views: Number(views) || 0, clicks: Number(clicks) || 0 };
}

/** What the app receives: no stats, image URLs instead of image data. */
export function publicAd(ad, baseUrl) {
  const v = ad.updatedAt || 0;
  return {
    id: ad.id,
    brand: ad.brand,
    headline: ad.headline,
    url: ad.url,
    colorStart: ad.colorStart,
    colorEnd: ad.colorEnd,
    ctaColor: ad.ctaColor,
    placements: ad.placements || PLACEMENTS,
    logoUrl: ad.hasLogo ? `${baseUrl}/api/ad-image?id=${ad.id}&kind=logo&v=${v}` : null,
    artUrl: ad.hasArt ? `${baseUrl}/api/ad-image?id=${ad.id}&kind=art&v=${v}` : null,
  };
}

const CONFIG_KEY = 'ads:config';
export const DEFAULT_CONFIG = { storyEvery: 3, storyDurationSec: 6, bannerEnabled: true, storyEnabled: true };

export async function getConfig() {
  return { ...DEFAULT_CONFIG, ...(parse(await kv.get(CONFIG_KEY)) || {}) };
}

export async function setConfig(patch) {
  const config = await getConfig();
  if (Number.isInteger(patch.storyEvery) && patch.storyEvery >= 1 && patch.storyEvery <= 50) config.storyEvery = patch.storyEvery;
  if (Number.isInteger(patch.storyDurationSec) && patch.storyDurationSec >= 3 && patch.storyDurationSec <= 30) config.storyDurationSec = patch.storyDurationSec;
  if (typeof patch.bannerEnabled === 'boolean') config.bannerEnabled = patch.bannerEnabled;
  if (typeof patch.storyEnabled === 'boolean') config.storyEnabled = patch.storyEnabled;
  await kv.set(CONFIG_KEY, JSON.stringify(config));
  return config;
}

export function newId() {
  return Math.random().toString(36).slice(2, 8);
}
