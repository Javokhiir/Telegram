# U message premium — backend

An app-only "premium" flag for **U message** users. It has **nothing to do with real Telegram
Premium**: it only tells the U message client which of its own users the app should show a premium
badge for and unlock premium sticker/emoji features for, inside U message.

## What it stores

One record per Telegram user id (Vercel KV):

```json
{ "granted": true, "until": 1735689600000, "enabled": true, "stickerSet": "<id or null>" }
```

- `granted` — an admin turned premium on for this user.
- `until` — expiry epoch ms, or `null` for lifetime.
- `enabled` — the user's own toggle (show/hide their premium in the app).
- `stickerSet` — the premium sticker/emoji set the user chose to display.

## Endpoints

| Method | Path | Body / Query | Who |
|-------|------|--------------|-----|
| GET | `/api/status?ids=1,2,3` | up to 100 ids | client — batch lookup for chat lists/profiles |
| POST | `/api/settings` | `{ userId, enabled?, stickerSet? }` | a premium user changes their own display |
| POST | `/api/grant` | `{ userId, days? }` + header `x-admin-secret` | admin grants/extends/revokes (days<0 revokes, 0/absent = lifetime) |

`/api/status` returns `{ users: { "1": { premium, enabled, stickerSet, until } } }`.

## Deploy (Vercel)

1. `npm i -g vercel` and `vercel login` (or use the dashboard).
2. From this folder: `vercel` (first run links/creates the project), then `vercel --prod`.
3. Add **Vercel KV** to the project (Storage tab) — it injects `KV_REST_API_URL` and
   `KV_REST_API_TOKEN` automatically.
4. Set an env var **`ADMIN_SECRET`** (a long random string) for the `/api/grant` header.

## Grant premium to a user

```bash
curl -X POST https://<your-project>.vercel.app/api/grant \
  -H "x-admin-secret: $ADMIN_SECRET" \
  -H "content-type: application/json" \
  -d '{"userId":"123456789","days":30}'
```

## Notes / next steps

- **Trust:** `/api/settings` currently trusts the `userId` in the body (MVP). Before real use, verify
  the caller via Telegram login (signed init data) or a per-user token issued after login.
- The client reads `/api/status` and caches results; the base URL lives in the app as
  `UMessagePremium` config.
