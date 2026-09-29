import { getRecord, putRecord, publicView } from '../lib/store.js';
import { json, readBody, isValidId, checkAdmin } from '../lib/http.js';

// POST /api/grant  { userId, days? }   header: x-admin-secret: <ADMIN_SECRET>
// Grants (or extends) U message premium. days omitted or 0 => lifetime. Negative days => revoke.
export default async function handler(req, res) {
  if (req.method !== 'POST') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  if (!checkAdmin(req)) {
    return json(res, 401, { error: 'unauthorized' });
  }
  let body;
  try {
    body = await readBody(req);
  } catch {
    return json(res, 400, { error: 'bad_json' });
  }
  const { userId, days } = body || {};
  if (!isValidId(userId)) {
    return json(res, 400, { error: 'bad_user' });
  }
  const record = (await getRecord(userId)) || { enabled: true };
  if (typeof days === 'number' && days < 0) {
    record.granted = false;
    record.until = null;
  } else {
    record.granted = true;
    record.until = typeof days === 'number' && days > 0 ? Date.now() + days * 86400000 : null;
    if (record.enabled === undefined) record.enabled = true;
  }
  await putRecord(userId, record);
  return json(res, 200, publicView(userId, record));
}
