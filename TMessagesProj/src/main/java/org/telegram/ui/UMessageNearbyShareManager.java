package org.telegram.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageNearbyCard;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * Contact exchange between U message users whose phones touch.
 * Nearby Connections discovery carries each peer's contact in its endpoint info; a BLE beacon with the
 * user id gives the signal strength, and only when the phones are (almost) touching is the card shown.
 * Moving apart re-arms the peer, so every new touch shows the card again.
 */
public class UMessageNearbyShareManager {
    private static final String SERVICE_ID = "org.telegram.umessage.nearby.contact";
    private static final ParcelUuid BEACON_UUID = new ParcelUuid(UUID.fromString("7c1f3a52-94d1-4b8e-a6c2-5e0d9b1f4a37"));
    private static final int REQUEST_CODE = 7346;

    /** Smoothed RSSI at or above this means the phones are touching or about to. */
    private static final int RSSI_TOUCH = -55;
    /** Smoothed RSSI below this means they moved apart, so the next touch counts again. */
    private static final int RSSI_APART = -70;
    /** A peer not heard from for this long is treated as gone and re-armed. */
    private static final long LOST_MS = 4000;
    private static final String TAG = "UNearby";

    private static ConnectionsClient client;
    private static boolean started;
    private static boolean askedPermissions;
    private static View overlayView;
    private static UMessageNearbyCard overlayCard;

    private static BluetoothLeAdvertiser advertiser;
    private static BluetoothLeScanner scanner;
    private static boolean beaconRunning;
    private static int tokenExpires;

    /** Discovered peers' advertised info ("uid=..;username=..;name=..;bio=.."), by user id. */
    private static final HashMap<Long, String> endpoints = new HashMap<>();
    private static final HashMap<String, Long> endpointUids = new HashMap<>();
    private static final HashMap<Long, Peer> peers = new HashMap<>();

    private static class Peer {
        float rssi = Float.NaN;
        long lastSeen;
        boolean armed = true;
    }

    public static void onActivityResumed(LaunchActivity activity) {
        if (activity == null || !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        if (!UMessageConfig.isNearbyShareEnabled()) {
            stop();
            return;
        }
        if (!hasPermissions(activity)) {
            Log.i(TAG, "missing permissions");
            if (!askedPermissions) {
                askedPermissions = true;
                activity.requestPermissions(requiredPermissions(), REQUEST_CODE);
            }
            return;
        }
        if (started && tokenExpires != 0 && System.currentTimeMillis() / 1000 > tokenExpires - 60) {
            tokenExpires = 0;
            refreshContactToken();
        }
        start(activity);
        startBeacon(activity);
    }

    public static void onActivityPaused() {
        stopBeacon();
    }

    public static void setEnabled(LaunchActivity activity, boolean enabled) {
        UMessageConfig.setNearbyShareEnabled(enabled);
        if (enabled) {
            askedPermissions = false;
            onActivityResumed(activity);
        } else {
            stop();
        }
    }

    private static void stop() {
        stopBeacon();
        if (!started || client == null) {
            return;
        }
        started = false;
        tokenExpires = 0;
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        endpoints.clear();
        endpointUids.clear();
        peers.clear();
        hideOverlay();
    }

    private static void start(LaunchActivity activity) {
        if (started) {
            return;
        }
        started = true;
        client = Nearby.getConnectionsClient(activity);
        advertise(null);
        refreshContactToken();
        client.startDiscovery(SERVICE_ID, endpointCallback, new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener(e -> FileLog.e(e));
    }

    /** Re-advertises with a contact token so peers without a shared chat or username can still open ours. */
    private static void refreshContactToken() {
        MessagesController.getInstance(UserConfig.selectedAccount).requestContactToken(token -> {
            if (!started || client == null || token == null || TextUtils.isEmpty(token.url)) {
                return;
            }
            tokenExpires = token.expires;
            client.stopAdvertising();
            advertise(token.url.substring(token.url.lastIndexOf('/') + 1));
        });
    }

    private static void advertise(String token) {
        client.startAdvertising(makeLocalPayload(token), SERVICE_ID, lifecycleCallback, new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener(e -> FileLog.e(e));
    }

    /* Proximity beacon */

    @SuppressLint("MissingPermission")
    private static void startBeacon(Context context) {
        if (beaconRunning) {
            return;
        }
        long self = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (self == 0 || adapter == null || !adapter.isEnabled()) {
            Log.i(TAG, "beacon skipped, bluetooth off or no user");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        scanner = adapter.getBluetoothLeScanner();
        if (advertiser == null || scanner == null) {
            Log.i(TAG, "beacon unsupported adv=" + advertiser + " scan=" + scanner);
            return;
        }
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(BEACON_UUID, ByteBuffer.allocate(8).putLong(self).array())
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);

            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, scanSettings, scanCallback);
            beaconRunning = true;
            Log.i(TAG, "beacon started self=" + self);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @SuppressLint("MissingPermission")
    private static void stopBeacon() {
        if (!beaconRunning) {
            return;
        }
        beaconRunning = false;
        try {
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            Log.i(TAG, "advertise failed " + errorCode);
        }
    };

    private static final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() == null) {
                return;
            }
            byte[] data = result.getScanRecord().getServiceData(BEACON_UUID);
            if (data == null || data.length < 8) {
                return;
            }
            long uid = ByteBuffer.wrap(data).getLong();
            int rssi = result.getRssi();
            AndroidUtilities.runOnUIThread(() -> onBeacon(uid, rssi));
        }

        @Override
        public void onScanFailed(int errorCode) {
            beaconRunning = false;
            Log.i(TAG, "scan failed " + errorCode);
        }
    };

