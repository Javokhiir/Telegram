package org.telegram.messenger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Friend Map pairing and location relay.
 *
 * Pairing runs over ordinary Telegram messages, so Telegram guarantees who sent a request:
 * A sends B "umf1 req &lt;key&gt;", B accepts with "umf1 ok &lt;ref&gt;"; afterwards both share a random
 * 256-bit key. Each side uploads its position AES-GCM-encrypted under an HMAC-derived id to the
 * U message relay, which only ever sees opaque blobs. Handshake messages are deleted once processed.
 */
public class UMessageFriendLocations implements NotificationCenter.NotificationCenterDelegate {

    public static final int STATE_OUTGOING = 0;
    public static final int STATE_INCOMING = 1;
    public static final int STATE_FRIEND = 2;

    public static class Friend {
        public long uid;
        public int state;
        byte[] key;
        String ref;
        final ArrayList<Integer> handshakeIds = new ArrayList<>();
        public Loc location;
        public Zone zone;
    }

    public static class Loc {
        public double lat, lng;
        public float accuracy;
        public long time;      // ms
        public float speedKmh; // -1 when unknown
        public String character; // their chosen map character, empty for the pin
    }

    public static final int ZONE_OUTGOING = 0;
    public static final int ZONE_INCOMING = 1;
    public static final int ZONE_ACTIVE = 2;

    /** An agreed area: crossing its edge alerts the other person. Proposed by one, accepted by the other. */
    public static class Zone {
        public double lat, lng;
        public int radius; // metres
        public int state;
        String zid;
        Boolean inside; // last known side of the edge for the friend
        final ArrayList<Integer> messageIds = new ArrayList<>();
    }

    public interface Listener {
        void onFriendsChanged();
    }

    private static final String TAG = "#umfriend";
    private static final Pattern COMMAND = Pattern.compile("umf1 (req|ok|no|del|zone|zok|zno|zdel) ([A-Za-z0-9_-]{8,512})");
    private static final String URL = UMessagePremiumController.BASE_URL + "/api/loc";
    // handshake payload rides in a hidden link on the leading emoji; the fragment never reaches a server
    private static final String LINK = UMessagePremiumController.BASE_URL + "/f#umf1.";
    private static final Pattern LINK_COMMAND = Pattern.compile("umf1\\.(req|ok|no|del|zone|zok|zno|zdel)\\.([A-Za-z0-9_-]{8,512})");
    private static final String BUTTON = "umf:";
    private static final ExecutorService network = Executors.newSingleThreadExecutor();

    private static final UMessageFriendLocations[] instances = new UMessageFriendLocations[UserConfig.MAX_ACCOUNT_COUNT];

