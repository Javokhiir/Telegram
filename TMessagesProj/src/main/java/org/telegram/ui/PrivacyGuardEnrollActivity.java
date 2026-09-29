package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.privacyguard.PrivacyGuardEnrollment;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/** Settings -> Privacy Guard -> Register My Face. */
public class PrivacyGuardEnrollActivity extends BaseFragment implements PrivacyGuardEnrollment.Callback {

    private final boolean enableAfter;

    private TextureView textureView;
    private ProgressRingView ringView;
    private TextView titleView;
    private TextView hintView;
    private TextView buttonView;
    private PrivacyGuardEnrollment enrollment;
    private boolean resumed;
    private boolean done;
    private int size;

    public PrivacyGuardEnrollActivity(boolean enableAfter) {
        this.enableAfter = enableAfter;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PrivacyGuardEnrollTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        size = Math.min(AndroidUtilities.displaySize.x - dp(112), dp(260));
        FrameLayout previewFrame = new FrameLayout(context);
        content.addView(previewFrame, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 40, 0, 0));

        FrameLayout clip = new FrameLayout(context);
        clip.setBackgroundColor(0xff000000);
        clip.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        clip.setClipToOutline(true);
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                startIfReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                stopEnrollment();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
        clip.addView(textureView, new FrameLayout.LayoutParams(size, size));
        final int ringPadding = dp(14);
        previewFrame.addView(clip, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        ringView = new ProgressRingView(context);
        previewFrame.addView(ringView, new FrameLayout.LayoutParams(size + ringPadding * 2, size + ringPadding * 2, Gravity.CENTER));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 32, 0, 0));

        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hintView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hintView.setGravity(Gravity.CENTER);
        hintView.setMinLines(2);
        content.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 8, 32, 0));

        buttonView = new TextView(context);
        buttonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        buttonView.setTypeface(AndroidUtilities.bold());
        buttonView.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        buttonView.setGravity(Gravity.CENTER);
        buttonView.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 8));
        buttonView.setVisibility(View.INVISIBLE);
        content.addView(buttonView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 32, 20, 32, 0));

        View spacer = new View(context);
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        TextView privacyView = new TextView(context);
        privacyView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollPrivacy));
        privacyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        privacyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        privacyView.setGravity(Gravity.CENTER);
        content.addView(privacyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 0, 32, 24));

        onProgress(PrivacyGuardEnrollment.STEP_STRAIGHT, 0, PrivacyGuardEnrollment.HINT_NONE);
        fragmentView = content;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (getParentActivity() != null) {
            AndroidUtilities.lockOrientation(getParentActivity(), ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        startIfReady();
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        stopEnrollment();
        if (getParentActivity() != null) {
            AndroidUtilities.unlockOrientation(getParentActivity());
        }
    }

    @Override
    public void onFragmentDestroy() {
        stopEnrollment();
        super.onFragmentDestroy();
    }

    private void startIfReady() {
        if (!resumed || done || enrollment != null || textureView == null || !textureView.isAvailable()) {
            return;
        }
        ringView.reset();
        buttonView.setVisibility(View.INVISIBLE);
        onProgress(PrivacyGuardEnrollment.STEP_STRAIGHT, 0, PrivacyGuardEnrollment.HINT_NONE);
        enrollment = new PrivacyGuardEnrollment(getParentActivity(), this);
        enrollment.start(textureView.getSurfaceTexture());
    }

    private void stopEnrollment() {
        if (enrollment != null) {
            enrollment.stop();
            enrollment = null;
        }
    }

    @Override
    public void onCameraStarted(int width, int height) {
        // portrait: the upright buffer is the short side wide; crop it to fill the circle
        final float contentAspect = Math.min(width, height) / (float) Math.max(width, height);
        Matrix matrix = new Matrix();
        matrix.setScale(1f, 1f / contentAspect, size / 2f, size / 2f);
        textureView.setTransform(matrix);
    }

    @Override
    public void onProgress(int step, float stepProgress, int hint) {
        if (done) {
            return;
        }
        final int previous = ringView.step;
        ringView.setProgress(step, stepProgress);
        if (step != previous && step > 0 && fragmentView != null) {
            try {
                fragmentView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
        }
        titleView.setText(LocaleController.getString(stepTitle(step)));
        final int hintRes = hintText(hint);
        hintView.setText(hintRes != 0 ? LocaleController.getString(hintRes) : "");
    }

    @Override
    public void onFinished(boolean success, int error) {
        stopEnrollment();
        done = success;
        if (success) {
            ringView.setProgress(PrivacyGuardEnrollment.STEP_COUNT, 1f);
            if (enableAfter) {
                PrivacyGuardSettings.setEnabled(true);
            }
            titleView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollDone));
            hintView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollDoneInfo));
            buttonView.setText(LocaleController.getString(R.string.Done));
            buttonView.setOnClickListener(v -> finishFragment());
        } else {
            titleView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollFailed));
            final int message;
            switch (error) {
                case PrivacyGuardEnrollment.ERROR_INCONSISTENT: message = R.string.PrivacyGuardEnrollFailedInconsistent; break;
                case PrivacyGuardEnrollment.ERROR_CAMERA: message = R.string.PrivacyGuardEnrollFailedCamera; break;
                default: message = R.string.PrivacyGuardEnrollFailedGeneric; break;
            }
            hintView.setText(LocaleController.getString(message));
            buttonView.setText(LocaleController.getString(R.string.PrivacyGuardTryAgain));
            buttonView.setOnClickListener(v -> startIfReady());
        }
        buttonView.setVisibility(View.VISIBLE);
    }

    private static int stepTitle(int step) {
        switch (step) {
            case PrivacyGuardEnrollment.STEP_SIDE: return R.string.PrivacyGuardEnrollSide;
            case PrivacyGuardEnrollment.STEP_OTHER_SIDE: return R.string.PrivacyGuardEnrollOtherSide;
            case PrivacyGuardEnrollment.STEP_UP: return R.string.PrivacyGuardEnrollUp;
            case PrivacyGuardEnrollment.STEP_DOWN: return R.string.PrivacyGuardEnrollDown;
            default: return R.string.PrivacyGuardEnrollStraight;
        }
    }

    private static int hintText(int hint) {
        switch (hint) {
            case PrivacyGuardEnrollment.HINT_NO_FACE: return R.string.PrivacyGuardEnrollHintNoFace;
            case PrivacyGuardEnrollment.HINT_MULTIPLE_FACES: return R.string.PrivacyGuardEnrollHintMultiple;
            case PrivacyGuardEnrollment.HINT_MOVE_CLOSER: return R.string.PrivacyGuardEnrollHintCloser;
            case PrivacyGuardEnrollment.HINT_TOO_DARK: return R.string.PrivacyGuardEnrollHintDark;
            case PrivacyGuardEnrollment.HINT_OPEN_EYES: return R.string.PrivacyGuardEnrollHintEyes;
            case PrivacyGuardEnrollment.HINT_TURN_MORE: return R.string.PrivacyGuardEnrollHintMore;
            case PrivacyGuardEnrollment.HINT_TURN_LESS: return R.string.PrivacyGuardEnrollHintLess;
            default: return 0;
        }
    }

    /** Five arcs around the preview, one per head orientation. */
    private class ProgressRingView extends View {

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        int step;
        private float stepProgress;
        private float animatedTotal;
        private ValueAnimator animator;

        ProgressRingView(Context context) {
            super(context);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            trackPaint.setStrokeWidth(dp(4));
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(dp(4));
        }

        void reset() {
            if (animator != null) {
                animator.cancel();
            }
            step = 0;
            stepProgress = 0;
            animatedTotal = 0;
            invalidate();
        }

        void setProgress(int step, float progress) {
            this.step = step;
            this.stepProgress = progress;
            final float target = Math.min(PrivacyGuardEnrollment.STEP_COUNT, step + progress);
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(animatedTotal, target);
            animator.addUpdateListener(a -> {
                animatedTotal = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(220);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            trackPaint.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText), 0.25f));
            progressPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            final float inset = dp(3);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            final int count = PrivacyGuardEnrollment.STEP_COUNT;
            final float gap = 10f;
            final float sweep = 360f / count - gap;
            for (int i = 0; i < count; i++) {
                final float start = -90f + i * 360f / count + gap / 2f;
                canvas.drawArc(rect, start, sweep, false, trackPaint);
                final float filled = Math.max(0f, Math.min(1f, animatedTotal - i));
                if (filled > 0) {
                    canvas.drawArc(rect, start, sweep * filled, false, progressPaint);
                }
            }
        }
    }
}
