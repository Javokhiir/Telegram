package org.telegram.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;

import java.util.Locale;

/**
 * Shown instead of the app on any phone other than 7TECH Connect U7.
 * Mirrors the 7OS Launcher "unsupported device" screen.
 */
public class UMessageUnsupportedDeviceActivity extends Activity {

    private static final String ALLOWED_DEVICE = "connect_u7";
    private static final String ALLOWED_BRAND = "7tech";

    private static Boolean supported;

    public static boolean isSupported() {
        if (supported == null) {
            boolean device = ALLOWED_DEVICE.equals(normalize(Build.DEVICE))
                    || ALLOWED_DEVICE.equals(normalize(Build.MODEL))
                    || ALLOWED_DEVICE.equals(normalize(Build.PRODUCT));
            boolean brand = normalize(Build.BRAND).contains(ALLOWED_BRAND)
                    || normalize(Build.MANUFACTURER).contains(ALLOWED_BRAND)
                    || normalize(Build.PRODUCT).contains(ALLOWED_BRAND);
            supported = device && brand;
        }
        return supported;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US).replaceAll("[\\s-]+", "_");
    }

    private int dp(float value) {
        return (int) Math.ceil(getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int background = 0xFF08080C;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);

        final String title, body, exit;
        switch (Locale.getDefault().getLanguage()) {
            case "uz":
                title = "Bu qurilma qo‘llab-quvvatlanmaydi";
                body = "U message faqat 7TECH Connect U7 uchun mo‘ljallangan.";
                exit = "Ilovadan chiqish";
                break;
            case "kaa":
                title = "Bul qurılma qollap-quwatlanbaydı";
                body = "U message tek 7TECH Connect U7 ushın arnalǵan.";
                exit = "Baǵdarlamadan shıǵıw";
                break;
            case "ru":
                title = "Это устройство не поддерживается";
                body = "U message предназначен только для 7TECH Connect U7.";
                exit = "Закрыть приложение";
                break;
            default:
                title = "This device is not supported";
                body = "U message is built for 7TECH Connect U7 only.";
                exit = "Close the app";
                break;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(background);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.umessage_seven_logo);
        logo.setColorFilter(Color.WHITE);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        int logoWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f) - dp(64);
        root.addView(logo, new FrameLayout.LayoutParams(logoWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setGravity(Gravity.CENTER);
        column.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView bodyView = new TextView(this);
        bodyView.setText(body);
        bodyView.setTextColor(0xFFB9BAC4);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bodyView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(12);
        column.addView(bodyView, bodyParams);

        TextView button = new TextView(this);
        button.setText(exit);
        button.setTextColor(background);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.WHITE);
        buttonBackground.setCornerRadius(dp(16));
        button.setBackground(buttonBackground);
        button.setOnClickListener(v -> finishAndRemoveTask());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(40);
        column.addView(button, buttonParams);

        int columnWidth = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(420));
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(columnWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        columnParams.bottomMargin = dp(96);
        root.addView(column, columnParams);

        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(root);
    }
}
