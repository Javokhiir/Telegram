import { getImage } from '../lib/ads.js';
import { json } from '../lib/http.js';
import { withUsage } from '../lib/meter.js';

// GET /api/ad-image?id=<id>&kind=logo|art -> the image bytes (cached by the CDN; URL changes when the ad changes).
export default withUsage('ad-image', async function handler(req, res) {
  const { id, kind } = req.query;
  if (!id || (kind !== 'logo' && kind !== 'art')) {
    return json(res, 400, { error: 'bad_request' });
  }
  const image = await getImage(String(id), kind);
  if (!image) {
    return json(res, 404, { error: 'not_found' });
  }
  res.setHeader('Content-Type', image.mime || 'image/png');
  res.setHeader('Cache-Control', 'public, max-age=86400, s-maxage=604800, immutable');
  res.status(200).send(Buffer.from(image.data, 'base64'));
});
