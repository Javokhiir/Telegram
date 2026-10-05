package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mapbox.bindgen.Value;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.CameraState;
import com.mapbox.maps.EdgeInsets;
import com.mapbox.maps.MapView;
import com.mapbox.maps.MapboxMap;
import com.mapbox.maps.ScreenCoordinate;
import com.mapbox.maps.Style;
import com.mapbox.maps.plugin.animation.CameraAnimationsUtils;
import com.mapbox.maps.plugin.animation.MapAnimationOptions;
import com.mapbox.maps.plugin.attribution.AttributionUtils;
import com.mapbox.maps.plugin.compass.CompassUtils;
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin;
import com.mapbox.maps.plugin.locationcomponent.LocationComponentUtils;
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener;
import com.mapbox.maps.plugin.logo.LogoUtils;
import com.mapbox.maps.plugin.scalebar.ScaleBarUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UMessageFriendLocations;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageMapCharacters;
import org.telegram.ui.Components.UMessageFriendMapOverlay;

import java.util.ArrayList;

/**
 * Friend Map: a globe with every accepted friend at their current position. Friendship is a
 * request/accept handshake ({@link UMessageFriendLocations}); positions are relayed end-to-end
 * encrypted and refreshed every few seconds while this screen is open.
 */
public class UMessageFriendMapActivity extends BaseFragment implements UMessageFriendLocations.Listener {

    private static final int PERMISSION_CODE = 2;
    private static final String HI = "👋";

    private UMessageFriendLocations controller;
    private final LongSparseArray<ImageReceiver> avatars = new LongSparseArray<>();

    private MapView mapView;
    private MapboxMap mapboxMap;
    private UMessageFriendMapOverlay overlay;
    private boolean styleLoaded;
    private boolean introDone;
    private Location myLocation;
    private final OnIndicatorPositionChangedListener positionListener = this::onMyPosition;

    private boolean dark;
    private int cBg, cText, cSecondary, cSeparator, cButton, cAccent;