    private static void onBeacon(long uid, int rssi) {
        if (uid == 0 || uid == UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()) {
            return;
        }
        Peer peer = peers.get(uid);
        if (peer == null) {
            peers.put(uid, peer = new Peer());
        }
        long now = SystemClock.elapsedRealtime();
        if (Float.isNaN(peer.rssi) || now - peer.lastSeen > LOST_MS) {
            peer.rssi = rssi;
            peer.armed = true;
        } else {
            peer.rssi = peer.rssi * 0.6f + rssi * 0.4f;
        }
        peer.lastSeen = now;
        Log.i(TAG, "beacon uid=" + uid + " rssi=" + rssi + " avg=" + (int) peer.rssi + " armed=" + peer.armed + " endpoint=" + endpoints.get(uid));

        if (peer.rssi < RSSI_APART) {
            peer.armed = true;
        } else if (peer.armed && peer.rssi >= RSSI_TOUCH && overlayView == null) {
            String info = endpoints.get(uid);
            LaunchActivity activity = LaunchActivity.instance;
            if (info != null && activity != null) {
                peer.armed = false;
                Log.i(TAG, "touch -> show card uid=" + uid);
                String username = parseField(info, "username");
                String token = parseField(info, "t");
                showNearbyOverlay(activity, parseField(info, "name"), username, parseField(info, "bio"),
                        () -> openChat(username, token, uid), UMessageNearbyShareManager::hideOverlay);
            }
        }
    }

    /* Nearby Connections */

    private static boolean hasPermissions(LaunchActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static String[] requiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return permissions.toArray(new String[0]);
    }

    private static final EndpointDiscoveryCallback endpointCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            long uid = parseLong(parseField(info.getEndpointName(), "uid"));
            Log.i(TAG, "endpoint found " + endpointId + " uid=" + uid);
            if (uid != 0) {
                endpoints.put(uid, info.getEndpointName());
                endpointUids.put(endpointId, uid);
            }
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Long uid = endpointUids.remove(endpointId);
            if (uid != null) {
                endpoints.remove(uid);
            }
        }
    };

    /** Contacts travel in the advertised endpoint info, so incoming connections are never needed. */
    private static final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            if (client != null) {
                client.rejectConnection(endpointId);
            }
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
        }
    };

    /** Endpoint info is limited to 131 bytes, so the bio is cut to whatever room is left. */
    private static final int MAX_INFO_BYTES = 130;

    private static String makeLocalPayload(String token) {
        int account = UserConfig.selectedAccount;
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        String name = self == null || TextUtils.isEmpty(self.first_name) ? "U message" : self.first_name;
        String username = self == null || TextUtils.isEmpty(self.username) ? "" : self.username;
        TLRPC.UserFull userFull = self == null ? null : MessagesController.getInstance(account).getUserFull(self.id);
        String bio = userFull == null || TextUtils.isEmpty(userFull.about) ? "" : safe(userFull.about);
        long uid = self == null ? 0 : self.id;
        String head = "uid=" + uid + ";username=" + safe(username) + ";t=" + safe(token) + ";name=" + safe(cut(name, 24)) + ";bio=";
        int room = MAX_INFO_BYTES - head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        while (!bio.isEmpty() && bio.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > room) {
            bio = bio.substring(0, bio.length() - 1);
        }
        return head + bio;
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static void openChat(String username, String token, long uid) {
        hideOverlay();
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        if (!TextUtils.isEmpty(username)) {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last != null) {
                MessagesController.getInstance(account).openByUserName(username, last, 1);
                return;
            }
        }
        if (!TextUtils.isEmpty(token)) {
            TLRPC.TL_contacts_importContactToken req = new TLRPC.TL_contacts_importContactToken();
            req.token = token;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.User) {
                    MessagesController.getInstance(account).putUser((TLRPC.User) response, false);
                    activity.presentFragment(ChatActivity.of(((TLRPC.User) response).id));
                } else {
                    Log.i(TAG, "import contact token failed " + (error == null ? null : error.text));
                    openKnown(activity, account, uid);
                }
            }));
            return;
        }
        openKnown(activity, account, uid);
    }

    private static void openKnown(LaunchActivity activity, int account, long uid) {
        if (uid > 0 && MessagesController.getInstance(account).getUser(uid) != null) {
            activity.presentFragment(ChatActivity.of(uid));
        } else {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last != null) {
                BulletinFactory.of(last).createErrorBulletin(LocaleController.getString(R.string.NoUsernameFound)).show();
            }
        }
    }

    /* Card */

    private static void showNearbyOverlay(LaunchActivity activity, String peerName, String username, String bio, Runnable share, Runnable cancel) {
        hideOverlay();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0xcc000000);

        UMessageNearbyCard card = new UMessageNearbyCard(activity, peerName, username, bio, share, cancel);
        overlay.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 18, 0, 18, 28));

        overlay.setAlpha(0f);
        root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        card.setTranslationY(AndroidUtilities.dp(420));
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(750).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        card.play(true);
        AndroidUtilities.vibrateCursor(overlay);
        overlayView = overlay;
        overlayCard = card;
    }

    private static void hideOverlay() {
        if (overlayView == null) {
            return;
        }
        View view = overlayView;
        UMessageNearbyCard card = overlayCard;
        overlayView = null;
        overlayCard = null;
        if (card != null) {
            card.animate().translationY(AndroidUtilities.dp(420)).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_IN).start();
        }
        view.animate().alpha(0f).setDuration(420).withEndAction(() -> {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }).start();
    }

    private static String parseField(String data, String key) {
        if (data == null) {
            return "";
        }
        String prefix = key + "=";
        String[] parts = data.split(";");
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return "";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(";", " ").replace("=", " ");
    }
}
