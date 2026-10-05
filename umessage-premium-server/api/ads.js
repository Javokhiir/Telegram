import { listAds, isLive, publicAd, getConfig } from '../lib/ads.js';
import { json } from '../lib/http.js';
import { withUsage } from '../lib/meter.js';

// GET /api/ads -> { ads: [ {id, brand, headline, url, colors, placements, logoUrl, artUrl} ] }
// Only ads that are switched on and inside their contract dates.
export default withUsage('ads', async function handler(req, res) {
  if (req.method !== 'GET') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  const baseUrl = `https://${req.headers.host}`;
  const [all, config] = await Promise.all([listAds(), getConfig()]);
  const ads = all.filter((ad) => isLive(ad)).map((ad) => publicAd(ad, baseUrl));
  return json(res, 200, { ads, config });
});
