package com.xauusd.mobileengine;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Reversible, UI-only theme styling. Original styles are cached so switching
 * themes never compounds color changes and DEFAULT restores the original UI.
 * Does not touch order, gateway, networking, or trading state.
 */
public final class RayyanTheme {
    public static final String DEFAULT = "DEFAULT";
    public static final String DARK = "DARK";
    public static final String LIGHT = "LIGHT";

    private static final int NAVY_TEXT = Color.rgb(23, 44, 66);
    private static final int MUTED_TEXT = Color.rgb(91, 111, 132);
    private static final int DARK_TEXT = Color.rgb(235, 243, 252);
    private static final int DARK_MUTED = Color.rgb(177, 195, 215);
    private static final int LIGHT_BLUE = Color.rgb(0, 105, 190);
    private static final WeakHashMap<View, OriginalStyle> ORIGINALS = new WeakHashMap<>();

    private RayyanTheme() {}

    private static final class OriginalStyle {
        final Drawable.ConstantState backgroundState;
        final Drawable backgroundFallback;
        final ColorStateList textColors;
        final ColorStateList hintColors;
        final boolean textView;

        OriginalStyle(View view) {
            Drawable background = view.getBackground();
            backgroundState = background == null ? null : background.getConstantState();
            backgroundFallback = backgroundState == null ? background : null;
            textView = view instanceof TextView;
            if (textView) {
                TextView text = (TextView) view;
                textColors = text.getTextColors();
                hintColors = text.getHintTextColors();
            } else {
                textColors = null;
                hintColors = null;
            }
        }

        Drawable freshBackground(View view) {
            if (backgroundState != null) {
                try { return backgroundState.newDrawable(view.getResources()).mutate(); }
                catch (Throwable ignored) {}
            }
            return backgroundFallback;
        }
    }

    public static String normalize(String mode) {
        if (DARK.equalsIgnoreCase(mode)) return DARK;
        if (LIGHT.equalsIgnoreCase(mode)) return LIGHT;
        return DEFAULT;
    }

    public static void apply(Activity host, View root, String selectedMode) {
        if (root == null) return;
        applyNode(root, normalize(selectedMode));
    }

    private static void applyNode(View view, String mode) {
        if (view instanceof CandleChartView) {
            ((CandleChartView) view).setVisualTheme(mode);
            return;
        }

        OriginalStyle original = ORIGINALS.get(view);
        if (original == null) {
            original = new OriginalStyle(view);
            ORIGINALS.put(view, original);
        }
        view.setBackground(original.freshBackground(view));
        int originalBg = backgroundColor(view.getBackground());

        if (original.textView) {
            TextView text = (TextView) view;
            text.setTextColor(original.textColors);
            text.setHintTextColor(original.hintColors);
        }

        if (!DEFAULT.equals(mode)) {
            Drawable background = view.getBackground();
            recolorBackground(background, mode);
            if (original.textView) {
                TextView text = (TextView) view;
                boolean actionBackground = isActionAccent(originalBg);
                text.setTextColor(mapTextColor(text.getCurrentTextColor(), mode, actionBackground));
                if (view instanceof android.widget.EditText) {
                    text.setHintTextColor(LIGHT.equals(mode) ? MUTED_TEXT : DARK_MUTED);
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyNode(group.getChildAt(i), mode);
            }
        }
    }

    private static void recolorBackground(Drawable background, String mode) {
        if (background instanceof ColorDrawable) {
            ColorDrawable solid = (ColorDrawable) background;
            int original = solid.getColor();
            if (Color.alpha(original) > 0) solid.setColor(mapSurface(original, mode));
            return;
        }
        if (background instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) background;
            if (Build.VERSION.SDK_INT >= 24) {
                ColorStateList fill = shape.getColor();
                if (fill != null) shape.setColor(mapSurface(fill.getDefaultColor(), mode));
                int[] strokeWidth = new int[]{0};
                ColorStateList[] strokeColors = new ColorStateList[]{null};
                shape.getStroke(strokeWidth, strokeColors);
                if (strokeWidth[0] > 0 && strokeColors[0] != null) {
                    shape.setStroke(strokeWidth[0], mapStroke(strokeColors[0].getDefaultColor(), mode));
                }
            }
        }
    }

