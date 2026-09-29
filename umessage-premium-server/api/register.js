import { getRecord, putRecord, publicView } from '../lib/store.js';
import { json, readBody, isValidId } from '../lib/http.js';

// POST /api/register  { userId, enabled? }
// Every U message user is premium: the client self-registers on launch, which grants premium
// (lifetime) so other U message users see them as premium. `enabled` is the user's display toggle.
export default async function handler(req, res) {
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
  const record = (await getRecord(userId)) || {};
  record.granted = true;
  record.until = null; // lifetime for every U message user
  record.enabled = typeof enabled === 'boolean' ? enabled : record.enabled !== false;
  await putRecord(userId, record);
  return json(res, 200, publicView(userId, record));
}
