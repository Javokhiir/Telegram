import { kv } from '../lib/kv.js';
import { json, readBody } from '../lib/http.js';
import { withUsage } from '../lib/meter.js';

// Friend Map relay. Clients pair over Telegram messages and share a secret key; each side writes its
// position AES-GCM-encrypted under an id derived from that key (HMAC), so this server only stores
// opaque blobs and cannot tell who is where or who is paired with whom.
//
// GET  /api/loc?ids=a,b,c                → { items: { a: "<blob>", b: null } }
// POST /api/loc { items: [{ id, data }] } → data = "<blob>" writes (7 day TTL), data = null deletes

const TTL_SECONDS = 7 * 24 * 3600;
const MAX_ITEMS = 100;
const MAX_DATA = 1024;
const key = (id) => `umloc:${id}`;
const isId = (id) => typeof id === 'string' && /^[0-9a-f]{32}$/.test(id);

export default withUsage('loc', async function handler(req, res) {
  if (req.method === 'GET') {
    const ids = String(req.query.ids || '')
      .split(',')
      .filter(isId)
      .slice(0, MAX_ITEMS);
    if (!ids.length) {
      return json(res, 200, { items: {} });
    }
    const values = await kv.mget(...ids.map(key));
    const items = {};
    ids.forEach((id, i) => {
      items[id] = typeof values[i] === 'string' ? values[i] : null;
    });
    return json(res, 200, { items });
  }
  if (req.method === 'POST') {
    let body;
    try {
      body = await readBody(req);
    } catch {
      return json(res, 400, { error: 'bad_json' });
    }
    const items = Array.isArray(body && body.items) ? body.items.slice(0, MAX_ITEMS) : [];
    let written = 0;
    for (const item of items) {
      if (!item || !isId(item.id)) continue;
      if (item.data === null) {
        await kv.del(key(item.id));
      } else if (typeof item.data === 'string' && item.data.length <= MAX_DATA && /^[A-Za-z0-9+/=_-]+$/.test(item.data)) {
        await kv.set(key(item.id), item.data, { ex: TTL_SECONDS });
      } else {
        continue;
      }
      written++;
    }
    return json(res, 200, { written });
  }
  return json(res, 405, { error: 'method_not_allowed' });
});