    private static int backgroundColor(Drawable background) {
        if (background instanceof ColorDrawable) return ((ColorDrawable) background).getColor();
        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            ColorStateList fill = ((GradientDrawable) background).getColor();
            if (fill != null) return fill.getDefaultColor();
        }
        return Color.TRANSPARENT;
    }

    private static int mapSurface(int color, String mode) {
        if (Color.alpha(color) < 80 || isActionAccent(color)) return color;
        int lum = luminance(color);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (LIGHT.equals(mode)) {
            if (lum < 48) {
                if (hsv[1] < 0.18f) return Color.rgb(248, 250, 253);
                if (hsv[0] >= 90 && hsv[0] < 170) return Color.rgb(231, 247, 239);
                if (hsv[0] >= 170 && hsv[0] < 260) return Color.rgb(234, 242, 252);
                if (hsv[0] >= 260 && hsv[0] < 330) return Color.rgb(243, 238, 252);
                return Color.rgb(247, 250, 253);
            }
            if (lum < 100) {
                if (hsv[1] < 0.18f) return Color.rgb(237, 242, 248);
                if (hsv[0] >= 90 && hsv[0] < 170) return Color.rgb(235, 247, 241);
                if (hsv[0] >= 170 && hsv[0] < 260) return Color.rgb(235, 243, 253);
                return Color.rgb(241, 245, 250);
            }
            if (lum < 150 && hsv[1] < 0.24f) return Color.rgb(225, 233, 242);
            if (lum < 165 && hsv[1] < 0.42f) return Color.rgb(232, 239, 247);
            return color;
        }

        if (lum < 42) {
            if (hsv[1] < 0.18f) return Color.rgb(10, 15, 24);
            if (hsv[0] >= 170 && hsv[0] < 260) return Color.rgb(15, 29, 48);
            if (hsv[0] >= 90 && hsv[0] < 170) return Color.rgb(12, 31, 29);
            return Color.rgb(13, 19, 30);
        }
        if (lum < 95) {
            if (hsv[1] < 0.18f) return Color.rgb(17, 25, 38);
            if (hsv[0] >= 170 && hsv[0] < 260) return Color.rgb(20, 39, 63);
            if (hsv[0] >= 90 && hsv[0] < 170) return Color.rgb(16, 43, 39);
            if (hsv[0] >= 260 && hsv[0] < 330) return Color.rgb(37, 27, 49);
            return Color.rgb(20, 29, 43);
        }
        if (lum < 155 && hsv[1] < 0.20f) return Color.rgb(27, 38, 55);
        if (lum < 125 && hsv[0] >= 170 && hsv[0] < 260) return Color.rgb(24, 42, 67);
        return color;
    }

    private static int mapStroke(int color, String mode) {
        if (LIGHT.equals(mode)) {
            if (luminance(color) < 100) return Color.rgb(188, 207, 229);
            return Color.rgb(155, 183, 214);
        }
        if (luminance(color) < 100) return Color.rgb(48, 78, 119);
        return Color.rgb(54, 103, 159);
    }

    private static int mapTextColor(int color, String mode, boolean actionBackground) {
        if (actionBackground) return color;
        int lum = luminance(color);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (LIGHT.equals(mode)) {
            if (lum > 218 && hsv[1] < 0.20f) return NAVY_TEXT;
            if (lum > 150 && lum <= 218 && hsv[1] < 0.24f) return MUTED_TEXT;
            if (hsv[0] >= 175 && hsv[0] < 205 && hsv[1] > 0.25f) return LIGHT_BLUE;
            return color;
        }
        if (lum < 50 && hsv[1] < 0.28f) return DARK_TEXT;
        if (lum >= 60 && lum < 155 && hsv[1] < 0.22f) return DARK_MUTED;
        if (hsv[0] >= 175 && hsv[0] < 205 && hsv[1] > 0.25f) return Color.rgb(91, 190, 255);
        return color;
    }

    private static boolean isActionAccent(int color) {
        if (Color.alpha(color) < 80) return false;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max >= 160 && max - min >= 55) return true;
        return g >= 112 && g > r * 1.30f && g > b * 1.05f;
    }

    private static int luminance(int color) {
        return (int) ((Color.red(color) * 0.2126f) + (Color.green(color) * 0.7152f) + (Color.blue(color) * 0.0722f));
    }
}