    public static UMessageFriendLocations getInstance(int account) {
        UMessageFriendLocations local = instances[account];
        if (local == null) {
            synchronized (UMessageFriendLocations.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new UMessageFriendLocations(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final java.util.concurrent.ConcurrentHashMap<Long, Friend> friends = new java.util.concurrent.ConcurrentHashMap<>();
    private final HashSet<String> deadRefs = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private long lastScan;
    private long lastUpload;
    private boolean uploading;

    private UMessageFriendLocations(int account) {
        this.account = account;
        load();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages));
    }

    // ---------------------------------------------------------------- public API

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public ArrayList<Friend> getAll() {
        ArrayList<Friend> list = new ArrayList<>(friends.values());
        Collections.sort(list, (a, b) -> {
            if (a.state != b.state) return Integer.compare(order(a.state), order(b.state));
            long ta = a.location != null ? a.location.time : 0, tb = b.location != null ? b.location.time : 0;
            return Long.compare(tb, ta);
        });
        return list;
    }

    private static int order(int state) {
        return state == STATE_INCOMING ? 0 : state == STATE_FRIEND ? 1 : 2;
    }

    public Friend get(long uid) {
        return friends.get(uid);
    }

    public boolean hasFriends() {
        for (Friend f : friends.values()) {
            if (f.state == STATE_FRIEND) return true;
        }
        return false;
    }

    public void sendRequest(long uid) {
        Friend f = friends.get(uid);
        if (f != null && f.state != STATE_OUTGOING) {
            return;
        }
        if (f == null) {
            f = new Friend();
            f.uid = uid;
            f.key = new byte[32];
            new SecureRandom().nextBytes(f.key);
            f.ref = ref(f.key);
            f.state = STATE_OUTGOING;
            friends.put(uid, f);
        }
        send(uid, R.string.UMessageFriendReqText, "req", b64(f.key));
        changed();
    }

    public void accept(long uid) {
        Friend f = friends.get(uid);
        if (f == null || f.state != STATE_INCOMING) return;
        f.state = STATE_FRIEND;
        send(uid, R.string.UMessageFriendOkText, "ok", f.ref);
        deleteHandshake(f); // their request has done its job
        changed();
        uploadNow();
    }

    /** Declines a request, cancels our own request, or removes a friend. */
    public void remove(long uid) {
        Friend f = friends.remove(uid);
        if (f == null) return;
        deadRefs.add(f.ref);
        boolean decline = f.state == STATE_INCOMING;
        send(uid, decline ? R.string.UMessageFriendNoText : R.string.UMessageFriendDelText, decline ? "no" : "del", f.ref);
        if (f.state != STATE_FRIEND) {
            deleteHandshake(f);
        }
        if (f.state == STATE_FRIEND) {
            postItems(Collections.singletonList(new String[]{locId(f.key, myUid()), null}));
        }
        changed();
    }

    // ---------------------------------------------------------------- zones (geofence alerts)

    /** Proposes an alert zone to a friend; it is active for both once they accept. */
    public void proposeZone(long uid, double lat, double lng, int radiusM) {
        Friend f = friends.get(uid);
        if (f == null || f.state != STATE_FRIEND) return;
        if (f.zone != null) removeZone(uid);
        Zone z = new Zone();
        z.lat = lat;
        z.lng = lng;
        z.radius = radiusM;
        z.state = ZONE_OUTGOING;
        byte[] id = new byte[6];
        new SecureRandom().nextBytes(id);
        z.zid = hex(id);
        f.zone = z;
        String payload = encryptZone(f.key, z);
        if (payload == null) return;
        send(uid, LocaleController.formatString(R.string.UMessageZoneProposalText, formatRadius(radiusM)), "zone", payload);
        changed();
    }

    public void acceptZone(long uid) {
        Friend f = friends.get(uid);
        if (f == null || f.zone == null || f.zone.state != ZONE_INCOMING) return;
        f.zone.state = ZONE_ACTIVE;
        f.zone.inside = null;
        send(uid, LocaleController.getString(R.string.UMessageZoneOkText), "zok", f.zone.zid);
        changed();
    }

    /** Declines a proposal, cancels our own, or ends an active zone. */
    public void removeZone(long uid) {
        Friend f = friends.get(uid);
        if (f == null || f.zone == null) return;
        Zone z = f.zone;
        f.zone = null;
        boolean decline = z.state == ZONE_INCOMING;
        send(uid, LocaleController.getString(decline ? R.string.UMessageZoneNoText : R.string.UMessageZoneDelText), decline ? "zno" : "zdel", z.zid);
        deleteMessages(uid, z.messageIds, 0);
        changed();
    }

    public static String formatRadius(int metres) {
        return metres >= 1000 ? (metres % 1000 == 0 ? (metres / 1000) + " km" : String.format(java.util.Locale.US, "%.1f km", metres / 1000f)) : metres + " m";
    }

    /** Alerts when a friend crosses an active zone's edge (compared with the previous fix). */
    private void checkZone(Friend f) {
        Zone z = f.zone;
        if (z == null || z.state != ZONE_ACTIVE || f.location == null) return;
        float[] d = new float[1];
        Location.distanceBetween(z.lat, z.lng, f.location.lat, f.location.lng, d);
        // a small hysteresis band so GPS wobble on the edge does not ping-pong
        boolean inside = z.inside == null ? d[0] <= z.radius : (z.inside ? d[0] <= z.radius + 50 : d[0] < z.radius - 50);
        if (z.inside != null && z.inside != inside) {
            notifyZone(f, inside);
        }
        z.inside = inside;
    }

    private void notifyZone(Friend f, boolean entered) {
        try {
            TLRPC.User user = MessagesController.getInstance(account).getUser(f.uid);
            String name = user != null ? UserObject.getFirstName(user) : "";
            String text = LocaleController.formatString(entered ? R.string.UMessageZoneEntered : R.string.UMessageZoneLeft, name, formatRadius(f.zone.radius));
            Context context = ApplicationLoader.applicationContext;
            NotificationsController.checkOtherNotificationsChannel();
            android.content.Intent open = new android.content.Intent(context, org.telegram.ui.LaunchActivity.class);
            open.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.live_loc)
                    .setContentTitle(LocaleController.getString(R.string.UMessageFriendMap))
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);
            androidx.core.app.NotificationManagerCompat.from(context).notify((int) (0x7a000000 + (f.uid & 0xffffff)), b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** True when any friend has an active zone (the background service then polls positions). */
    public boolean hasActiveZones() {
        for (Friend f : friends.values()) {
            if (f.zone != null && f.zone.state == ZONE_ACTIVE) return true;
        }
        return false;
    }

    /** Looks for handshake messages (at most once a minute unless forced). */
    public void scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 60_000) return;
        lastScan = now;
        search("", new TLRPC.TL_inputMessagesFilterUrl());
        search(TAG, new TLRPC.TL_inputMessagesFilterEmpty()); // requests sent by older builds
    }

    private void search(String q, TLRPC.MessagesFilter filter) {
        TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.users_only = true;
        req.q = q;
        req.filter = filter;
        req.min_date = ConnectionsManager.getInstance(account).getCurrentTime() - 30 * 86400;
        req.offset_peer = new TLRPC.TL_inputPeerEmpty();
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.messages_Messages)) return;
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(res.users, false);
                ArrayList<TLRPC.Message> list = new ArrayList<>(res.messages);
                Collections.sort(list, (a, b) -> Integer.compare(a.date, b.date));
                boolean any = false;
                HashMap<Long, ArrayList<TLRPC.Message>> touched = new HashMap<>();
                for (TLRPC.Message m : list) {
                    if (process(m)) {
                        any = true;
                        touched.computeIfAbsent(m.peer_id.user_id, k -> new ArrayList<>()).add(m);
                    }
                }
                if (any) changed();
                for (HashMap.Entry<Long, ArrayList<TLRPC.Message>> e : touched.entrySet()) redraw(e.getKey(), e.getValue());
            });
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** Downloads and decrypts the latest positions of all friends. */
    public void refreshLocations() {
        ArrayList<Friend> list = new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        HashMap<String, Friend> byId = new HashMap<>();
        for (Friend f : friends.values()) {
            if (f.state != STATE_FRIEND) continue;
            String id = locId(f.key, f.uid);
            byId.put(id, f);
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
            list.add(f);
        }
        if (list.isEmpty()) return;
        String query = ids.toString();
        network.execute(() -> {
            try {
                JSONObject items = new JSONObject(http("GET", URL + "?ids=" + query, null)).getJSONObject("items");
                HashMap<Friend, Loc> result = new HashMap<>();
                for (HashMap.Entry<String, Friend> e : byId.entrySet()) {
                    String blob = items.optString(e.getKey(), null);
                    if (blob == null || "null".equals(blob)) continue;
                    Loc loc = decrypt(e.getValue().key, blob);
                    if (loc != null) result.put(e.getValue(), loc);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    boolean any = false;
                    for (HashMap.Entry<Friend, Loc> e : result.entrySet()) {
                        Loc old = e.getKey().location;
                        if (old == null || old.time != e.getValue().time) {
                            e.getKey().location = e.getValue();
                            checkZone(e.getKey());
                            any = true;
                        }
                    }
                    if (any) changed();
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Encrypts our position once per friend and uploads it (throttled). */
    public void upload(Location location, boolean force) {
        long now = System.currentTimeMillis();
        if (location == null || uploading || (!force && now - lastUpload < 4000)) return;
        long me = myUid();
        ArrayList<String[]> items = new ArrayList<>();
        for (Friend f : friends.values()) {
            if (f.state != STATE_FRIEND) continue;
            String blob = encrypt(f.key, location);
            if (blob != null) items.add(new String[]{locId(f.key, me), blob});
        }
        if (items.isEmpty()) return;
        lastUpload = now;
        postItems(items);
    }

    public void uploadNow() {
        upload(lastLocation, true);
    }

    // ---------------------------------------------------------------- foreground tracking

    private static boolean appForeground, mapOpen;
    private static int trackingMode; // 0 off, 1 slow, 2 fast
    private static Location lastLocation;
    private static final LocationListener tracker = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            dispatchLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    /** Uploads a fresh fix for every logged-in account (also used by the background service). */
    public static void dispatchLocation(Location location) {
        if (location == null) return;
        if (lastLocation != null && location.getAccuracy() > lastLocation.getAccuracy() * 3
                && location.getTime() - lastLocation.getTime() < 15_000) {
            return; // ignore a much worse fix right after a good one
        }
        lastLocation = location;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                getInstance(a).upload(location, false);
            }
        }
    }

    // ---------------------------------------------------------------- background mode

    private static boolean serviceRunning;

    private static SharedPreferences globalPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umfriends_global", Context.MODE_PRIVATE);
    }

    /** The 3D character this user shows on friends' maps ("" = classic pin). */
    public static String getMyCharacter() {
        return globalPrefs().getString("character", "");
    }

    public static void setMyCharacter(String id) {
        globalPrefs().edit().putString("character", id == null ? "" : id).apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null) instances[a].uploadNow();
        }
    }

    /** Once a day per account: lets the admin dashboard estimate Mapbox monthly active users. */
    public static void reportMapOpen(int account) {
        long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid <= 0) return;
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        String key = "mapopen_" + account;
        if (day.equals(globalPrefs().getString(key, ""))) return;
        globalPrefs().edit().putString(key, day).apply();
        network.execute(() -> {
            try {
                http("POST", UMessagePremiumController.BASE_URL + "/api/usage", new JSONObject().put("userId", String.valueOf(uid)).put("event", "map").toString());
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static boolean isBackgroundEnabled() {
        return globalPrefs().getBoolean("background", false);
    }

    public static void setBackgroundEnabled(boolean enabled) {
        globalPrefs().edit().putBoolean("background", enabled).apply();
        updateService();
    }

    /** Runs the background service only while it is wanted, allowed and useful. */
    private static void updateService() {
        Context context = ApplicationLoader.applicationContext;
        boolean anyFriends = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null && instances[a].hasFriends()) anyFriends = true;
        }
        boolean want = isBackgroundEnabled() && anyFriends && hasPermission(context);
        if (want && !serviceRunning && appForeground) {
            serviceRunning = true;
            UMessageFriendLocationService.start(context);
        } else if (!want) {
            serviceRunning = false;
            UMessageFriendLocationService.stop(context);
        }
    }

    public static Location getLastLocation() {
        return lastLocation;
    }

    public static void onAppResumed() {
        appForeground = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                UMessageFriendLocations c = getInstance(a);
                c.scan(false);
                if (c.hasFriends()) c.refreshLocations();
            }
        }
        updateTracking();
    }

    public static void onAppPaused() {
        appForeground = false;
        updateTracking();
    }

    public static void setMapOpen(boolean open) {
        mapOpen = open;
        updateTracking();
    }

    @SuppressLint("MissingPermission")
    private static void updateTracking() {
        updateService();
        boolean anyFriends = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (instances[a] != null && instances[a].hasFriends()) anyFriends = true;
        }
        int mode = !appForeground ? 0 : mapOpen ? 2 : anyFriends ? 1 : 0;
        if (mode == trackingMode) return;
        trackingMode = mode;
        Context context = ApplicationLoader.applicationContext;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.removeUpdates(tracker);
        } catch (Exception ignore) {
        }
        if (mode == 0 || !hasPermission(context)) return;
        long interval = mode == 2 ? 2000 : 30_000;
        float distance = mode == 2 ? 2 : 25;
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, interval, distance, tracker, Looper.getMainLooper());
                    Location last = lm.getLastKnownLocation(provider);
                    if (last != null && (lastLocation == null || last.getTime() > lastLocation.getTime())) {
                        tracker.onLocationChanged(last);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Re-evaluates tracking after a permission grant or a new friend. */
    public static void restartTracking() {
        trackingMode = -1;
        updateTracking();
    }

    // ---------------------------------------------------------------- handshake

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || acc != account) return;
        if (args.length > 2 && (boolean) args[2]) return; // scheduled
        long did = (long) args[0];
        if (did <= 0) return;
        ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[1];
        boolean any = false;
        ArrayList<TLRPC.Message> requests = new ArrayList<>();
        for (MessageObject obj : objects) {
            if (process(obj.messageOwner)) {
                any = true;
                requests.add(obj.messageOwner);
            }
        }
        if (any) changed();
        redraw(did, requests);
    }

    /**
     * The chat built this message before we knew it was a request, so it has no buttons yet; hand the
     * chat a fresh copy (decorated with the current state) so Accept / Decline show up right away.
     */
    private void redraw(long did, ArrayList<TLRPC.Message> messages) {
        if (messages.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<MessageObject> fresh = new ArrayList<>();
            for (TLRPC.Message m : messages) {
                decorate(account, m);
                fresh.add(new MessageObject(account, m, true, true));
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, did, fresh);
        });
    }

    private boolean process(TLRPC.Message m) {
        String[] command = parse(m);
        if (command == null) return false;
        long uid = m.peer_id.user_id;
        if (uid == myUid()) return false;
        String cmd = command[0], arg = command[1];
        Friend f = friends.get(uid);
        switch (cmd) {
            case "req": {
                byte[] key = unb64(arg);
                if (key == null || key.length != 32) return false;
                String ref = ref(key);
                if (deadRefs.contains(ref) || (f != null && ref.equals(f.ref))) {
                    if (f != null && !f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                    return false;
                }
                if (f != null && f.state == STATE_FRIEND) return false;
                if (f != null && f.state == STATE_OUTGOING && !m.out) {
                    // both asked at once: the smaller user id's key wins
                    if (uid > myUid()) return false;
                    f.key = key;
                    f.ref = ref;
                    f.state = STATE_INCOMING;
                    f.handshakeIds.add(m.id);
                    accept(uid);
                    return true;
                }
                if (f == null) {
                    f = new Friend();
                    f.uid = uid;
                    friends.put(uid, f);
                }
                f.key = key;
                f.ref = ref;
                f.state = m.out ? STATE_OUTGOING : STATE_INCOMING;
                f.handshakeIds.add(m.id);
                return true;
            }
            case "ok": {
                if (f == null || !arg.equals(f.ref)) return false;
                if (!f.handshakeIds.contains(m.id)) f.handshakeIds.add(m.id);
                if (f.state == STATE_FRIEND) return false;
                f.state = STATE_FRIEND;
                if (!m.out) {
                    // we asked and they agreed: clean the handshake out of the chat
                    deleteHandshake(f);
                }
                uploadNow();
                return true;
            }
            case "zone": {
                if (f == null || f.state != STATE_FRIEND) return false;
                Zone z = decryptZone(f.key, arg);
                if (z == null) return false;
                if (deadRefs.contains("z" + z.zid)) return false;
                if (f.zone != null && z.zid.equals(f.zone.zid)) {
                    if (!f.zone.messageIds.contains(m.id)) f.zone.messageIds.add(m.id);
                    return false;
                }
                z.state = m.out ? ZONE_OUTGOING : ZONE_INCOMING;
                z.messageIds.add(m.id);
                f.zone = z;
                return true;
            }
            case "zok": {
                if (f == null || f.zone == null || !arg.equals(f.zone.zid)) return false;
                if (!f.zone.messageIds.contains(m.id)) f.zone.messageIds.add(m.id);
                if (f.zone.state == ZONE_ACTIVE) return false;
                f.zone.state = ZONE_ACTIVE;
                f.zone.inside = null;
                if (!m.out) {
                    deleteMessages(uid, f.zone.messageIds, 0); // agreed: tidy the chat
                    f.zone.messageIds.clear();
                }
                return true;
            }
            case "zno":
            case "zdel": {
                deadRefs.add("z" + arg);
                if (!m.out) deleteMessages(uid, f != null && f.zone != null && arg.equals(f.zone.zid) ? f.zone.messageIds : null, m.id);
                if (f == null || f.zone == null || !arg.equals(f.zone.zid)) return false;
                f.zone = null;
                return true;
            }
            case "no":
            case "del": {
                if (!m.out) {
                    deleteMessages(uid, f != null && arg.equals(f.ref) ? f.handshakeIds : null, m.id);
                }
                deadRefs.add(arg);
                if (f == null || !arg.equals(f.ref)) return false;
                friends.remove(uid);
                return true;
            }
        }
        return false;
    }

    private void deleteHandshake(Friend f) {
        deleteMessages(f.uid, f.handshakeIds, 0);
        f.handshakeIds.clear();
    }

    private void deleteMessages(long uid, ArrayList<Integer> ids, int extra) {
        ArrayList<Integer> list = new ArrayList<>();
        if (ids != null) list.addAll(ids);
        if (extra != 0) list.add(extra);
        if (list.isEmpty()) return;
        MessagesController.getInstance(account).deleteMessages(list, null, null, uid, 0, true, 0);
    }

    private void send(long uid, int textRes, String cmd, String arg) {
        send(uid, LocaleController.getString(textRes), cmd, arg);
    }

    /** Sends a readable line whose leading emoji carries the command as a hidden link. */
    private void send(long uid, String text, String cmd, String arg) {
        int space = text.indexOf(' ');
        TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
        link.offset = 0;
        link.length = space > 0 ? space : text.length();
        link.url = LINK + cmd + "." + arg;
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        entities.add(link);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, uid);
        params.entities = entities;
        SendMessagesHelper.getInstance(account).sendMessage(params);
    }

    /** {command, argument} of a Friend Map handshake message, or null. */
    private static String[] parse(TLRPC.Message m) {
        if (m == null || !(m.peer_id instanceof TLRPC.TL_peerUser)) return null;
        if (m.entities != null) {
            for (int i = 0; i < m.entities.size(); i++) {
                TLRPC.MessageEntity e = m.entities.get(i);
                if (e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null && e.url.startsWith(LINK)) {
                    Matcher matcher = LINK_COMMAND.matcher(e.url);
                    if (matcher.find()) return new String[]{matcher.group(1), matcher.group(2)};
                }
            }
        }
        if (!TextUtils.isEmpty(m.message)) {
            Matcher matcher = COMMAND.matcher(m.message);
            if (matcher.find()) return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    // ---------------------------------------------------------------- in-chat buttons

    /** Gives a pending request message its Accept / Decline (or Cancel) buttons, drawn locally. */
    public static void decorate(int account, TLRPC.Message m) {
        if (m == null || (m.reply_markup != null && !isOurMarkup(m.reply_markup))) return;
        String[] command = parse(m);
        if (command != null && "zone".equals(command[0])) {
            decorateZone(account, m, command[1]);
            return;
        }
        if (command == null || !"req".equals(command[0])) return;
        byte[] key = unb64(command[1]);
        if (key == null) return;
        String ref = ref(key);
        Friend f = getInstance(account).friends.get(m.peer_id.user_id);
        TLRPC.TL_replyInlineMarkup markup = null;
        if (f != null && ref.equals(f.ref)) {
            if (!m.out && f.state == STATE_INCOMING) {
                markup = markup(button(R.string.UMessageFriendMapAccept, "a", ref, true), button(R.string.UMessageFriendMapDecline, "d", ref, false));
            } else if (m.out && f.state == STATE_OUTGOING) {
                markup = markup(button(R.string.UMessageFriendMapCancelRequest, "c", ref, false));
            }
        }
        m.reply_markup = markup;
    }

    private static void decorateZone(int account, TLRPC.Message m, String arg) {
        Friend f = getInstance(account).friends.get(m.peer_id.user_id);
        TLRPC.TL_replyInlineMarkup markup = null;
        if (f != null && f.zone != null) {
            Zone z = decryptZone(f.key, arg);
            if (z != null && z.zid.equals(f.zone.zid)) {
                if (!m.out && f.zone.state == ZONE_INCOMING) {
                    markup = markup(button(R.string.UMessageFriendMapAccept, "za", z.zid, true), button(R.string.UMessageFriendMapDecline, "zd", z.zid, false));
                } else if (m.out && f.zone.state == ZONE_OUTGOING) {
                    markup = markup(button(R.string.UMessageFriendMapCancelRequest, "zc", z.zid, false));
                }
            }
        }
        m.reply_markup = markup;
    }

    private static boolean isOurMarkup(TLRPC.ReplyMarkup markup) {
        if (!(markup instanceof TLRPC.TL_replyInlineMarkup)) return false;
        TLRPC.TL_replyInlineMarkup inline = (TLRPC.TL_replyInlineMarkup) markup;
        if (inline.rows.isEmpty() || inline.rows.get(0).buttons.isEmpty()) return false;
        org.telegram.tgnet.tl.TL_keyboard.InlineButtonType type = inline.rows.get(0).buttons.get(0).type;
        return type instanceof org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback
                && new String(((org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback) type).data, StandardCharsets.UTF_8).startsWith(BUTTON);
    }

    private static TLRPC.TL_replyInlineMarkup markup(org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton... buttons) {
        TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
        org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow row = new org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButtonRow();
        Collections.addAll(row.buttons, buttons);
        markup.rows.add(row);
        return markup;
    }

    private static org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton button(int textRes, String action, String ref, boolean positive) {
        org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButton b = new org.telegram.tgnet.tl.TL_keyboard.TL_keyboardInlineButton();
        b.text = LocaleController.getString(textRes);
        org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback type = new org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback();
        type.data = (BUTTON + action + ":" + ref).getBytes(StandardCharsets.UTF_8);
        b.type = type;
        b.style = new org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonStyle();
        if (positive) b.style.bg_success = true; else b.style.bg_danger = true;
        return b;
    }

    /** Handles a press on one of our request buttons; returns the action ("a", "d", "c") or null. */
    public static String handleButton(int account, MessageObject messageObject, org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonProto button) {
        if (messageObject == null || !(button instanceof org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton)) return null;
        org.telegram.tgnet.tl.TL_keyboard.InlineButtonType type = ((org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButton) button).type;
        if (!(type instanceof org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback)) return null;
        String data = new String(((org.telegram.tgnet.tl.TL_keyboard.TL_inlineButtonTypeCallback) type).data, StandardCharsets.UTF_8);
        if (!data.startsWith(BUTTON)) return null;
        String[] parts = data.split(":");
        if (parts.length < 3) return null;
        UMessageFriendLocations c = getInstance(account);
        long uid = messageObject.getDialogId();
        Friend f = c.friends.get(uid);
        if (parts[1].startsWith("z")) {
            if (f != null && f.zone != null && parts[2].equals(f.zone.zid)) {
                if ("za".equals(parts[1])) c.acceptZone(uid); else c.removeZone(uid);
            }
        } else if (f != null && parts[2].equals(f.ref)) {
            if ("a".equals(parts[1])) {
                c.accept(uid);
            } else {
                c.remove(uid);
            }
        }
        messageObject.messageOwner.reply_markup = null;
        return parts[1];
    }

    private void changed() {
        save();
        updateTracking();
        for (Listener l : new ArrayList<>(listeners)) {
            l.onFriendsChanged();
        }
    }

    private long myUid() {
        return UserConfig.getInstance(account).getClientUserId();
    }

    // ---------------------------------------------------------------- crypto

    private static String ref(byte[] key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
            return hex(h).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String locId(byte[] key, long uid) {
        try {
            return hex(hmac(key, "loc:" + uid)).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    private static String encrypt(byte[] key, Location l) {
        try {
            JSONObject o = new JSONObject();
            o.put("a", l.getLatitude());
            o.put("o", l.getLongitude());
            o.put("c", (double) l.getAccuracy());
            o.put("t", System.currentTimeMillis());
            o.put("s", l.hasSpeed() ? l.getSpeed() * 3.6 : -1);
            o.put("i", getMyCharacter());
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(key, "enc"), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return b64(out);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String encryptZone(byte[] key, Zone z) {
        try {
            JSONObject o = new JSONObject().put("a", z.lat).put("o", z.lng).put("r", z.radius).put("z", z.zid);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(key, "zone"), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return b64(out);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Zone decryptZone(byte[] key, String blob) {
        try {
            byte[] raw = unb64(blob);
            if (key == null || raw == null || raw.length < 29) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hmac(key, "zone"), "AES"), new GCMParameterSpec(128, raw, 0, 12));
            JSONObject o = new JSONObject(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
            Zone z = new Zone();
            z.lat = o.getDouble("a");
            z.lng = o.getDouble("o");
            z.radius = Math.max(100, Math.min(100_000, o.getInt("r")));
            z.zid = o.getString("z");
            return z;
        } catch (Exception e) {
            return null;
        }
    }

    private static Loc decrypt(byte[] key, String blob) {
        try {
            byte[] raw = unb64(blob);
            if (raw == null || raw.length < 29) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hmac(key, "enc"), "AES"), new GCMParameterSpec(128, raw, 0, 12));
            JSONObject o = new JSONObject(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
            Loc loc = new Loc();
            loc.lat = o.getDouble("a");
            loc.lng = o.getDouble("o");
            loc.accuracy = (float) o.optDouble("c", 0);
            loc.time = o.getLong("t");
            loc.speedKmh = (float) o.optDouble("s", -1);
            loc.character = o.optString("i", "");
            return loc;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] unb64(String s) {
        try {
            return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- network

    private void postItems(java.util.List<String[]> items) {
        uploading = true;
        network.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (String[] item : items) {
                    JSONObject o = new JSONObject();
                    o.put("id", item[0]);
                    o.put("data", item[1] == null ? JSONObject.NULL : item[1]);
                    arr.put(o);
                }
                http("POST", URL, new JSONObject().put("items", arr).toString());
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                AndroidUtilities.runOnUIThread(() -> uploading = false);
            }
        });
    }

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try (InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- storage

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umfriends_" + account, Context.MODE_PRIVATE);
    }

    private void load() {
        try {
            JSONArray arr = new JSONArray(prefs().getString("friends", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Friend f = new Friend();
                f.uid = o.getLong("u");
                f.state = o.getInt("s");
                f.key = unb64(o.getString("k"));
                f.ref = ref(f.key);
                JSONArray ids = o.optJSONArray("h");
                for (int j = 0; ids != null && j < ids.length(); j++) f.handshakeIds.add(ids.getInt(j));
                JSONObject zo = o.optJSONObject("z");
                if (zo != null) {
                    Zone z = new Zone();
                    z.lat = zo.getDouble("a");
                    z.lng = zo.getDouble("o");
                    z.radius = zo.getInt("r");
                    z.state = zo.getInt("s");
                    z.zid = zo.getString("z");
                    if (zo.has("in")) z.inside = zo.getBoolean("in");
                    JSONArray zm = zo.optJSONArray("m");
                    for (int j = 0; zm != null && j < zm.length(); j++) z.messageIds.add(zm.getInt(j));
                    f.zone = z;
                }
                friends.put(f.uid, f);
            }
            JSONArray dead = new JSONArray(prefs().getString("dead", "[]"));
            for (int i = 0; i < dead.length(); i++) deadRefs.add(dead.getString(i));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Friend f : friends.values()) {
                JSONArray ids = new JSONArray();
                for (int id : f.handshakeIds) ids.put(id);
                JSONObject fo = new JSONObject().put("u", f.uid).put("s", f.state).put("k", b64(f.key)).put("h", ids);
                if (f.zone != null) {
                    JSONArray zm = new JSONArray();
                    for (int id : f.zone.messageIds) zm.put(id);
                    JSONObject zo = new JSONObject().put("a", f.zone.lat).put("o", f.zone.lng).put("r", f.zone.radius)
                            .put("s", f.zone.state).put("z", f.zone.zid).put("m", zm);
                    if (f.zone.inside != null) zo.put("in", f.zone.inside.booleanValue());
                    fo.put("z", zo);
                }
                arr.put(fo);
            }
            prefs().edit().putString("friends", arr.toString()).putString("dead", new JSONArray(deadRefs).toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
