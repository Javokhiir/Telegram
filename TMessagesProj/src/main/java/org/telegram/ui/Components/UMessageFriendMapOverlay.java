package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.location.Location;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.PathParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Draws friends on top of the map: a 3D-style red pin (built from SVG path data) with the avatar in its
 * hole, a pulsing ground ring, a movement trail (footprints up to 7 km/h, a fading line above that),
 * the current speed, a waving U message flag while moving fast, and a "👋" bubble after a hi.
 */
public class UMessageFriendMapOverlay extends View {

    public interface Projector {
        /** Screen position of a coordinate in this view's space, or null while the map is not ready. */
        PointF toScreen(double lat, double lng);
    }

    public interface OnPinClick {
        void onClick(long uid);
    }

    public static final float WALK_KMH = 7f;
    private static final float MIN_SPEED_KMH = 2f;   // below this a person is standing
    private static final float MIN_MOVE_M = 20f;     // smallest step that is not GPS wobble
    private static final float MAX_SPEED_KMH = 250f; // faster than this is a bad fix (teleport)
    private static final int TRAIL_MAX = 40;
    private static final int TRAIL_SECONDS = 30 * 60;

    // SVG path data, viewBox 0 0 100 132
    private static final String PIN = "M50,3C24,3 6,22 6,46C6,72 33,98 50,126C67,98 94,72 94,46C94,22 76,3 50,3Z";
    private static final String PIN_BACK = "M44,3C18,3 0,22 0,46C0,72 27,98 46,126L50,126C33,98 6,72 6,46C6,22 24,3 50,3Z";
    private static final String FOOT = "M0,-7C3.2,-7 4.2,-2.5 3.6,1.8C3.1,5 1.7,7 0,7C-1.7,7 -3.1,5 -3.6,1.8C-4.2,-2.5 -3.2,-7 0,-7Z";
    // half of the U message logo (one mark), viewBox 0 0 740 425 shifted like umessage_mark.xml
    private static final String LOGO_HALF = "M0,0h370v74h-370zM370,0A212.5,212.5 0,0 0,370 425V351A138.5,138.5 0,0 1,370 74Z";

    public static class Pin {
        public long uid;
        public TLRPC.User user;
        public ImageReceiver avatar;
        double lat, lng, fromLat, fromLng, toLat, toLng;
        long moveStart;
        long appearStart;
        float speedKmh;
        long hiUntil;
        final ArrayList<double[]> trail = new ArrayList<>(); // lat, lng, unix seconds
        boolean alive = true;
        String character; // chosen 3D character id, null for the classic pin
        boolean faceRight;
        int lastFixDate;
        float accuracy;
        long lastMoveAt;
        long playStart;
    }

    private final ArrayList<Pin> pins = new ArrayList<>();
    private Projector projector;
    private OnPinClick onPinClick;
    private String speedFormat = "%d km/h";

    private final Path pinPath, pinBackPath, footPath, logoPath;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint characterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Path tmpPath = new Path();
    private Shader bodyShader, holeShader;
    private Bitmap flagBitmap;
    private final float[] mesh = new float[(FLAG_W + 1) * (FLAG_H + 1) * 2];
    private static final int FLAG_W = 10, FLAG_H = 4;

    private final float pinWidth = dp(54);
    private final float scale = pinWidth / 100f;
    private Pin pressed;

