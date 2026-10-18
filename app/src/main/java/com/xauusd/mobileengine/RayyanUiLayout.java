package com.xauusd.mobileengine;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI-only layout for the Rayyan header, Price/Spread, and timeframe strip.
 *
 * Keep GatewayService, Mt5Gateway, order queueing, and all network methods out
 * of this class. This class builds views only; it does not send requests.
 */
public final class RayyanUiLayout {
    private static final int HEADER_BG = Color.rgb(2, 15, 29);
    private static final int STRIP_BG = Color.rgb(6, 27, 45);
    private static final int TF_BG = Color.rgb(2, 14, 26);
    private static final int BUTTON_BG = Color.rgb(7, 34, 60);
    private static final int STROKE = Color.rgb(22, 104, 157);
    private static final int CYAN = Color.rgb(0, 210, 220);
    private static final int GREEN = Color.rgb(0, 225, 135);
    private static final int BLUE_ACTIVE = Color.rgb(15, 150, 235);
    private static final int LIVE_BG = Color.rgb(7, 66, 49);

    private RayyanUiLayout() {}

    public interface Listener {
        void onMenu(View anchor);
        void onTimeframe(String timeframe);
        void onLive();
    }

    public static final class Header {
        public final LinearLayout container;
        public final TextView marketTitle;
        public final TextView marketSubtitle;
        public final TextView priceView;
        public final TextView spreadView;
        public final Map<String, Button> timeframeButtons;

        private Header(LinearLayout container, TextView marketTitle,
                       TextView marketSubtitle, TextView priceView,
                       TextView spreadView, Map<String, Button> timeframeButtons) {
            this.container = container;
            this.marketTitle = marketTitle;
            this.marketSubtitle = marketSubtitle;
            this.priceView = priceView;
            this.spreadView = spreadView;
            this.timeframeButtons = timeframeButtons;
        }
    }

    public static Header build(MainActivity host, String market, Listener listener) {
        LinearLayout container = new LinearLayout(host);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        // Top header: leave enough space for the app icon, market name, price, and menu.
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(host, 6), dp(host, 2), dp(host, 6), dp(host, 2));
        head.setBackgroundColor(HEADER_BG);
        container.addView(head, new LinearLayout.LayoutParams(-1, dp(host, 60)));

