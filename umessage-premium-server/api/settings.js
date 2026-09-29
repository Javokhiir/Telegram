import { getRecord, putRecord, publicView } from '../lib/store.js';
import { json, readBody, isValidId } from '../lib/http.js';

// POST /api/settings  { userId, enabled?, stickerSet? }
// A premium user turns their own badge/features on or off and picks a premium sticker/emoji set.
// MVP trust model: the caller sends its own userId. Tighten later with Telegram login verification.
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
  const { userId, enabled, stickerSet } = body || {};
  if (!isValidId(userId)) {
    return json(res, 400, { error: 'bad_user' });
  }
  const record = (await getRecord(userId)) || {};
  if (!record.granted) {
    return json(res, 403, { error: 'not_premium' });
  }
  if (typeof enabled === 'boolean') {
    record.enabled = enabled;
  }
  if (typeof stickerSet === 'string' || stickerSet === null) {
    record.stickerSet = stickerSet;
  }
  await putRecord(userId, record);
  return json(res, 200, publicView(userId, record));
}
