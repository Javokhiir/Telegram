package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U message app-only "premium" — completely separate from real Telegram Premium. A small Vercel
 * backend stores which Telegram user ids the app should treat as premium (badge + premium features)
 * for U message users only. This controller reads that state, caches it, and lets the current user
 * turn their own premium display on or off.
 *
 * Nothing here talks to Telegram's servers; the flag only affects how the U message client behaves.
 */
public final class UMessagePremiumController {

    public static final String BASE_URL = "https://umessage-premium-server.vercel.app";

    private static final long TTL_MS = 5 * 60 * 1000;
    private static final long NEGATIVE_TTL_MS = 60 * 1000;

    private static final String PREFS = "umessage_premium";
    private static final String KEY_ENABLED = "self_enabled";

    private static volatile UMessagePremiumController instance;

    public static UMessagePremiumController getInstance() {
        UMessagePremiumController local = instance;
        if (local == null) {
            synchronized (UMessagePremiumController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new UMessagePremiumController();
                }
            }
        }
        return local;
    }

    private static final class Entry {
        boolean premium;
        boolean enabled;
        String stickerSet;
        long fetchedAt;
    }

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private final HashSet<Long> pending = new HashSet<>();
    private final HashSet<Long> inFlight = new HashSet<>();
    private final Object lock = new Object();
    private boolean flushScheduled;
    private volatile Boolean selfEnabled;
    private volatile long lastRegister;

    private UMessagePremiumController() {
    }

    /** True when this user should be shown as premium in the U message UI (granted and not hidden). */
    public boolean isPremium(long userId) {
        if (userId <= 0) {
            return false;
        }
        final Entry e = cache.get(userId);
        if (e == null || isStale(e)) {
            requestFetch(userId);
        }
        return e != null && e.premium && e.enabled;
    }

    /** Every U message user is premium: self is premium whenever the user hasn't hidden it. */
    public boolean isSelfPremium(int account) {
        return isSelfEnabled();
    }

    /**
     * Announce this user to the backend so other U message users see them as premium. Safe to call
     * repeatedly (throttled); every registered user is granted premium automatically.
     */
    public void register(int account) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId <= 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastRegister < 6 * 60 * 60 * 1000) {
            return;
        }
        lastRegister = now;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("userId", String.valueOf(selfId));
                body.put("enabled", isSelfEnabled());
                httpPost(BASE_URL + "/api/register", body.toString());
            } catch (Throwable ignore) {
            }
        });
    }

    /** True when the current account's user was granted premium (regardless of their display toggle). */
    public boolean isSelfGranted(int account) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final Entry e = cache.get(selfId);
        if (e == null || isStale(e)) {
            requestFetch(selfId);
        }
        return e != null && e.premium;
    }

    public String getStickerSet(long userId) {
        final Entry e = cache.get(userId);
        return e != null && e.premium ? e.stickerSet : null;
    }

    /** The current user's local preference to display their premium (synced to the backend too). */
    public boolean isSelfEnabled() {
        Boolean v = selfEnabled;
        if (v == null) {
            v = prefs().getBoolean(KEY_ENABLED, true);
            selfEnabled = v;
        }
        return v;
    }

    public void setSelfEnabled(int account, boolean enabled) {
        selfEnabled = enabled;
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final Entry e = cache.get(selfId);
        if (e != null) {
            e.enabled = enabled;
        }
        lastRegister = 0; // force the backend upsert to carry the new flag
        register(account);
        AndroidUtilities.runOnUIThread(UMessagePremiumController::notifyChanged);
    }

    private boolean isStale(Entry e) {
        final long ttl = e.premium ? TTL_MS : NEGATIVE_TTL_MS;
        return System.currentTimeMillis() - e.fetchedAt > ttl;
    }

    /** Coalesces id lookups: requests within a short window go out as one batched status call. */
    private void requestFetch(long userId) {
        synchronized (lock) {
            if (inFlight.contains(userId) || !pending.add(userId)) {
                return;
            }
            if (!flushScheduled) {
                flushScheduled = true;
                AndroidUtilities.runOnUIThread(this::flush, 150);
            }
        }
    }

    private void flush() {
        final ArrayList<Long> ids = new ArrayList<>();
        synchronized (lock) {
            flushScheduled = false;
            for (Long id : pending) {
                if (!inFlight.contains(id)) {
                    ids.add(id);
                    inFlight.add(id);
                    if (ids.size() >= 100) {
                        break;
                    }
                }
            }
            pending.removeAll(ids);
        }
        if (ids.isEmpty()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean changed = false;
            try {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ids.get(i));
                }
                final JSONObject users = httpGet(BASE_URL + "/api/status?ids=" + sb).getJSONObject("users");
                final long now = System.currentTimeMillis();
                for (Long id : ids) {
                    final JSONObject u = users.optJSONObject(String.valueOf(id));
                    final Entry e = new Entry();
                    if (u != null) {
                        e.premium = u.optBoolean("premium", false);
                        e.enabled = u.optBoolean("enabled", false);
                        e.stickerSet = u.isNull("stickerSet") ? null : u.optString("stickerSet", null);
                    }
                    e.fetchedAt = now;
                    final Entry old = cache.put(id, e);
                    changed |= old == null || old.premium != e.premium || old.enabled != e.enabled;
                }
            } catch (Throwable ignore) {
                // keep whatever we had; stale entries retry later
            } finally {
                synchronized (lock) {
                    inFlight.removeAll(ids);
                }
            }
            if (changed) {
                AndroidUtilities.runOnUIThread(UMessagePremiumController::notifyChanged);
            }
        });
    }

    private static void notifyChanged() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, 0);
        }
    }

    private static JSONObject httpGet(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        try {
            return new JSONObject(readAll(c));
        } finally {
            c.disconnect();
        }
    }

    private static void httpPost(String url, String json) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
        }
        try {
            readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        final java.io.InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) {
            return "{}";
        }
        final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bo.write(buf, 0, n);
        }
        return bo.toString("UTF-8");
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }
}
