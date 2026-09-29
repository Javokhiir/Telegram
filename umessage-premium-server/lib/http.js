// Small shared helpers for the serverless handlers.

export function json(res, status, body) {
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Cache-Control', 'no-store');
  res.status(status).send(JSON.stringify(body));
}

export async function readBody(req) {
  if (req.body) {
    return typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
  }
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  return raw ? JSON.parse(raw) : {};
}

export function isValidId(id) {
  return /^\d{1,20}$/.test(String(id || ''));
}

/** Admin actions require the shared secret from the ADMIN_SECRET env var. */
export function checkAdmin(req) {
  const secret = process.env.ADMIN_SECRET;
  if (!secret) return false;
  const provided = req.headers['x-admin-secret'];
  return typeof provided === 'string' && provided === secret;
}