    private long focusUserId;
    private long selectedUid;
    private FrameLayout root;
    private SheetLayout sheet;
    private LinearLayout listLayout;
    private TextView countView;
    private ImageView characterButton;
    private FrameLayout selectedCard;
    private boolean permissionAsked;
    private Runnable pendingAction;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            controller.refreshLocations();
            AndroidUtilities.runOnUIThread(this, 4000);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            rebuild();
            AndroidUtilities.runOnUIThread(this, 20_000);
        }
    };

    public static UMessageFriendMapActivity forUser(long userId) {
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        return new UMessageFriendMapActivity(args);
    }

    public UMessageFriendMapActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        focusUserId = arguments != null ? arguments.getLong("user_id", 0) : 0;
        controller = UMessageFriendLocations.getInstance(currentAccount);
        controller.addListener(this);
        controller.scan(true);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(zoneFollow);
        controller.removeListener(this);
        AndroidUtilities.cancelRunOnUIThread(poller);
        AndroidUtilities.cancelRunOnUIThread(ticker);
        for (int i = 0; i < avatars.size(); i++) {
            avatars.valueAt(i).onDetachedFromWindow();
        }
        if (mapView != null) {
            try {
                LocationComponentUtils.getLocationComponent(mapView).removeOnIndicatorPositionChangedListener(positionListener);
                mapView.onDestroy();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            mapView = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        UMessageFriendLocations.setMapOpen(true);
        UMessageFriendLocations.reportMapOpen(currentAccount);
        AndroidUtilities.cancelRunOnUIThread(poller);
        poller.run();
        enableMyLocation();
    }

    @Override
    public void onPause() {
        super.onPause();
        UMessageFriendLocations.setMapOpen(false);
        AndroidUtilities.cancelRunOnUIThread(poller);
    }

    @Override
    public boolean isLightStatusBar() {
        return !dark;
    }

    @Override
    public void onFriendsChanged() {
        rebuild();
    }

    @Override
    public View createView(Context context) {
        dark = AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) < 0.721f;
        cBg = getThemedColor(Theme.key_windowBackgroundWhite);
        cText = getThemedColor(Theme.key_chats_name);
        cSecondary = getThemedColor(Theme.key_chats_message);
        cSeparator = dark ? 0x40545458 : 0x2e3c3c43;
        cButton = dark ? 0xf02c2c2e : 0xf7ffffff;
        cAccent = dark ? 0xff0a84ff : 0xff007aff;

        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(0xff000000);
        fragmentView = root;

        createMap(context);

        int top = AndroidUtilities.statusBarHeight + dp(8);
        ImageView back = roundButton(context, R.drawable.ic_ab_back);
        back.setOnClickListener(v -> finishFragment());
        root.addView(back, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.LEFT, 16, 0, 0, 0));
        ((FrameLayout.LayoutParams) back.getLayoutParams()).topMargin = top;

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackground(rounded(cButton, dp(14)));
        controls.setElevation(dp(3));
        controls.addView(controlButton(context, R.drawable.msg_current_location, v -> focusMe()), LayoutHelper.createLinear(46, 46));
        View sep = new View(context);
        sep.setBackgroundColor(cSeparator);
        controls.addView(sep, LayoutHelper.createLinear(30, 1f / AndroidUtilities.density, Gravity.CENTER_HORIZONTAL));
        controls.addView(controlButton(context, R.drawable.msg_groups, v -> showGlobe(true)), LayoutHelper.createLinear(46, 46));
        root.addView(controls, LayoutHelper.createFrame(46, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 0, 16, 0));
        ((FrameLayout.LayoutParams) controls.getLayoutParams()).topMargin = top;

        selectedCard = new FrameLayout(context);
        selectedCard.setVisibility(View.GONE);
        root.addView(selectedCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 0, 12, 0));

        sheet = new SheetLayout(context);
        root.addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.BOTTOM));
        ((FrameLayout.LayoutParams) sheet.getLayoutParams()).topMargin = AndroidUtilities.statusBarHeight + dp(64);

        rebuild();
        AndroidUtilities.runOnUIThread(ticker, 20_000);
        return fragmentView;
    }

    // ---------------------------------------------------------------- map

    private void createMap(Context context) {
        mapView = new MapView(context);
        root.addView(mapView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        mapboxMap = mapView.getMapboxMap();

        overlay = new UMessageFriendMapOverlay(context);
        overlay.setAccount(currentAccount);
        overlay.setSpeedFormat(LocaleController.getString(R.string.UMessageFriendMapSpeed));
        overlay.setOnPinClick(this::select);
        root.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        try {
            ScaleBarUtils.getScaleBar(mapView).setEnabled(false);
            CompassUtils.getCompass(mapView).setMarginTop(AndroidUtilities.statusBarHeight + dp(118));
        } catch (Throwable e) {
            FileLog.e(e);
        }

        myLocation = UMessageFriendLocations.getLastLocation();
        Point center = globeCenter();
        mapboxMap.setCamera(new CameraOptions.Builder().center(center).zoom(0.6).pitch(0.0).build());
        mapboxMap.loadStyle(Style.STANDARD, style -> {
            try {
                style.setStyleImportConfigProperty("basemap", "lightPreset", Value.valueOf(dark ? "night" : "day"));
            } catch (Throwable e) {
                FileLog.e(e);
            }
            styleLoaded = true;
            overlay.setProjector(this::toScreen);
            enableMyLocation();
            intro();
        });
    }

    /** Opening shot: the globe turns to friends, then dives to the profile's user if they are a friend. */
    private void intro() {
        if (introDone || !styleLoaded) return;
        introDone = true;
        CameraOptions globe = new CameraOptions.Builder().center(globeCenter()).zoom(1.7).pitch(0.0).bearing(0.0).build();
        CameraAnimationsUtils.easeTo(mapboxMap, globe, new MapAnimationOptions.Builder().duration(1400).build(), null);
        UMessageFriendLocations.Friend focused = controller.get(focusUserId);
        if (focused != null && focused.location != null) {
            AndroidUtilities.runOnUIThread(() -> select(focusUserId), 1600);
        }
    }

    private Point globeCenter() {
        double lat = 0, lng = 0;
        int n = 0;
        for (UMessageFriendLocations.Friend f : controller.getAll()) {
            if (f.location != null) {
                lat += f.location.lat;
                lng += f.location.lng;
                n++;
            }
        }
        if (myLocation != null) {
            lat += myLocation.getLatitude();
            lng += myLocation.getLongitude();
            n++;
        }
        return n == 0 ? Point.fromLngLat(64.5, 41.3) : Point.fromLngLat(lng / n, lat / n);
    }

    private PointF toScreen(double lat, double lng) {
        if (mapboxMap == null || !styleLoaded) return null;
        CameraState camera = mapboxMap.getCameraState();
        if (camera.getZoom() < 5) {
            // hide pins on the far side of the globe
            Point c = camera.getCenter();
            float[] d = new float[1];
            Location.distanceBetween(c.latitude(), c.longitude(), lat, lng, d);
            if (d[0] > 9_000_000) return null;
        }
        ScreenCoordinate s = mapboxMap.pixelForCoordinate(Point.fromLngLat(lng, lat));
        if (s.getX() < -dp(60) || s.getY() < -dp(60)) return null;
        return new PointF((float) s.getX(), (float) s.getY());
    }

    private EdgeInsets insets() {
        int bottom = sheet != null ? sheet.visibleHeight() : dp(260);
        int selected = selectedCard != null && selectedCard.getVisibility() == View.VISIBLE ? selectedCard.getHeight() + dp(12) : 0;
        return new EdgeInsets(AndroidUtilities.statusBarHeight + dp(80), dp(40), bottom + selected + dp(30), dp(40));
    }

    private void onSheetMoved() {
        int visible = sheet.visibleHeight();
        if (selectedCard.getVisibility() == View.VISIBLE) {
            selectedCard.setTranslationY(root.getHeight() - visible - selectedCard.getHeight() - dp(12));
        }
        if (mapView != null) {
            try {
                LogoUtils.getLogo(mapView).setMarginBottom(visible + dp(6));
                AttributionUtils.getAttribution(mapView).setMarginBottom(visible + dp(6));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private void onMyPosition(Point point) {
        boolean first = myLocation == null;
        Location location = new Location("mapbox");
        location.setLatitude(point.latitude());
        location.setLongitude(point.longitude());
        myLocation = location;
        updateMe();
        if (first) rebuild();
    }

    /** With a character chosen we draw ourselves like a friend (the Mapbox puck is hidden). */
    private void updateMe() {
        if (overlay == null) return;
        long me = getUserConfig().getClientUserId();
        String character = UMessageFriendLocations.getMyCharacter();
        if (TextUtils.isEmpty(character) || myLocation == null) {
            overlay.remove(me);
            return;
        }
        Location fix = UMessageFriendLocations.getLastLocation();
        float speed = fix != null && fix.hasSpeed() ? fix.getSpeed() * 3.6f : -1;
        overlay.update(me, getUserConfig().getCurrentUser(), avatar(me), myLocation.getLatitude(), myLocation.getLongitude(),
                (int) (System.currentTimeMillis() / 1000), speed, fix != null ? fix.getAccuracy() : 0, character);
    }

    private void applyPuck() {
        if (mapView == null || !styleLoaded) return;
        try {
            LocationComponentPlugin lc = LocationComponentUtils.getLocationComponent(mapView);
            boolean custom = !TextUtils.isEmpty(UMessageFriendLocations.getMyCharacter());
            lc.setLocationPuck(custom ? new com.mapbox.maps.plugin.LocationPuck2D() : LocationComponentUtils.createDefault2DPuck(true));
            lc.setPulsingEnabled(!custom);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        updateMe();
    }

    private void requestPermission() {
        if (!permissionAsked && Build.VERSION.SDK_INT >= 23 && getParentActivity() != null) {
            permissionAsked = true;
            getParentActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_CODE);
        }
    }

    private void enableMyLocation() {
        if (mapView == null || !styleLoaded) return;
        if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            requestPermission();
            return;
        }
        try {
            LocationComponentPlugin lc = LocationComponentUtils.getLocationComponent(mapView);
            lc.setPulsingColor(0xff0a84ff);
            applyPuck();
            lc.removeOnIndicatorPositionChangedListener(positionListener);
            lc.addOnIndicatorPositionChangedListener(positionListener);
            lc.setEnabled(true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_CODE && UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            UMessageFriendLocations.restartTracking();
            enableMyLocation();
            if (pendingAction != null) {
                Runnable r = pendingAction;
                pendingAction = null;
                r.run();
            }
        }
    }

    private void flyTo(double lat, double lng, double zoom) {
        if (mapboxMap == null) return;
        CameraOptions camera = new CameraOptions.Builder()
                .center(Point.fromLngLat(lng, lat))
                .zoom(zoom)
                .pitch(50.0)
                .padding(insets())
                .build();
        CameraAnimationsUtils.flyTo(mapboxMap, camera, new MapAnimationOptions.Builder().duration(2200).build(), null);
    }

    private void showGlobe(boolean animated) {
        if (mapboxMap == null) return;
        select(0);
        CameraOptions camera = new CameraOptions.Builder().center(globeCenter()).zoom(1.7).pitch(0.0).bearing(0.0)
                .padding(new EdgeInsets(0, 0, 0, 0)).build();
        if (animated) {
            CameraAnimationsUtils.flyTo(mapboxMap, camera, new MapAnimationOptions.Builder().duration(1800).build(), null);
        } else {
            mapboxMap.setCamera(camera);
        }
    }

    private void focusMe() {
        if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            permissionAsked = false;
            requestPermission();
            return;
        }
        Location l = myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation();
        if (l != null) {
            select(0);
            flyTo(l.getLatitude(), l.getLongitude(), 15.5);
        }
    }

    private ImageReceiver avatar(long uid) {
        ImageReceiver r = avatars.get(uid);
        TLRPC.User user = getMessagesController().getUser(uid);
        if (r == null && user != null) {
            r = new ImageReceiver();
            r.setCurrentAccount(currentAccount);
            r.onAttachedToWindow();
            AvatarDrawable d = new AvatarDrawable();
            d.setInfo(currentAccount, user);
            r.setForUserOrChat(user, d);
            avatars.put(uid, r);
        }
        return r;
    }

    private void updateMarkers() {
        if (overlay == null) return;
        for (UMessageFriendLocations.Friend f : controller.getAll()) {
            TLRPC.User user = getMessagesController().getUser(f.uid);
            if (f.state != UMessageFriendLocations.STATE_FRIEND || f.location == null || user == null) {
                overlay.remove(f.uid);
                continue;
            }
            overlay.update(f.uid, user, avatar(f.uid), f.location.lat, f.location.lng, (int) (f.location.time / 1000), f.location.speedKmh, f.location.accuracy, f.location.character);
        }
        ArrayList<double[]> zones = new ArrayList<>();
        for (UMessageFriendLocations.Friend f : controller.getAll()) {
            if (f.zone != null) zones.add(new double[]{f.zone.lat, f.zone.lng, f.zone.radius, f.zone.state == UMessageFriendLocations.ZONE_ACTIVE ? 1 : 0});
        }
        overlay.setZones(zones);
    }

    // ---------------------------------------------------------------- zone editor

    private static final int[] RADII = {200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 20000};
    private FrameLayout zoneEditor;
    private long zoneUid;
    private int zoneRadius = 1000;
    private final Runnable zoneFollow = new Runnable() {
        @Override
        public void run() {
            if (zoneEditor == null || mapboxMap == null) return;
            Point c = mapboxMap.getCameraState().getCenter();
            overlay.setEditZone(new double[]{c.latitude(), c.longitude(), zoneRadius});
            AndroidUtilities.runOnUIThread(this, 32);
        }
    };

    private void onZoneButton(UMessageFriendLocations.Friend f) {
        if (getParentActivity() == null) return;
        TLRPC.User user = getMessagesController().getUser(f.uid);
        String name = UserObject.getFirstName(user);
        UMessageFriendLocations.Zone z = f.zone;
        if (z == null) {
            startZoneEditor(f);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.UMessageZoneEditorTitle));
        String radius = UMessageFriendLocations.formatRadius(z.radius);
        if (z.state == UMessageFriendLocations.ZONE_INCOMING) {
            b.setMessage(LocaleController.formatString(R.string.UMessageZonePendingIn, name, radius));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageFriendMapAccept), (d, w) -> controller.acceptZone(f.uid));
            b.setNegativeButton(LocaleController.getString(R.string.UMessageFriendMapDecline), (d, w) -> controller.removeZone(f.uid));
        } else {
            b.setMessage(z.state == UMessageFriendLocations.ZONE_ACTIVE
                    ? LocaleController.formatString(R.string.UMessageZoneActiveInfo, radius)
                    : LocaleController.getString(R.string.UMessageZonePendingOut));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageZoneNew), (d, w) -> startZoneEditor(f));
            b.setNegativeButton(LocaleController.getString(R.string.UMessageZoneRemove), (d, w) -> controller.removeZone(f.uid));
        }
        b.setNeutralButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void startZoneEditor(UMessageFriendLocations.Friend f) {
        Context context = getParentActivity();
        if (context == null || mapboxMap == null) return;
        zoneUid = f.uid;
        select(0);
        sheet.setVisibility(View.GONE);
        if (f.location != null) flyTo(f.location.lat, f.location.lng, 13);

        zoneEditor = new FrameLayout(context);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16) + AndroidUtilities.navigationBarHeight);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cBg);
        bg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        card.setBackground(bg);
        card.setElevation(dp(16));
        card.setClickable(true);
        TextView title = text(context, 20, cText, true);
        title.setText(LocaleController.getString(R.string.UMessageZoneEditorTitle) + " · " + UserObject.getFirstName(getMessagesController().getUser(f.uid)));
        card.addView(title);
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setTextColor(cSecondary);
        hint.setText(LocaleController.getString(R.string.UMessageZoneEditorHint));
        card.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 12));
        TextView radiusLabel = text(context, 16, cText, true);
        radiusLabel.setText(LocaleController.formatString(R.string.UMessageZoneRadius, UMessageFriendLocations.formatRadius(zoneRadius)));
        card.addView(radiusLabel);
        android.widget.SeekBar seek = new android.widget.SeekBar(context);
        seek.setMax(RADII.length - 1);
        int start = 4;
        for (int i = 0; i < RADII.length; i++) if (RADII[i] == zoneRadius) start = i;
        seek.setProgress(start);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                zoneRadius = RADII[progress];
                radiusLabel.setText(LocaleController.formatString(R.string.UMessageZoneRadius, UMessageFriendLocations.formatRadius(zoneRadius)));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        card.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 6, 0, 10));
        LinearLayout buttons = new LinearLayout(context);
        TextView cancel = pill(context, LocaleController.getString(R.string.Cancel), dark ? 0xff2c2c2e : 0xfff2f2f7, cText, 16);
        cancel.setOnClickListener(v -> closeZoneEditor());
        buttons.addView(cancel, LayoutHelper.createLinear(0, 50, 1f, 0, 0, 10, 0));
        TextView send = pill(context, LocaleController.getString(R.string.UMessageZoneSend), 0, 0xffffffff, 16);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff64d2ff, 0xff0a84ff});
        g.setCornerRadius(dp(14));
        send.setBackground(g);
        send.setOnClickListener(v -> {
            Point c = mapboxMap.getCameraState().getCenter();
            controller.proposeZone(zoneUid, c.latitude(), c.longitude(), zoneRadius);
            TLRPC.User user = getMessagesController().getUser(zoneUid);
            closeZoneEditor();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageZoneSent, UserObject.getFirstName(user))).show();
        });
        buttons.addView(send, LayoutHelper.createLinear(0, 50, 1.4f));
        card.addView(buttons);
        zoneEditor.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(zoneEditor, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        zoneEditor.setClickable(false);
        zoneFollow.run();
    }

    private void closeZoneEditor() {
        AndroidUtilities.cancelRunOnUIThread(zoneFollow);
        if (zoneEditor != null) {
            root.removeView(zoneEditor);
            zoneEditor = null;
        }
        overlay.setEditZone(null);
        sheet.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- selection card

    private void select(long uid) {
        selectedUid = uid;
        UMessageFriendLocations.Friend f = uid != 0 ? controller.get(uid) : null;
        selectedCard.removeAllViews();
        if (f == null || f.location == null) {
            selectedCard.setVisibility(View.GONE);
            selectedUid = 0;
            return;
        }
        selectedCard.addView(createSelectedCard(selectedCard.getContext(), f));
        selectedCard.setVisibility(View.VISIBLE);
        selectedCard.setAlpha(0f);
        sheet.collapse();
        selectedCard.post(() -> {
            onSheetMoved();
            selectedCard.animate().alpha(1f).setDuration(200).start();
            flyTo(f.location.lat, f.location.lng, 16.5);
        });
    }

    private View createSelectedCard(Context context, UMessageFriendLocations.Friend f) {
        TLRPC.User user = getMessagesController().getUser(f.uid);
        LinearLayout card = new LinearLayout(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff1d2330, 0xff11141c});
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setElevation(dp(8));

        BackupImageView avatar = avatarView(context, user, 46);
        card.addView(avatar, LayoutHelper.createLinear(46, 46, 0, 0, 12, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, 17, 0xffffffff, true);
        name.setText(UserObject.getUserName(user));
        texts.addView(name);
        TextView sub = text(context, 13, 0xb3ebebf5, false);
        sub.setText(details(f, true));
        texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView zone = pill(context, "🎯", f.zone != null && f.zone.state == UMessageFriendLocations.ZONE_ACTIVE ? 0x4030d158 : 0x1fffffff, 0xffffffff, 20);
        zone.setPadding(0, 0, 0, 0);
        zone.setOnClickListener(v -> onZoneButton(f));
        card.addView(zone, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        TextView hi = pill(context, HI, 0x1fffffff, 0xffffffff, 20);
        hi.setPadding(0, 0, 0, 0);
        hi.setOnClickListener(v -> sayHi(f.uid));
        card.addView(hi, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        ImageView chat = new ImageView(context);
        chat.setScaleType(ImageView.ScaleType.CENTER);
        chat.setImageResource(R.drawable.filled_profile_message_24);
        chat.setColorFilter(0xffffffff);
        chat.setBackground(rounded(0x1fffffff, dp(22)));
        chat.setOnClickListener(v -> openChat(f.uid));
        pressScale(chat);
        card.addView(chat, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));

        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setAlpha(0.6f);
        close.setOnClickListener(v -> select(0));
        card.addView(close, LayoutHelper.createLinear(36, 44, 2, 0, 0, 0));
        return card;
    }

    // ---------------------------------------------------------------- sheet

    /** Bottom sheet that can be dragged between a peek and an almost full-height list. */
    private class SheetLayout extends LinearLayout {
        private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final ScrollView scroll;
        private float downY, startTranslation;
        private boolean dragging;
        private VelocityTracker velocity;
        private ValueAnimator animator;

        SheetLayout(Context context) {
            super(context);
            setOrientation(VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(cBg);
            bg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
            setBackground(bg);
            setElevation(dp(16));
            setClickable(true);

            View grabber = new View(context);
            grabber.setBackground(rounded(dark ? 0x66ebebf5 : 0x4d3c3c43, dp(2.5f)));
            addView(grabber, LayoutHelper.createLinear(36, 5, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 8));

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(context, 22, cText, true);
            title.setText(LocaleController.getString(R.string.UMessageFriendMapFriends));
            header.addView(title);
            countView = text(context, 15, cSecondary, false);
            header.addView(countView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 8, 3, 0, 0));
            characterButton = new ImageView(context);
            characterButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
            characterButton.setPadding(dp(4), dp(4), dp(4), dp(4));
            characterButton.setBackground(rounded(dark ? 0xff2c2c2e : 0xfff2f2f7, dp(18)));
            characterButton.setOnClickListener(v -> showCharacterPicker());
            pressScale(characterButton);
            updateCharacterButton();
            header.addView(characterButton, LayoutHelper.createLinear(36, 36, 0, 0, 10, 0));
            ImageView add = new ImageView(context);
            add.setScaleType(ImageView.ScaleType.CENTER);
            add.setImageResource(R.drawable.msg_add);
            add.setColorFilter(0xffffffff);
            GradientDrawable addBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff64d2ff, 0xff0a84ff});
            addBg.setShape(GradientDrawable.OVAL);
            add.setBackground(addBg);
            add.setOnClickListener(v -> openPicker());
            pressScale(add);
            header.addView(add, LayoutHelper.createLinear(36, 36));
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 0, 16, 8));

            scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(OVER_SCROLL_NEVER);
            listLayout = new LinearLayout(context);
            listLayout.setOrientation(VERTICAL);
            listLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(12));
            scroll.addView(listLayout);
            addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        }

        int peek() {
            return dp(268) + AndroidUtilities.navigationBarHeight;
        }

        int collapsedTranslation() {
            return Math.max(0, getHeight() - peek());
        }

        int visibleHeight() {
            return getHeight() == 0 ? peek() : (int) (getHeight() - getTranslationY());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            boolean first = getHeight() > 0 && getTag() == null;
            super.onLayout(changed, l, t, r, b);
            if (first || (changed && getTag() == null)) {
                setTag(1);
                setTranslationY(collapsedTranslation());
                onSheetMoved();
            }
        }

        @Override
        public void setTranslationY(float translationY) {
            super.setTranslationY(translationY);
            onSheetMoved();
        }

        void collapse() {
            animateTo(collapsedTranslation());
        }

        void animateTo(float target) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(getTranslationY(), target);
            animator.addUpdateListener(a -> setTranslationY((float) a.getAnimatedValue()));
            animator.setDuration(320);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getRawY();
                    startTranslation = getTranslationY();
                    dragging = false;
                    if (animator != null) animator.cancel();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dy) > touchSlop) {
                        boolean expanded = getTranslationY() <= 1;
                        boolean listAtTop = !scroll.canScrollVertically(-1);
                        boolean inList = ev.getY() > scroll.getTop();
                        if (!expanded || !inList || (dy > 0 && listAtTop)) {
                            dragging = true;
                            downY = ev.getRawY();
                            startTranslation = getTranslationY();
                            return true;
                        }
                    }
                    break;
                }
            }
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (velocity == null) velocity = VelocityTracker.obtain();
            velocity.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getRawY();
                    startTranslation = getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float t = Math.max(0, Math.min(collapsedTranslation(), startTranslation + ev.getRawY() - downY));
                    setTranslationY(t);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    velocity.computeCurrentVelocity(1000);
                    float vy = velocity.getYVelocity();
                    velocity.recycle();
                    velocity = null;
                    boolean expand = Math.abs(vy) > dp(400) ? vy < 0 : getTranslationY() < collapsedTranslation() / 2f;
                    animateTo(expand ? 0 : collapsedTranslation());
                    dragging = false;
                    return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private void rebuild() {
        if (listLayout == null) return;
        Context context = listLayout.getContext();
        listLayout.removeAllViews();
        ArrayList<UMessageFriendLocations.Friend> all = controller.getAll();
        ArrayList<UMessageFriendLocations.Friend> incoming = new ArrayList<>(), friendsList = new ArrayList<>(), outgoing = new ArrayList<>();
        for (UMessageFriendLocations.Friend f : all) {
            if (getMessagesController().getUser(f.uid) == null) continue;
            (f.state == UMessageFriendLocations.STATE_INCOMING ? incoming : f.state == UMessageFriendLocations.STATE_FRIEND ? friendsList : outgoing).add(f);
        }
        countView.setText(friendsList.isEmpty() ? "" : String.valueOf(friendsList.size()));

        UMessageFriendLocations.Friend focused = controller.get(focusUserId);
        if (focusUserId != 0 && (focused == null || focused.state != UMessageFriendLocations.STATE_FRIEND)) {
            listLayout.addView(createInviteCard(context, focusUserId, focused), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 10));
        }
        listLayout.addView(createBackgroundRow(context));
        if (!incoming.isEmpty()) {
            listLayout.addView(sectionHeader(context, R.string.UMessageFriendMapRequests));
            for (int i = 0; i < incoming.size(); i++) {
                listLayout.addView(createRow(context, incoming.get(i), i != incoming.size() - 1));
            }
        }
        if (!friendsList.isEmpty()) {
            if (!incoming.isEmpty()) listLayout.addView(sectionHeader(context, R.string.UMessageFriendMapFriends));
            for (int i = 0; i < friendsList.size(); i++) {
                listLayout.addView(createRow(context, friendsList.get(i), i != friendsList.size() - 1));
            }
        }
        if (!outgoing.isEmpty()) {
            listLayout.addView(sectionHeader(context, R.string.UMessageFriendMapSent));
            for (int i = 0; i < outgoing.size(); i++) {
                listLayout.addView(createRow(context, outgoing.get(i), i != outgoing.size() - 1));
            }
        }
        if (all.isEmpty() && focusUserId == 0) {
            TextView empty = new TextView(context);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(cSecondary);
            empty.setGravity(Gravity.CENTER);
            empty.setText(LocaleController.getString(R.string.UMessageFriendMapEmpty));
            listLayout.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 36, 24, 36, 24));
        }
        updateMarkers();
        if (selectedUid != 0) {
            UMessageFriendLocations.Friend f = controller.get(selectedUid);
            if (f == null || f.location == null) {
                select(0);
            } else if (selectedCard.getChildCount() > 0) {
                selectedCard.removeAllViews();
                selectedCard.addView(createSelectedCard(selectedCard.getContext(), f));
            }
        }
        if (!introDone) intro();
    }

    /** Background mode switch: keep sending our position while the app is closed. */
    private View createBackgroundRow(Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.live_loc);
        icon.setColorFilter(0xffffffff);
        GradientDrawable iconBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff64d2ff, 0xff0a84ff});
        iconBg.setCornerRadius(dp(9));
        icon.setBackground(iconBg);
        row.addView(icon, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
        TextView title = text(context, 16, cText, false);
        title.setText(LocaleController.getString(R.string.UMessageFriendMapBackground));
        row.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 64, 11, 70, 0));
        TextView sub = text(context, 13, cSecondary, false);
        sub.setText(LocaleController.getString(R.string.UMessageFriendMapBackgroundInfo));
        row.addView(sub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 64, 33, 70, 0));
        org.telegram.ui.Components.Switch toggle = new org.telegram.ui.Components.Switch(context);
        toggle.setChecked(UMessageFriendLocations.isBackgroundEnabled(), false);
        row.addView(toggle, LayoutHelper.createFrame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
        row.setOnClickListener(v -> {
            boolean enable = !UMessageFriendLocations.isBackgroundEnabled();
            if (enable && !UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
                pendingAction = () -> setBackground(true, toggle);
                permissionAsked = false;
                requestPermission();
                return;
            }
            setBackground(enable, toggle);
        });
        View line = new View(context);
        line.setBackgroundColor(cSeparator);
        row.addView(line, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 64, 0, 0, 0));
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        return row;
    }

    private void setBackground(boolean enable, org.telegram.ui.Components.Switch toggle) {
        if (enable && Build.VERSION.SDK_INT >= 33 && getParentActivity() != null
                && getParentActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        UMessageFriendLocations.setBackgroundEnabled(enable);
        toggle.setChecked(enable, true);
        BulletinFactory.of(this).createSimpleBulletin(enable ? R.raw.contact_check : R.raw.chats_infotip,
                LocaleController.getString(enable ? (controller.hasFriends() ? R.string.UMessageFriendMapBackgroundOn : R.string.UMessageFriendMapBackgroundOnLater) : R.string.UMessageFriendMapBackgroundOff)).show();
    }

    private void updateCharacterButton() {
        if (characterButton == null) return;
        TLRPC.Document doc = UMessageMapCharacters.document(currentAccount, UMessageFriendLocations.getMyCharacter());
        if (doc != null) {
            org.telegram.messenger.ImageReceiver r = new org.telegram.messenger.ImageReceiver(characterButton);
            r.setImage(org.telegram.messenger.ImageLocation.getForDocument(doc), "80_80", null, "tgs", doc, 1);
            r.setAllowStartLottieAnimation(false);
            r.onAttachedToWindow();
            characterButton.setImageDrawable(new android.graphics.drawable.Drawable() {
                @Override public void draw(android.graphics.Canvas canvas) { android.graphics.Rect b = getBounds(); r.setImageCoords(b.left, b.top, b.width(), b.height()); r.draw(canvas); }
                @Override public void setAlpha(int alpha) {}
                @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
                @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
                @Override public int getIntrinsicWidth() { return dp(28); }
                @Override public int getIntrinsicHeight() { return dp(28); }
            });
            characterButton.clearColorFilter();
        } else {
            characterButton.setImageResource(R.drawable.msg_location);
            characterButton.setColorFilter(0xffff3b30);
        }
    }

    /** A character's Telegram animated emoji (null until the emoji set is loaded). */
    private View characterView(Context context, String id, boolean animate) {
        TLRPC.Document doc = UMessageMapCharacters.document(currentAccount, id);
        if (doc == null) return null;
        BackupImageView image = new BackupImageView(context);
        image.setImage(org.telegram.messenger.ImageLocation.getForDocument(doc), "100_100", "tgs", null, doc);
        image.getImageReceiver().setAutoRepeat(animate ? 1 : 0);
        image.getImageReceiver().setAllowStartLottieAnimation(animate);
        return image;
    }

    /** Grid of 3D characters; the choice is how friends see us on their map. */
    private void showCharacterPicker() {
        Context context = getParentActivity();
        if (context == null) return;
        org.telegram.ui.ActionBar.BottomSheet bottomSheet = new org.telegram.ui.ActionBar.BottomSheet(context, false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(12));
        TextView title = text(context, 20, cText, true);
        title.setText(LocaleController.getString(R.string.UMessageFriendMapCharacterTitle));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(cSecondary);
        info.setText(LocaleController.getString(R.string.UMessageFriendMapCharacterInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 14));

        android.widget.GridLayout grid = new android.widget.GridLayout(context);
        int columns = 5;
        grid.setColumnCount(columns);
        int cell = (AndroidUtilities.displaySize.x - dp(32)) / columns;
        String currentEmoji = UMessageMapCharacters.emojiOf(UMessageFriendLocations.getMyCharacter());
        String current = currentEmoji == null ? "" : UMessageMapCharacters.idFor(currentEmoji);
        // every emoji Telegram itself animates
        ArrayList<String> ids = new ArrayList<>();
        ids.add("");
        for (String emoji : UMessageMapCharacters.allAnimated(currentAccount)) ids.add(UMessageMapCharacters.idFor(emoji));
        for (String id : ids) {
            FrameLayout tile = new FrameLayout(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(dark ? 0xff2c2c2e : 0xfff2f2f7);
            bg.setCornerRadius(dp(18));
            if (id.equals(current)) bg.setStroke(dp(2.5f), cAccent);
            tile.setBackground(bg);
            if (id.isEmpty()) {
                ImageView pin = new ImageView(context);
                pin.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                pin.setImageResource(R.drawable.msg_location);
                pin.setColorFilter(0xffff3b30);
                tile.addView(pin, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
            } else {
                View image = characterView(context, id, id.equals(current)); // the chosen one plays, the rest show their first pose
                if (image == null) continue; // a Telegram 3D face whose animation is not available
                tile.addView(image, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
            }
            tile.setOnClickListener(v -> {
                UMessageFriendLocations.setMyCharacter(id);
                bottomSheet.dismiss();
                updateCharacterButton();
                applyPuck();
            });
            pressScale(tile);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = cell - dp(8);
            lp.height = cell - dp(8);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(tile, lp);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(grid);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bottomSheet.setCustomView(content);
        showDialog(bottomSheet);
    }

    private View sectionHeader(Context context, int res) {
        TextView view = text(context, 13, cSecondary, true);
        view.setText(LocaleController.getString(res).toUpperCase());
        view.setPadding(dp(18), dp(14), dp(18), dp(6));
        return view;
    }

    /** Opened from a profile of someone who is not a friend yet. */
    private View createInviteCard(Context context, long uid, UMessageFriendLocations.Friend f) {
        TLRPC.User user = getMessagesController().getUser(uid);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff1d2330, 0xff11141c});
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(avatarView(context, user, 52), LayoutHelper.createLinear(52, 52, 0, 0, 12, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, 18, 0xffffffff, true);
        name.setText(UserObject.getUserName(user));
        texts.addView(name);
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(0xb3ebebf5);
        int hintRes = f == null ? R.string.UMessageFriendMapInviteHint : f.state == UMessageFriendLocations.STATE_INCOMING ? R.string.UMessageFriendMapRequestHint : R.string.UMessageFriendMapWaitingHint;
        hint.setText(LocaleController.formatString(hintRes, UserObject.getFirstName(user)));
        texts.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        top.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        card.addView(top);

        TextView button;
        if (f == null) {
            button = pill(context, LocaleController.getString(R.string.UMessageFriendMapSendRequest), 0, 0xffffffff, 16);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff64d2ff, 0xff0a84ff});
            g.setCornerRadius(dp(14));
            button.setBackground(g);
            button.setOnClickListener(v -> sendRequest(uid));
        } else if (f.state == UMessageFriendLocations.STATE_INCOMING) {
            button = pill(context, LocaleController.getString(R.string.UMessageFriendMapAccept), 0, 0xffffffff, 16);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff30d158, 0xff34c759});
            g.setCornerRadius(dp(14));
            button.setBackground(g);
            button.setOnClickListener(v -> accept(uid));
        } else {
            button = pill(context, LocaleController.getString(R.string.UMessageFriendMapCancelRequest), 0x1fffffff, 0xffffffff, 16);
            button.setOnClickListener(v -> controller.remove(uid));
        }
        card.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 0));
        return card;
    }

    /** Looks like a chat list row; the distance takes the place of the last message. */
    private View createRow(Context context, UMessageFriendLocations.Friend f, boolean divider) {
        TLRPC.User user = getMessagesController().getUser(f.uid);
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.addView(avatarView(context, user, 54), LayoutHelper.createFrame(54, 54, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        String friendCharacter = f.location != null ? f.location.character : null;
        View badge = TextUtils.isEmpty(friendCharacter) ? null : characterView(context, friendCharacter, false);
        if (badge != null) {
            FrameLayout holder = new FrameLayout(context);
            holder.setBackground(rounded(cBg, dp(13)));
            holder.setPadding(dp(2), dp(2), dp(2), dp(2));
            holder.addView(badge, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            row.addView(holder, LayoutHelper.createFrame(26, 26, Gravity.LEFT | Gravity.CENTER_VERTICAL, 44, 22, 0, 0));
        }

        TextView name = text(context, 16, cText, true);
        name.setText(UserObject.getUserName(user));
        row.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 76, 13, f.state == UMessageFriendLocations.STATE_FRIEND ? 64 : 170, 0));
        TextView sub = text(context, 15, cSecondary, false);
        row.addView(sub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 76, 37, f.state == UMessageFriendLocations.STATE_FRIEND ? 16 : 170, 0));

        if (f.state == UMessageFriendLocations.STATE_FRIEND) {
            sub.setText(details(f, false));
            if (f.location != null) {
                TextView time = text(context, 13, getThemedColor(Theme.key_chats_date), false);
                time.setText(LocaleController.stringForMessageListDate(f.location.time / 1000));
                row.addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 15, 16, 0));
            }
            row.setOnClickListener(v -> {
                if (f.location != null) {
                    select(f.uid);
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatString(R.string.UMessageFriendMapNoLocationYet, UserObject.getFirstName(user))).show();
                }
            });
            row.setOnLongClickListener(v -> {
                confirmRemove(f.uid);
                return true;
            });
        } else if (f.state == UMessageFriendLocations.STATE_INCOMING) {
            sub.setText(LocaleController.getString(R.string.UMessageFriendMapWantsToAdd));
            sub.setTextColor(cAccent);
            LinearLayout actions = new LinearLayout(context);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            TextView accept = pill(context, LocaleController.getString(R.string.UMessageFriendMapAccept), cAccent, 0xffffffff, 14);
            accept.setOnClickListener(v -> accept(f.uid));
            actions.addView(accept, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
            ImageView decline = new ImageView(context);
            decline.setScaleType(ImageView.ScaleType.CENTER);
            decline.setImageResource(R.drawable.ic_close_white);
            decline.setColorFilter(cSecondary);
            decline.setBackground(rounded(dark ? 0xff2c2c2e : 0xfff2f2f7, dp(16)));
            decline.setOnClickListener(v -> controller.remove(f.uid));
            pressScale(decline);
            actions.addView(decline, LayoutHelper.createLinear(32, 32, 8, 0, 0, 0));
            row.addView(actions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        } else {
            sub.setText(LocaleController.getString(R.string.UMessageFriendMapRequestSent));
            TextView cancel = pill(context, LocaleController.getString(R.string.Cancel), dark ? 0xff2c2c2e : 0xfff2f2f7, cSecondary, 14);
            cancel.setOnClickListener(v -> controller.remove(f.uid));
            row.addView(cancel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        }

        if (divider) {
            View line = new View(context);
            line.setBackgroundColor(cSeparator);
            row.addView(line, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 76, 0, 0, 0));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));
        return row;
    }

    private String details(UMessageFriendLocations.Friend f, boolean withTime) {
        String base = detailsBase(f, withTime);
        if (f.zone == null) return base;
        String zone = f.zone.state == UMessageFriendLocations.ZONE_ACTIVE
                ? LocaleController.formatString(R.string.UMessageZoneShort, UMessageFriendLocations.formatRadius(f.zone.radius))
                : LocaleController.getString(R.string.UMessageZoneWaiting);
        return base + " · 🎯 " + zone;
    }

    private String detailsBase(UMessageFriendLocations.Friend f, boolean withTime) {
        if (f.location == null) {
            return LocaleController.getString(R.string.UMessageFriendMapNoLocationShort);
        }
        StringBuilder sb = new StringBuilder();
        Location me = myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation();
        if (me != null) {
            float[] d = new float[1];
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), f.location.lat, f.location.lng, d);
            sb.append(LocaleController.formatString(R.string.UMessageFriendMapAway, LocaleController.formatDistance(d[0], 0)));
        }
        float speed = overlay != null ? overlay.getSpeed(f.uid) : f.location.speedKmh;
        if (speed >= 1f) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(LocaleController.getString(R.string.UMessageFriendMapSpeed), Math.round(speed)));
        }
        if (withTime || sb.length() == 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(LocaleController.formatLocationUpdateDate(f.location.time / 1000));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- actions

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("needForwardCount", false);
        ContactsActivity activity = new ContactsActivity(args);
        activity.setDelegate((user, param, a) -> AndroidUtilities.runOnUIThread(() -> {
            if (user == null) return;
            UMessageFriendLocations.Friend f = controller.get(user.id);
            if (f == null) {
                sendRequest(user.id);
            } else if (f.state == UMessageFriendLocations.STATE_INCOMING) {
                accept(user.id);
            } else if (f.state == UMessageFriendLocations.STATE_FRIEND) {
                select(user.id);
            }
        }, 300));
        presentFragment(activity);
    }

    private void sendRequest(long uid) {
        controller.sendRequest(uid);
        TLRPC.User user = getMessagesController().getUser(uid);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageFriendMapRequestSentTo, UserObject.getFirstName(user))).show();
    }

    private void accept(long uid) {
        if (!UMessageFriendLocations.hasPermission(ApplicationLoader.applicationContext)) {
            pendingAction = () -> accept(uid);
            permissionAsked = false;
            requestPermission();
            return;
        }
        controller.accept(uid);
        UMessageFriendLocations.restartTracking();
    }

    private void confirmRemove(long uid) {
        if (getParentActivity() == null) return;
        TLRPC.User user = getMessagesController().getUser(uid);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.UMessageFriendMapRemoveTitle));
        builder.setMessage(LocaleController.formatString(R.string.UMessageFriendMapRemoveInfo, UserObject.getFirstName(user)));
        builder.setPositiveButton(LocaleController.getString(R.string.UMessageFriendMapRemove), (d, w) -> {
            if (selectedUid == uid) select(0);
            controller.remove(uid);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) button.setTextColor(getThemedColor(Theme.key_text_RedBold));
    }

    private void openChat(long uid) {
        Bundle args = new Bundle();
        args.putLong("user_id", uid);
        presentFragment(new ChatActivity(args));
    }

    /** Waves at a friend: a hi message plus where we are right now. */
    private void sayHi(long uid) {
        Location l = myLocation != null ? myLocation : UMessageFriendLocations.getLastLocation();
        getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(LocaleController.getString(R.string.UMessageFriendMapHiText), uid));
        if (l != null) {
            TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
            media.geo = new TLRPC.TL_geoPoint();
            media.geo.lat = AndroidUtilities.fixLocationCoord(l.getLatitude());
            media.geo._long = AndroidUtilities.fixLocationCoord(l.getLongitude());
            getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(media, uid, null, null, null, null, true, 0, 0));
        }
        if (overlay != null) overlay.sayHi(uid);
        TLRPC.User user = getMessagesController().getUser(uid);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageFriendMapHiSent, UserObject.getFirstName(user))).show();
    }

    // ---------------------------------------------------------------- view helpers

    private BackupImageView avatarView(Context context, TLRPC.User user, int size) {
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(size / 2f));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, user);
        avatar.setForUserOrChat(user, avatarDrawable);
        return avatar;
    }

    private ImageView roundButton(Context context, int icon) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(dark ? 0xffffffff : 0xff000000);
        view.setBackground(rounded(cButton, dp(22)));
        view.setElevation(dp(3));
        pressScale(view);
        return view;
    }

    private ImageView controlButton(Context context, int icon, View.OnClickListener listener) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(cAccent);
        view.setOnClickListener(listener);
        pressScale(view);
        return view;
    }

    private TextView pill(Context context, String label, int bgColor, int textColor, float textSize) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(rounded(bgColor, dp(textSize >= 16 ? 14 : 16)));
        pressScale(view);
        return view;
    }

    private static TextView text(Context context, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void pressScale(View view) {
        view.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.85f).setDuration(120).start();
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
            return false;
        });
    }
}
