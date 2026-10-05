import { getRecord, putRecord, publicView } from '../lib/store.js';
import { json, readBody, isValidId } from '../lib/http.js';
import { touchUser } from '../lib/users.js';
import { kv } from '../lib/kv.js';
import { logEvent } from '../lib/meter.js';
import { withUsage } from '../lib/meter.js';

// POST /api/register  { userId, enabled? }
// Every U message user is premium: the client self-registers on launch, which grants premium
// (lifetime) so other U message users see them as premium. `enabled` is the user's display toggle.
export default withUsage('register', async function handler(req, res) {
  if (req.method !== 'POST') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  let body;
  try {
    body = await readBody(req);
  } catch {
    return json(res, 400, { error: 'bad_json' });
  }
  const { userId, enabled } = body || {};
  if (!isValidId(userId)) {
    return json(res, 400, { error: 'bad_user' });
  }
  const existing = await getRecord(userId);
  const record = existing || {};
  record.granted = true;
  record.until = null; // lifetime for every U message user
  record.enabled = typeof enabled === 'boolean' ? enabled : record.enabled !== false;
  const now = Date.now();
  record.firstSeen = record.firstSeen || now;
  record.lastSeen = now;
  // Vercel geolocates the caller's IP; counted once per user (older users get it on their next launch)
  const country = String(req.headers['x-vercel-ip-country'] || '').toUpperCase();
  const countNewCountry = /^[A-Z]{2}$/.test(country) && !record.country;
  if (countNewCountry) record.country = country;
  await putRecord(userId, record);
  try {
    await touchUser(userId, !existing);
    if (countNewCountry) await kv.hincrby('um:countries', country, 1);
    if (!existing) await logEvent('user', `Yangi foydalanuvchi${record.country ? ' · ' + record.country : ''}`);
  } catch {
    // counters are best-effort; never fail registration over them
  }
  return json(res, 200, publicView(userId, record));
});
