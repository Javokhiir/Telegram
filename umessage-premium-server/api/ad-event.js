import { getAd, countEvent } from '../lib/ads.js';
import { json, readBody } from '../lib/http.js';
import { withUsage, logEvent } from '../lib/meter.js';

// POST /api/ad-event { id, type: "view" | "click" } -> counts impressions and clicks for brand reports.
export default withUsage('ad-event', async function handler(req, res) {
  if (req.method !== 'POST') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  let body;
  try {
    body = await readBody(req);
  } catch {
    return json(res, 400, { error: 'bad_json' });
  }
  const { id, type } = body || {};
  if (typeof id !== 'string' || (type !== 'view' && type !== 'click')) {
    return json(res, 400, { error: 'bad_request' });
  }
  const ad = await getAd(id);
  if (!ad) {
    return json(res, 404, { error: 'not_found' });
  }
  await countEvent(id, type);
  if (type === 'click') await logEvent('ad', `Reklama bosildi · ${ad.brand}`);
  return json(res, 200, { ok: true });
});
