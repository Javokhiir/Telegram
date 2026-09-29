package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RoundEffectsCarousel;
import org.telegram.ui.Components.RoundEffectsPreviewView;
import org.telegram.ui.Components.RoundVideoEffects;
import org.telegram.ui.Components.SeekBarView;


/**
 * U message: color filters and Beauty (skin smoothing with a light blush) with one overall strength control and a
 * live round preview.
 */
public class UMessageRoundEffectsActivity extends BaseFragment {

    private static final int REQUEST_CAMERA = 3771;

    private FrameLayout previewContainer;
    private RoundEffectsPreviewView previewView;
    private TextView errorView;
    private RoundEffectsCarousel carousel;
    private TextView presetNameView;
    private TextView intensityValueView;
    private boolean permissionRequested;

    private int preset = RoundVideoEffects.getConfiguredPreset();
    private int intensity = RoundVideoEffects.getConfiguredIntensity();
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageRoundEffects));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // round live preview
        final int size = Math.min(AndroidUtilities.displaySize.x - dp(96), dp(280));
        previewContainer = new FrameLayout(context);
        previewContainer.setBackgroundColor(0xff000000);
        previewContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        previewContainer.setClipToOutline(true);
        errorView = new TextView(context);
        errorView.setText(LocaleController.getString(R.string.UMessageCameraUnavailable));
        errorView.setTextColor(0xffffffff);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        previewContainer.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 24, 0, 24, 0));
        // dark stage like the camera: round preview on top, round effect thumbnails below
        LinearLayout stage = new LinearLayout(context);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setBackgroundColor(0xff000000);
        stage.setPadding(0, dp(24), 0, dp(16));
        stage.addView(previewContainer, new LinearLayout.LayoutParams(size, size) {{
            gravity = Gravity.CENTER_HORIZONTAL;
        }});
        presetNameView = new TextView(context);
        presetNameView.setTextColor(0xffffffff);
        presetNameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        presetNameView.setTypeface(AndroidUtilities.bold());
        presetNameView.setGravity(Gravity.CENTER);
        stage.addView(presetNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 4));
        carousel = new RoundEffectsCarousel(context, selectedPreset -> {
            preset = selectedPreset;
            RoundVideoEffects.saveConfiguredLook(preset, intensity);
            updateSelection();
        });
        carousel.setSelected(preset);
        stage.addView(carousel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76));
        content.addView(stage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // pickers
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        section.setPadding(0, 0, 0, dp(12));

        intensityValueView = addSlider(context, section, R.string.UMessageRoundEffectIntensity, intensity, (value, stop) -> {
            intensity = value;
            if (stop) {
                RoundVideoEffects.saveConfiguredLook(preset, intensity);
            }
            updateSelection();
        });
        content.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.UMessageRoundEffectsInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateSelection();
        fragmentView = scrollView;
        return fragmentView;
    }

    // Keep the carousel thumbnails live while the user compares looks.
    private final Runnable captureThumbRunnable = new Runnable() {
        @Override
        public void run() {
            if (previewView == null) {
                return;
            }
            if (previewView.isAvailable()) {
                try {
                    android.graphics.Bitmap bitmap = previewView.getBitmap(dp(56), dp(56));
                    if (bitmap != null && bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2) != 0) {
                        carousel.setSource(bitmap);
                    }
                } catch (Exception ignore) {}
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    private interface SliderListener {
        void onChanged(int percent, boolean stop);
    }

    /** Header with the current percent on the right and a 0..100 slider below; returns the percent view. */
    private TextView addSlider(Context context, LinearLayout parent, int titleRes, int value, SliderListener listener) {
        FrameLayout headerLayout = new FrameLayout(context);
        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(titleRes));
        headerLayout.addView(header);
        TextView valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        valueView.setTypeface(AndroidUtilities.bold());
        headerLayout.addView(valueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 21, 15, 21, 0));
        parent.addView(headerLayout);

        SeekBarView seekBar = new SeekBarView(context);
        seekBar.setReportChanges(true);
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                listener.onChanged(Math.round(progress * 100), stop);
            }

            @Override
            public CharSequence getContentDescription() {
                return valueView.getText();
            }
        });
        seekBar.setProgress(value / 100f);
        parent.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 5, 0, 5, 4));
        return valueView;
    }

    private String formatPercent(int percent) {
        return percent == 0 ? LocaleController.getString(R.string.UMessageBeautyOff) : percent + "%";
    }

    private void updateSelection() {
        intensityValueView.setText(formatPercent(intensity));
        presetNameView.setText(RoundEffectsCarousel.getName(preset));
        if (previewView != null) {
            final RoundVideoEffects.Look look = RoundVideoEffects.createLook(preset, intensity);
            previewView.setEffects(look.filter, look.filterIntensity, look.beauty, look.foundation,
                    look.blush, look.eyes, look.eyeTone, look.lipstick);
        }
    }

    private void attachPreview() {
        final Activity activity = getParentActivity();
        if (previewView != null || previewContainer == null || activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true;
                activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            } else {
                errorView.setVisibility(View.VISIBLE);
            }
            return;
        }
        errorView.setVisibility(View.GONE);
        previewView = new RoundEffectsPreviewView(activity);
        final RoundVideoEffects.Look look = RoundVideoEffects.createLook(preset, intensity);
        previewView.setEffects(look.filter, look.filterIntensity, look.beauty, look.foundation,
                look.blush, look.eyes, look.eyeTone, look.lipstick);
        previewView.setOnErrorListener(() -> {
            detachPreview();
            errorView.setVisibility(View.VISIBLE);
        });
        previewContainer.addView(previewView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        AndroidUtilities.cancelRunOnUIThread(captureThumbRunnable);
        AndroidUtilities.runOnUIThread(captureThumbRunnable, 800);
    }

    // removing the view destroys its surface, which closes the camera
    private void detachPreview() {
        AndroidUtilities.cancelRunOnUIThread(captureThumbRunnable);
        if (previewView != null) {
            previewContainer.removeView(previewView);
            previewView = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        attachPreview();
    }

    @Override
    public void onPause() {
        super.onPause();
        detachPreview();
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            attachPreview();
        } else if (errorView != null) {
            errorView.setVisibility(View.VISIBLE);
        }
    }
}
