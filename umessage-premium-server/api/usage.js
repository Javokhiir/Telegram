import { kv } from '../lib/kv.js';
import { json, readBody, isValidId } from '../lib/http.js';
import { dayKey } from '../lib/users.js';
import { withUsage, logEvent } from '../lib/meter.js';

// POST /api/usage { userId, event: "map" }
// The app pings once a day when the Friend Map opens. Mapbox bills the mobile SDK per monthly active
// user, so the set size per month is our own estimate of Mapbox MAU (no location is sent here).
export default withUsage('usage', async function handler(req, res) {
  if (req.method !== 'POST') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  let body;
  try {
    body = await readBody(req);
  } catch {
    return json(res, 400, { error: 'bad_json' });
  }
  const { userId, event } = body || {};
  if (!isValidId(userId) || event !== 'map') {
    return json(res, 400, { error: 'bad_request' });
  }
  const day = dayKey();
  const month = day.slice(0, 7);
  const p = kv.pipeline();
  p.sadd(`um:mapmau:${month}`, String(userId));
  p.expire(`um:mapmau:${month}`, 70 * 86400);
  p.hincrby('um:mapopens', day, 1);
  const [added] = await p.exec();
  if (added) await logEvent('map', 'Do‘stlar xaritasi ochildi (oyda birinchi marta)');
  return json(res, 200, { ok: true });
});
