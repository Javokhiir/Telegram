package org.telegram.ui.ActionBar;

import android.graphics.Color;
import android.util.SparseIntArray;

import androidx.core.graphics.ColorUtils;

/**
 * U message look: the Telegram blue accent is replaced by ink (black on light themes,
 * white/graphite on dark ones). Only blue hues are touched, so semantic colors
 * (red, green, avatar and name colors) stay as they are.
 */
public class UMessageMonochrome {

    private static final int INK_LIGHT = 0xFF111111;
    private static final int INK_DARK = 0xFFEDEDED;
    private static final int FILL_DARK = 0xFF3A3A3A;

    private static final float[] hsl = new float[3];

    public static void apply(int[] colors, boolean dark) {
        for (int key = 0; key < colors.length; key++) {
            if (colors[key] != 0) {
                colors[key] = apply(key, colors[key], dark);
            }
        }
    }

    public static void apply(SparseIntArray colors, boolean dark) {
        for (int i = 0; i < colors.size(); i++) {
            colors.setValueAt(i, apply(colors.keyAt(i), colors.valueAt(i), dark));
        }
    }

    /** Emoji statuses, premium stars and verified badges keep their original (blue) color. */
    private static boolean keepsOwnColor(String name) {
        String n = name.toLowerCase();
        return n.contains("verified") || n.contains("premium") || n.contains("star")
                || n.contains("emoji") && !n.startsWith("chat_emoji"); // the emoji panel itself stays monochrome
    }

    public static int apply(int key, int color, boolean dark) {
        String name = ThemeColors.getStringName(key);
        if (name == null || name.startsWith("avatar_") || name.contains("wallpaper") || keepsOwnColor(name)) {
            return color;
        }
        ColorUtils.colorToHSL(color, hsl);
        float hue = hsl[0], saturation = hsl[1], lightness = hsl[2];
        if (hue < 180 || hue > 250 || saturation < 0.08f) {
            return color;
        }
        int alpha = Color.alpha(color);
        boolean tint = saturation < 0.35f || (dark ? lightness < 0.3f : lightness >= 0.8f);
        if (tint) {
            // backgrounds, selections and bluish greys: keep the lightness, drop the hue
            hsl[1] = 0;
            return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), alpha);
        }
        // the accent itself
        int ink;
        if (!dark) {
            ink = INK_LIGHT;
        } else {
            boolean foreground = name.contains("Text") || name.contains("Icon") || name.contains("Link") || name.contains("Title");
            boolean fill = !foreground && (name.contains("Background") || name.contains("Button") || name.contains("Counter") || name.contains("Bubble"));
            ink = fill ? FILL_DARK : INK_DARK;
        }
        return ColorUtils.setAlphaComponent(ink, alpha);
    }
}
