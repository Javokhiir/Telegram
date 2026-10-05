package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Brand ads: bottom banner on feature pages and a full-screen card between stories.
 * Ads are managed from the admin bot on the U message server ({@code /api/ads}); the app caches the
 * list and images so they also show offline. The built-in ad is only a fallback before the first sync.
 */
public final class UMessageAds {

    public static final String PLACEMENT_BANNER = "banner";
    public static final String PLACEMENT_STORY = "story";

    public static final class Ad {
        public final String id;         // server id, null for built-in
        public final String brand;      // shown when there is no logo
        public final String headline;
        public final String url;
        public final int colorStart;    // card gradient
        public final int colorEnd;
        public final int ctaColor;      // round arrow / button
        public final int logoRes;       // built-in white wordmark, 0 = none
        public final int artRes;        // built-in picture, 0 = none
        public Bitmap logo;             // downloaded logo, wins over logoRes
        public Bitmap art;              // downloaded picture, wins over artRes
        public List<String> placements = new ArrayList<>();

        public Ad(String id, String brand, String headline, String url, int colorStart, int colorEnd, int ctaColor, int logoRes, int artRes) {
            this.id = id;
            this.brand = brand;
            this.headline = headline;
            this.url = url;
            this.colorStart = colorStart;
            this.colorEnd = colorEnd;
            this.ctaColor = ctaColor;
            this.logoRes = logoRes;
            this.artRes = artRes;
            placements.add(PLACEMENT_BANNER);
            placements.add(PLACEMENT_STORY);
        }
    }

    private static final String SERVER = UMessagePremiumController.BASE_URL;
    private static final long REFRESH_MS = 3 * 60 * 60 * 1000L;

    private static final List<Ad> BUILT_IN = Collections.unmodifiableList(new ArrayList<Ad>() {{
        add(new Ad(null, "7TECH", "Texnologiya yangiliklari\nbir joyda", "https://t.me/seventechchat",
                0xff0b1020, 0xff1d2f8f, 0xff2f6bff, R.drawable.um_ad_seventech_logo, R.drawable.um_ad_seventech_art));
    }});

    // Timing set in the admin panel (/admin), defaults until the first sync.
    private static volatile int storyEvery = 3;
    private static volatile int storyDurationSec = 6;
    private static volatile boolean bannerEnabled = true;
    private static volatile boolean storyEnabled = true;

    public static int storyEvery() {
        return Math.max(1, storyEvery);
    }

    public static long storyDurationMs() {
        return Math.max(3, storyDurationSec) * 1000L;
    }

    private static volatile List<Ad> remoteAds;   // null = never synced, empty = server has no ads
    private static long lastRefresh;
    private static boolean refreshing;

    private UMessageAds() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_ads", Context.MODE_PRIVATE);
    }

    private static File imageDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "umads");
        dir.mkdirs();
        return dir;
    }

    /** The ad for a page in a placement; each page gets its own ad from the list, or null when there are none. */
    public static Ad forPage(String pageKey, String placement) {
        refresh();
        if (PLACEMENT_BANNER.equals(placement) && !bannerEnabled || PLACEMENT_STORY.equals(placement) && !storyEnabled) {
            return null;
        }
        List<Ad> source = remoteAds != null ? remoteAds : BUILT_IN;
        ArrayList<Ad> ads = new ArrayList<>();
        for (Ad ad : source) {
            if (ad.placements.contains(placement)) {
                ads.add(ad);
            }
        }
        if (ads.isEmpty()) {
            return null;
        }
        int index = Math.abs((pageKey == null ? 0 : pageKey.hashCode()) % ads.size());
        return ads.get(index);
    }

    /** Loads the cached list right away, then syncs with the server (at most every few hours). */
    public static void refresh() {
        if (remoteAds == null) {
            String cached = prefs().getString("json", null);
            if (cached != null) {
                try {
                    remoteAds = parse(cached, false);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        if (refreshing || SystemClock.elapsedRealtime() - lastRefresh < REFRESH_MS && lastRefresh != 0) {
            return;
        }
        refreshing = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String json = new String(get(SERVER + "/api/ads"), "UTF-8");
                List<Ad> ads = parse(json, true);
                prefs().edit().putString("json", json).apply();
                remoteAds = ads;
                lastRefresh = SystemClock.elapsedRealtime();
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                refreshing = false;
            }
        });
    }

    private static List<Ad> parse(String json, boolean download) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject config = root.optJSONObject("config");
        if (config != null) {
            storyEvery = config.optInt("storyEvery", storyEvery);
            storyDurationSec = config.optInt("storyDurationSec", storyDurationSec);
            bannerEnabled = config.optBoolean("bannerEnabled", bannerEnabled);
            storyEnabled = config.optBoolean("storyEnabled", storyEnabled);
        }
        JSONArray array = root.getJSONArray("ads");
        ArrayList<Ad> ads = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            Ad ad = new Ad(o.getString("id"), o.optString("brand"), o.optString("headline"), o.getString("url"),
                    color(o, "colorStart", 0xff1c1c22), color(o, "colorEnd", 0xff2c2c34), color(o, "ctaColor", 0xff2f6bff), 0, 0);
            JSONArray placements = o.optJSONArray("placements");
            if (placements != null) {
                ad.placements.clear();
                for (int j = 0; j < placements.length(); j++) {
                    ad.placements.add(placements.getString(j));
                }
            }
            ad.logo = image(o.optString("logoUrl", null), ad.id + "_logo", download);
            ad.art = image(o.optString("artUrl", null), ad.id + "_art", download);
            ads.add(ad);
        }
        return Collections.unmodifiableList(ads);
    }

    private static int color(JSONObject o, String key, int fallback) {
        try {
            return Color.parseColor(o.getString(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Image from the disk cache; downloaded first when allowed and the url (version) changed. */
    private static Bitmap image(String url, String name, boolean download) {
        if (url == null || url.isEmpty() || "null".equals(url)) {
            return null;
        }
        File file = new File(imageDir(), name + "_" + Integer.toHexString(url.hashCode()) + ".img");
        try {
            if (!file.exists() && download) {
                byte[] bytes = get(url);
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
            }
            return file.exists() ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Counts an impression or a click for the brand's report. */
    public static void track(Ad ad, String type) {
        if (ad == null || ad.id == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SERVER + "/api/ad-event").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(new JSONObject().put("id", ad.id).put("type", type).toString().getBytes("UTF-8"));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
