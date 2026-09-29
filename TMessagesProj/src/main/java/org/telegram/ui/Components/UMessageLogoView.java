package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * U message mark: the original "7C" mark ("me") plus its twin rotated 180° about the
 * ring centre ("U"), which together close the circle.
 *
 * Intro: the bar and the arc are drawn, then the twin peels off the mark and swings
 * 180° to close the circle. While paging, the twin makes a half turn per page,
 * so pages alternate between the single mark and the closed circle.
 */
public class UMessageLogoView extends View {

    // Mark geometry in source units (see branding/umessage/umessage-logo.svg)
    private static final float BAR_X1 = 155, BAR_X2 = 525, BAR_Y = 49;
    private static final float CX = 525, CY = 224.5f, R = 175.5f, STROKE = 74;
    private static final float MIN_X = 155, MAX_X = 895, MIN_Y = 12, MAX_Y = 437;

    // Keep the original 2900ms drawing timeline, but play it much faster. This keeps
    // all choreography intact while the first-launch screen becomes responsive.
    private static final long TIMELINE_DURATION = 2900;
    private static final long PLAYBACK_DURATION = 900;
    private static final long TWIN_START = 1450;

    private static final CubicBezierInterpolator DRAW = new CubicBezierInterpolator(.65, 0, .35, 1);
    private static final CubicBezierInterpolator SWING = new CubicBezierInterpolator(.7, 0, .2, 1.15);
    private static final CubicBezierInterpolator SETTLE = new CubicBezierInterpolator(.34, 1.56, .64, 1);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF(CX - R, CY - R, CX + R, CY + R);

    private ValueAnimator animator;
    private float time = 1f; // 0..1 of the intro animation
    private float pagePosition;

    public UMessageLogoView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(STROKE);
        setOnClickListener(v -> play());
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setPagePosition(float position) {
        pagePosition = position;
        invalidate();
    }

    public void play() {
        play(false);
    }

    /** @param markDrawn the mark is already drawn (e.g. by the splash): only the twin swings in */
    public void play(boolean markDrawn) {
        if (animator != null) {
            animator.cancel();
        }
        float from = markDrawn ? TWIN_START / (float) TIMELINE_DURATION : 0f;
        time = from;
        invalidate();
        animator = ValueAnimator.ofFloat(from, 1);
        animator.setDuration((long) (PLAYBACK_DURATION * (1f - from)));
        animator.addUpdateListener(a -> {
            time = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        time = 1f;
    }

    private float segment(float startMs, float durationMs) {
        float t = (time * TIMELINE_DURATION - startMs) / durationMs;
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(w / (MAX_X - MIN_X), h / (MAX_Y - MIN_Y));

        float bar = DRAW.getInterpolation(segment(100, 550));
        float arc = DRAW.getInterpolation(segment(500, 900));
        float swingT = segment(TWIN_START, 1100);
        float swing = SWING.getInterpolation(swingT);
        float settleT = segment(2400, 500);
        float settle = settleT <= 0 || settleT >= 1 ? 1f : 1f - 0.04f * (float) Math.sin(Math.PI * SETTLE.getInterpolation(settleT));

        canvas.save();
        canvas.translate(getPaddingLeft() + w / 2f, getPaddingTop() + h / 2f);
        canvas.scale(scale * settle, scale * settle);
        canvas.translate(-(MIN_X + MAX_X) / 2f, -(MIN_Y + MAX_Y) / 2f);

        // "me": the original mark
        drawMark(canvas, bar, arc);

        // "U": the twin, only once the mark is drawn
        if (swingT > 0) {
            canvas.save();
            canvas.rotate(180f * swing + 180f * pagePosition, CX, CY);
            drawMark(canvas, 1f, 1f);
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawMark(Canvas canvas, float bar, float arc) {
        if (bar > 0) {
            canvas.drawLine(BAR_X1, BAR_Y, BAR_X1 + (BAR_X2 - BAR_X1) * bar, BAR_Y, paint);
        }
        if (arc > 0) {
            // from the top of the ring, counter-clockwise through the left side to the bottom
            canvas.drawArc(arcRect, 270, -180 * arc, false, paint);
        }
    }
}