        ImageView appIcon = new ImageView(host);
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appIcon.setAdjustViewBounds(true);
        try {
            Drawable icon = host.getPackageManager().getApplicationIcon(host.getPackageName());
            appIcon.setImageDrawable(icon);
        } catch (Exception ignored) {
            appIcon.setImageDrawable(null);
        }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(host, 34), dp(host, 38));
        iconParams.setMargins(0, 0, dp(host, 4), 0);
        head.addView(appIcon, iconParams);

        LinearLayout title = new LinearLayout(host);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinimumWidth(dp(host, 90));

        TextView marketTitle = text(host, market, 17, Color.WHITE);
        marketTitle.setTypeface(null, Typeface.BOLD);
        marketTitle.setSingleLine(true);
        marketTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        marketTitle.setIncludeFontPadding(false);
        marketTitle.setPadding(0, 0, 0, 0);
        title.addView(marketTitle, new LinearLayout.LayoutParams(-1, dp(host, 23)));

        TextView marketSubtitle = text(host, host.marketDescription(market), 9, Color.LTGRAY);
        marketSubtitle.setSingleLine(true);
        marketSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        marketSubtitle.setIncludeFontPadding(false);
        marketSubtitle.setPadding(0, 0, 0, 0);
        title.addView(marketSubtitle, new LinearLayout.LayoutParams(-1, dp(host, 15)));

        TextView liveDot = text(host, "● LIVE", 8, GREEN);
        liveDot.setSingleLine(true);
        liveDot.setIncludeFontPadding(false);
        liveDot.setPadding(0, 0, 0, 0);
        title.addView(liveDot, new LinearLayout.LayoutParams(-1, dp(host, 12)));

        head.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout priceBox = new LinearLayout(host);
        priceBox.setOrientation(LinearLayout.VERTICAL);
        priceBox.setGravity(Gravity.CENTER);
        priceBox.setPadding(dp(host, 3), dp(host, 1), dp(host, 3), dp(host, 1));
        LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(dp(host, 124), dp(host, 56));
        priceParams.setMargins(dp(host, 3), 0, dp(host, 4), 0);

        TextView priceLabel = text(host, "PRICE", 9, CYAN);
        priceLabel.setGravity(Gravity.CENTER);
        priceLabel.setIncludeFontPadding(false);
        priceBox.addView(priceLabel, new LinearLayout.LayoutParams(-1, dp(host, 16)));

        TextView priceView = text(host, "--", 22, Color.CYAN);
        priceView.setTypeface(null, Typeface.BOLD);
        priceView.setGravity(Gravity.CENTER);
        priceView.setSingleLine(true);
        priceView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        priceView.setIncludeFontPadding(false);
        priceView.setPadding(0, 0, 0, 0);
        priceView.setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        priceBox.addView(priceView, new LinearLayout.LayoutParams(-1, dp(host, 36)));
        head.addView(priceBox, priceParams);

        Button menu = button(host, "☰", 17);
        menu.setContentDescription("Menu utama");
        menu.setBackground(roundBackground(host, BUTTON_BG, 10));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(host, 42), dp(host, 42));
        menuParams.setMargins(dp(host, 2), 0, 0, 0);
        head.addView(menu, menuParams);
        menu.setOnClickListener(v -> {
            if (listener != null) listener.onMenu(menu);
        });

        // Keep Spread readable but secondary so it cannot crowd the market header.
        LinearLayout spreadRow = new LinearLayout(host);
        spreadRow.setGravity(Gravity.CENTER);
        spreadRow.setBackgroundColor(STRIP_BG);
        container.addView(spreadRow, new LinearLayout.LayoutParams(-1, dp(host, 22)));

        TextView spreadView = text(host, "SPREAD  --", 9, Color.WHITE);
        spreadView.setGravity(Gravity.CENTER);
        spreadView.setSingleLine(true);
        spreadView.setIncludeFontPadding(false);
        spreadView.setPadding(dp(host, 2), 0, dp(host, 2), 0);
        spreadRow.addView(spreadView, new LinearLayout.LayoutParams(-1, -1));

        // Seven equally weighted touch targets: M1, M5, M15, H1, H4, D1, LIVE.
        LinearLayout tfRow = new LinearLayout(host);
        tfRow.setOrientation(LinearLayout.HORIZONTAL);
        tfRow.setGravity(Gravity.CENTER_VERTICAL);
        tfRow.setPadding(dp(host, 4), dp(host, 2), dp(host, 4), dp(host, 2));
        tfRow.setBackgroundColor(TF_BG);
        container.addView(tfRow, new LinearLayout.LayoutParams(-1, dp(host, 42)));

        Map<String, Button> tfButtons = new LinkedHashMap<>();
        String[] tfs = {"M1", "M5", "M15", "H1", "H4", "D1"};
        for (String tf : tfs) {
            Button b = button(host, tf, 11);
            tfButtons.put(tf, b);
            addWeightedButton(host, tfRow, b);
            b.setOnClickListener(v -> {
                if (listener != null) listener.onTimeframe(tf);
            });
        }

        Button live = button(host, "● LIVE", 10);
        live.setTextColor(GREEN);
        live.setBackground(roundBackground(host, LIVE_BG, 10));
        addWeightedButton(host, tfRow, live);
        live.setOnClickListener(v -> {
            if (listener != null) listener.onLive();
        });

        return new Header(container, marketTitle, marketSubtitle, priceView, spreadView, tfButtons);
    }

    private static void addWeightedButton(MainActivity host, LinearLayout row, Button button) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(host, 36), 1f);
        p.setMargins(dp(host, 1), 0, dp(host, 1), 0);
        row.addView(button, p);
    }

    private static TextView text(MainActivity host, String value, float size, int color) {
        TextView t = new TextView(host);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static Button button(MainActivity host, String value, float size) {
        Button b = new Button(host);
        b.setText(value);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(host, 1), 0, dp(host, 1), 0);
        b.setBackground(roundBackground(host, BUTTON_BG, 9));
        return b;
    }

    private static GradientDrawable roundBackground(MainActivity host, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(host, radiusDp));
        g.setStroke(dp(host, 1), STROKE);
        return g;
    }

    private static int dp(MainActivity host, float value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }
}
