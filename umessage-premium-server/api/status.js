import { getRecord, publicView, parseIds } from '../lib/store.js';
import { json } from '../lib/http.js';
import { withUsage } from '../lib/meter.js';

// GET /api/status?ids=123,456  -> { users: { "123": {premium, enabled, stickerSet, until}, ... } }
// The client calls this to learn which U message users (in a chat list, profile, etc.) are premium.
export default withUsage('status', async function handler(req, res) {
  if (req.method !== 'GET') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  const ids = parseIds(req.query.ids);
  if (ids.length === 0) {
    return json(res, 400, { error: 'no_ids' });
  }
  const users = {};
  await Promise.all(
    ids.map(async (id) => {
      const record = await getRecord(id);
      users[id] = publicView(id, record);
    })
  );
  return json(res, 200, { users });
});