    public UMessageFriendMapOverlay(Context context) {
        super(context);
        pinPath = PathParser.createPathFromPathData(PIN);
        pinBackPath = PathParser.createPathFromPathData(PIN_BACK);
        footPath = PathParser.createPathFromPathData(FOOT);
        logoPath = PathParser.createPathFromPathData(LOGO_HALF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTypeface(AndroidUtilities.bold());
        bodyShader = new LinearGradient(20, 0, 80, 126, new int[]{0xffff6b63, 0xffff3b30, 0xffd7261e}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        holeShader = new RadialGradient(50, 46, 27, new int[]{0xffb81d17, 0xffe5312a}, new float[]{0.75f, 1f}, Shader.TileMode.CLAMP);
    }

    public void setProjector(Projector projector) {
        this.projector = projector;
        invalidate();
    }

    public void setOnPinClick(OnPinClick onPinClick) {
        this.onPinClick = onPinClick;
    }

    public void setSpeedFormat(String format) {
        speedFormat = format;
    }

    public Pin find(long uid) {
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).uid == uid) return pins.get(i);
        }
        return null;
    }

    /** Adds or moves a friend's pin; consecutive positions build the trail and the speed. */
    /**
     * Adds or moves a friend's pin. GPS wanders by tens of metres while standing still, so a fix only
     * counts as movement when it lands outside the accuracy circle (at least 20 m); jitter never extends
     * the trail or produces a speed, and speed fades back to zero once real movement stops.
     */
    public void update(long uid, TLRPC.User user, ImageReceiver avatar, double lat, double lng, int date, float knownSpeedKmh, float accuracyM, String character) {
        Pin pin = find(uid);
        long now = SystemClock.elapsedRealtime();
        if (pin == null) {
            pin = new Pin();
            pin.uid = uid;
            pin.lat = pin.fromLat = pin.toLat = lat;
            pin.lng = pin.fromLng = pin.toLng = lng;
            pin.appearStart = now;
            pin.lastFixDate = date;
            pin.accuracy = accuracyM;
            pins.add(pin);
            pin.trail.add(new double[]{lat, lng, date});
        } else if (date > pin.lastFixDate) {
            double[] anchor = pin.trail.get(pin.trail.size() - 1); // last confirmed position
            float[] dist = new float[1];
            Location.distanceBetween(anchor[0], anchor[1], lat, lng, dist);
            float noise = Math.max(MIN_MOVE_M, Math.max(accuracyM, pin.accuracy) * 1.2f);
            double seconds = date - anchor[2];
            float kmh = seconds > 0 ? (float) (dist[0] / seconds * 3.6) : 0f;
            boolean gpsSaysMoving = knownSpeedKmh >= MIN_SPEED_KMH;
            if ((dist[0] > noise || (gpsSaysMoving && dist[0] > MIN_MOVE_M)) && kmh < MAX_SPEED_KMH) {
                float measured = knownSpeedKmh >= 0 ? knownSpeedKmh : kmh;
                pin.speedKmh = pin.speedKmh == 0 ? measured : pin.speedKmh * 0.4f + measured * 0.6f;
                pin.lastMoveAt = now;
                pin.trail.add(new double[]{lat, lng, date});
                while (pin.trail.size() > TRAIL_MAX || (pin.trail.size() > 1 && date - pin.trail.get(0)[2] > TRAIL_SECONDS)) {
                    pin.trail.remove(0);
                }
                if (Math.abs(lng - pin.toLng) > 1e-6) {
                    pin.faceRight = lng > pin.toLng; // the artwork faces left
                }
                pin.fromLat = pin.lat;
                pin.fromLng = pin.lng;
                pin.toLat = lat;
                pin.toLng = lng;
                pin.moveStart = now;
            } else {
                // standing still: ignore the wobble, let the speed fade out
                pin.speedKmh = gpsSaysMoving ? knownSpeedKmh : pin.speedKmh * 0.5f;
                if (pin.speedKmh < MIN_SPEED_KMH) pin.speedKmh = 0;
            }
            pin.lastFixDate = date;
            pin.accuracy = accuracyM > 0 ? accuracyM : pin.accuracy;
        }
        pin.character = TextUtils.isEmpty(character) ? null : character;
        pin.user = user;
        pin.avatar = avatar;
        pin.alive = true;
        invalidate();
    }

    public void remove(long uid) {
        Pin pin = find(uid);
        if (pin != null) pins.remove(pin);
        invalidate();
    }

    public void sayHi(long uid) {
        Pin pin = find(uid);
        if (pin != null) {
            pin.hiUntil = SystemClock.elapsedRealtime() + 4500;
            invalidate();
        }
    }

    public float getSpeed(long uid) {
        Pin pin = find(uid);
        return pin == null ? 0 : pin.speedKmh;
    }

    // ---------------------------------------------------------------- drawing

    // ---------------------------------------------------------------- zones

    /** lat, lng, radius metres, active (1) or pending (0). */
    private final ArrayList<double[]> zones = new ArrayList<>();
    private double[] editZone;
    private final android.graphics.DashPathEffect dash = new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0);

    public void setZones(ArrayList<double[]> list) {
        zones.clear();
        zones.addAll(list);
        invalidate();
    }

    /** The circle being drawn in the zone editor (null when not editing). */
    public void setEditZone(double[] zone) {
        editZone = zone;
        invalidate();
    }

    private void drawZone(Canvas canvas, double lat, double lng, double radius, boolean active, boolean editing) {
        PointF c = projector.toScreen(lat, lng);
        PointF n = projector.toScreen(lat + radius / 111320.0, lng);
        if (c == null || n == null) return;
        float r = (float) Math.hypot(n.x - c.x, n.y - c.y);
        if (r < 2) return;
        int color = editing ? 0xff0a84ff : active ? 0xff30d158 : 0xffff9f0a;
        paint.setShader(null);
        paint.setColor(color);
        paint.setAlpha(editing ? 0x30 : 0x22);
        canvas.drawCircle(c.x, c.y, r, paint);
        strokePaint.setColor(color);
        strokePaint.setAlpha(0xdd);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setPathEffect(active && !editing ? null : dash);
        canvas.drawCircle(c.x, c.y, r, strokePaint);
        strokePaint.setPathEffect(null);
        if (editing) {
            paint.setColor(color);
            paint.setAlpha(255);
            canvas.drawCircle(c.x, c.y, dp(5), paint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (projector == null) {
            return;
        }
        for (double[] z : zones) drawZone(canvas, z[0], z[1], z[2], z[3] > 0, false);
        if (!zones.isEmpty()) postInvalidateOnAnimation(); // follow the camera
        if (editZone != null) {
            drawZone(canvas, editZone[0], editZone[1], editZone[2], true, true);
            postInvalidateOnAnimation();
        }
        if (pins.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        float t = (now % 100_000) / 1000f;
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            float p = Math.min(1f, (now - pin.moveStart) / 1200f);
            p = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(p);
            if (pin.speedKmh > 0 && now - pin.lastMoveAt > 90_000) pin.speedKmh = 0; // no real movement lately
            pin.lat = pin.fromLat + (pin.toLat - pin.fromLat) * p;
            pin.lng = pin.fromLng + (pin.toLng - pin.fromLng) * p;
            drawTrail(canvas, pin);
        }
        for (int i = 0; i < pins.size(); i++) {
            drawPin(canvas, pins.get(i), now, t);
        }
        postInvalidateOnAnimation();
    }

    private void drawTrail(Canvas canvas, Pin pin) {
        int n = pin.trail.size();
        if (n < 2) {
            return;
        }
        ArrayList<PointF> pts = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            PointF s = projector.toScreen(pin.trail.get(i)[0], pin.trail.get(i)[1]);
            if (s != null) pts.add(s);
        }
        PointF head = projector.toScreen(pin.lat, pin.lng);
        if (head == null || pts.size() < 1) return;
        pts.set(pts.size() - 1, head);
        if (pts.size() < 2) return;

        if (pin.speedKmh > WALK_KMH) {
            // fast: soft gradient line that fades toward the oldest point
            for (int i = 1; i < pts.size(); i++) {
                float a = i / (float) (pts.size() - 1);
                strokePaint.setStrokeWidth(dp(3 + 2 * a));
                strokePaint.setColor(0xffff3b30);
                strokePaint.setAlpha((int) (40 + 170 * a));
                canvas.drawLine(pts.get(i - 1).x, pts.get(i - 1).y, pts.get(i).x, pts.get(i).y, strokePaint);
            }
            return;
        }
        // walking: alternating footprints spaced along the path
        float total = 0;
        for (int i = 1; i < pts.size(); i++) {
            total += dist(pts.get(i - 1), pts.get(i));
        }
        if (total < dp(6)) return;
        float step = dp(15);
        int count = (int) (total / step);
        float acc = 0;
        int seg = 1;
        float segStart = 0;
        for (int k = 0; k <= count; k++) {
            float target = total - dp(22) - k * step; // start a bit behind the pin, walk back in time
            if (target < 0) break;
            acc = 0;
            seg = 1;
            segStart = 0;
            for (; seg < pts.size(); seg++) {
                float d = dist(pts.get(seg - 1), pts.get(seg));
                if (acc + d >= target) {
                    segStart = acc;
                    break;
                }
                acc += d;
            }
            if (seg >= pts.size()) break;
            PointF a = pts.get(seg - 1), b = pts.get(seg);
            float d = Math.max(1, dist(a, b));
            float f = (target - segStart) / d;
            float x = a.x + (b.x - a.x) * f, y = a.y + (b.y - a.y) * f;
            float angle = (float) Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)) + 90;
            float side = (k % 2 == 0 ? 1 : -1) * dp(4);
            float nx = -(b.y - a.y) / d, ny = (b.x - a.x) / d;
            float age = 1f - Math.min(1f, k * step / Math.max(total, 1));
            canvas.save();
            canvas.translate(x + nx * side, y + ny * side);
            canvas.rotate(angle);
            canvas.scale(dp(1) * 0.8f, dp(1) * 0.8f);
            paint.setShader(null);
            paint.setColor(0xffff3b30);
            paint.setAlpha((int) (60 + 170 * age));
            canvas.drawPath(footPath, paint);
            canvas.drawCircle(-2.6f, -10.2f, 1.5f, paint);
            canvas.drawCircle(0f, -11f, 1.4f, paint);
            canvas.drawCircle(2.4f, -10.3f, 1.3f, paint);
            canvas.restore();
        }
    }

    private void drawPin(Canvas canvas, Pin pin, long now, float t) {
        PointF s = projector.toScreen(pin.lat, pin.lng);
        if (s == null) return;
        float appear = Math.min(1f, (now - pin.appearStart) / 650f);
        float drop = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(appear);
        float bob = (float) Math.sin(t * 2.2f + pin.uid % 7) * dp(2.5f);

        float pinTop;
        ImageReceiver character = animatedFor(pin);
        if (character != null && character.hasBitmapImage()) {
            pinTop = drawCharacter(canvas, pin, character, s, drop, appear);
        } else {
            pinTop = drawRedPin(canvas, pin, s, drop, appear, bob, t);
        }
        if (pin.speedKmh > WALK_KMH) {
            drawFlag(canvas, s.x + pinWidth * 0.32f, pinTop + dp(6), t, appear);
        }
        if (pin.speedKmh >= MIN_SPEED_KMH) {
            drawSpeed(canvas, s.x, s.y + dp(14), Math.round(pin.speedKmh), appear);
        }
        if (now < pin.hiUntil) {
            drawHi(canvas, s.x, pinTop - dp(6), (pin.hiUntil - now) / 4500f, t);
        }
    }

    private float drawRedPin(Canvas canvas, Pin pin, PointF s, float drop, float appear, float bob, float t) {
        // ground ring: a static open ellipse plus a soft pulse
        float ringRx = dp(19), ringRy = dp(5.5f);
        strokePaint.setColor(0xffff3b30);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setAlpha((int) (230 * appear));
        rect.set(s.x - ringRx, s.y - ringRy, s.x + ringRx, s.y + ringRy);
        canvas.drawArc(rect, 110, 320, false, strokePaint);
        float pulse = (t * 0.7f + pin.uid % 5 * 0.2f) % 1f;
        strokePaint.setStrokeWidth(dp(1.5f));
        strokePaint.setAlpha((int) (150 * (1f - pulse) * appear));
        rect.set(s.x - ringRx * (1 + pulse), s.y - ringRy * (1 + pulse), s.x + ringRx * (1 + pulse), s.y + ringRy * (1 + pulse));
        canvas.drawOval(rect, strokePaint);

        // shadow under the tip
        paint.setShader(null);
        paint.setColor(0x33000000);
        float sh = 1f - Math.abs(bob) / dp(6);
        rect.set(s.x - dp(7) * sh, s.y - dp(2), s.x + dp(7) * sh, s.y + dp(2));
        canvas.drawOval(rect, paint);

        // pin body, tip resting just above the ground ring
        float pinH = 126 * scale;
        float top = s.y - pinH - dp(3) + bob - (1f - drop) * dp(40);
        canvas.save();
        canvas.translate(s.x - pinWidth / 2, top);
        canvas.scale(scale * (0.6f + 0.4f * drop), scale * (0.6f + 0.4f * drop), 50, 126);
        paint.setColor(0xffa3150f);
        canvas.drawPath(pinBackPath, paint);
        paint.setShader(bodyShader);
        canvas.drawPath(pinPath, paint);
        // gloss on the upper-left
        paint.setShader(null);
        paint.setColor(0x38ffffff);
        rect.set(16, 10, 46, 30);
        canvas.drawOval(rect, paint);
        // hole rim (darker inner bevel) and white disc
        paint.setShader(holeShader);
        canvas.drawCircle(50, 46, 27, paint);
        paint.setShader(null);
        paint.setColor(0xffffffff);
        canvas.drawCircle(51, 47, 24, paint);
        // avatar
        Bitmap bitmap = pin.avatar != null && pin.avatar.hasImageLoaded() ? pin.avatar.getBitmap() : null;
        if (bitmap != null && !bitmap.isRecycled()) {
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            matrix.reset();
            float sc = 42f / Math.min(bitmap.getWidth(), bitmap.getHeight());
            matrix.postScale(sc, sc);
            matrix.postTranslate(51 - bitmap.getWidth() * sc / 2, 47 - bitmap.getHeight() * sc / 2);
            shader.setLocalMatrix(matrix);
            avatarPaint.setShader(shader);
            canvas.drawCircle(51, 47, 21, avatarPaint);
        } else if (pin.user != null) {
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(pin.user);
            avatarDrawable.setBounds(30, 26, 72, 68);
            avatarDrawable.draw(canvas);
        }
        canvas.restore();

        return top;
    }

    /** A 3D cartoon character standing on the spot, hopping while moving, with the avatar as a badge. */
    private float drawCharacter(Canvas canvas, Pin pin, ImageReceiver character, PointF s, float drop, float appear) {
        boolean moving = pin.speedKmh >= MIN_SPEED_KMH;
        // Telegram's animation plays only while moving; standing still freezes it on the first pose
        RLottieDrawable lottie = character.getLottieAnimation();
        if (lottie != null) {
            if (moving) {
                if (!lottie.isRunning()) lottie.start();
            } else if (lottie.isRunning() || lottie.getCurrentFrame() != 0) {
                lottie.stop();
                lottie.setCurrentFrame(0, false);
            }
        }
        float hop = 0;
        float size = dp(68);
        float sc = 0.6f + 0.4f * drop;

        paint.setShader(null);
        paint.setColor(0x40000000);
        paint.setAlpha((int) (0x40 * appear));
        float sw = size * 0.34f * (1f - hop / dp(16));
        rect.set(s.x - sw, s.y - dp(3), s.x + sw, s.y + dp(3));
        canvas.drawOval(rect, paint);

        float bottom = s.y - hop - (1f - drop) * dp(40);
        canvas.save();
        canvas.translate(s.x, bottom);
        canvas.scale(pin.faceRight ? -sc : sc, sc);
        character.setImageCoords(-size / 2, -size, size, size);
        character.setAlpha(appear);
        character.draw(canvas);
        canvas.restore();

        float top = bottom - size * sc;
        float r = dp(12);
        float bx = s.x + size * 0.34f * sc, by = top + dp(8);
        paint.setColor(0xffffffff);
        paint.setAlpha((int) (255 * appear));
        paint.setShadowLayer(dp(2), 0, dp(1), 0x40000000);
        canvas.drawCircle(bx, by, r + dp(2), paint);
        paint.clearShadowLayer();
        Bitmap avatar = pin.avatar != null && pin.avatar.hasImageLoaded() ? pin.avatar.getBitmap() : null;
        if (avatar != null && !avatar.isRecycled()) {
            BitmapShader shader = new BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            matrix.reset();
            float k = r * 2 / Math.min(avatar.getWidth(), avatar.getHeight());
            matrix.postScale(k, k);
            matrix.postTranslate(bx - avatar.getWidth() * k / 2, by - avatar.getHeight() * k / 2);
            shader.setLocalMatrix(matrix);
            avatarPaint.setShader(shader);
            canvas.drawCircle(bx, by, r, avatarPaint);
        } else if (pin.user != null) {
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(pin.user);
            avatarDrawable.setBounds((int) (bx - r), (int) (by - r), (int) (bx + r), (int) (by + r));
            avatarDrawable.draw(canvas);
        }
        return top;
    }

    /** Kept for callers; characters are bundled sprites now, no account needed. */
    private int account = -1;
    private final java.util.HashMap<String, ImageReceiver> characters = new java.util.HashMap<>();
    private long lastLookup;

    public void setAccount(int account) {
        this.account = account;
    }

    /** A per-pin receiver of the character's Telegram animated emoji (null while the set loads). */
    private ImageReceiver animatedFor(Pin pin) {
        if (pin.character == null || account < 0) return null;
        String key = pin.uid + ":" + pin.character;
        ImageReceiver r = characters.get(key);
        if (r != null) return r;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLookup < 1500) return null;
        lastLookup = now;
        TLRPC.Document doc = UMessageMapCharacters.document(account, pin.character);
        if (doc == null) return null;
        r = new ImageReceiver(this);
        r.setCurrentAccount(account);
        r.setAllowStartLottieAnimation(true);
        r.setAutoRepeat(1);
        r.setImage(org.telegram.messenger.ImageLocation.getForDocument(doc), "160_160", null, "tgs", doc, 1);
        if (isAttachedToWindow()) r.onAttachedToWindow();
        characters.put(key, r);
        return r;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (ImageReceiver r : characters.values()) r.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (ImageReceiver r : characters.values()) r.onDetachedFromWindow();
    }

    private void drawSpeed(Canvas canvas, float cx, float y, int kmh, float alpha) {
        String label = String.format(speedFormat, kmh);
        textPaint.setTextSize(dp(12));
        float w = textPaint.measureText(label) + dp(16);
        rect.set(cx - w / 2, y, cx + w / 2, y + dp(22));
        paint.setShader(null);
        paint.setColor(kmh > WALK_KMH ? 0xf2ff3b30 : 0xe61c1c1e);
        paint.setAlpha((int) (paint.getAlpha() * alpha));
        canvas.drawRoundRect(rect, dp(11), dp(11), paint);
        textPaint.setColor(0xffffffff);
        textPaint.setAlpha((int) (255 * alpha));
        canvas.drawText(label, cx - w / 2 + dp(8), y + dp(15.5f), textPaint);
    }

    /** A flag on a short pole that waves (bitmap mesh), showing half of the U message logo. */
    private void drawFlag(Canvas canvas, float poleX, float poleTop, float t, float alpha) {
        if (flagBitmap == null) {
            flagBitmap = createFlagBitmap();
        }
        float fw = dp(30), fh = dp(20), poleH = dp(36);
        paint.setShader(null);
        paint.setColor(0xff3a3a3c);
        paint.setAlpha((int) (255 * alpha));
        rect.set(poleX - dp(1.2f), poleTop - poleH + dp(16), poleX + dp(1.2f), poleTop + dp(16));
        canvas.drawRoundRect(rect, dp(1.2f), dp(1.2f), paint);
        canvas.drawCircle(poleX, poleTop - poleH + dp(16), dp(2.2f), paint);

        float ox = poleX + dp(1), oy = poleTop - poleH + dp(18);
        int idx = 0;
        for (int y = 0; y <= FLAG_H; y++) {
            for (int x = 0; x <= FLAG_W; x++) {
                float fx = x / (float) FLAG_W;
                float wave = (float) Math.sin(fx * 5.5f - t * 9f) * dp(3) * fx;
                mesh[idx++] = ox + fx * fw - Math.abs(wave) * 0.25f;
                mesh[idx++] = oy + y / (float) FLAG_H * fh + wave;
            }
        }
        paint.setAlpha((int) (255 * alpha));
        canvas.drawBitmapMesh(flagBitmap, FLAG_W, FLAG_H, mesh, 0, null, 0, paint);
    }

    private Bitmap createFlagBitmap() {
        int w = dp(30), h = dp(20);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, 0xff64d2ff, 0xff0a84ff, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xffffffff);
        float logoH = h * 0.62f, sc = logoH / 425f;
        c.save();
        c.translate((w - 582.5f * sc) / 2f, (h - logoH) / 2f);
        c.scale(sc, sc);
        c.drawPath(logoPath, p);
        c.restore();
        return b;
    }

    private void drawHi(Canvas canvas, float cx, float bottom, float left, float t) {
        float a = Math.min(1f, Math.min(left * 4f, (1f - left) * 6f));
        float size = dp(38);
        rect.set(cx - size / 2, bottom - size - dp(6), cx + size / 2, bottom - dp(6));
        paint.setShader(null);
        paint.setColor(0xffffffff);
        paint.setAlpha((int) (255 * a));
        canvas.drawRoundRect(rect, size / 2, size / 2, paint);
        tmpPath.reset();
        tmpPath.moveTo(cx - dp(6), bottom - dp(7));
        tmpPath.lineTo(cx, bottom);
        tmpPath.lineTo(cx + dp(6), bottom - dp(7));
        tmpPath.close();
        canvas.drawPath(tmpPath, paint);
        textPaint.setTextSize(dp(20));
        textPaint.setColor(0xff000000);
        textPaint.setAlpha((int) (255 * a));
        canvas.save();
        canvas.rotate((float) Math.sin(t * 14f) * 18f, rect.centerX() + dp(4), rect.bottom - dp(8));
        float tw = textPaint.measureText("👋");
        canvas.drawText("👋", rect.centerX() - tw / 2, rect.centerY() + dp(7), textPaint);
        canvas.restore();
    }

    private static float dist(PointF a, PointF b) {
        return (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    // ---------------------------------------------------------------- touch

    private Pin hit(float x, float y) {
        if (projector == null) return null;
        for (int i = pins.size() - 1; i >= 0; i--) {
            Pin pin = pins.get(i);
            PointF s = projector.toScreen(pin.lat, pin.lng);
            if (s == null) continue;
            if (Math.abs(x - s.x) < pinWidth / 2 + dp(4) && y < s.y + dp(8) && y > s.y - 126 * scale - dp(8)) {
                return pin;
            }
        }
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            pressed = hit(event.getX(), event.getY());
            return pressed != null;
        }
        if (event.getAction() == MotionEvent.ACTION_UP && pressed != null) {
            if (hit(event.getX(), event.getY()) == pressed && onPinClick != null) {
                onPinClick.onClick(pressed.uid);
            }
            pressed = null;
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            pressed = null;
        }
        return pressed != null;
    }
}
