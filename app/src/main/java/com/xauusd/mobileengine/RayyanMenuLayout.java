package com.xauusd.mobileengine;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/**
 * Touch-friendly main and settings menus.
 * UI only: menu actions are returned to MainActivity; this class has no gateway
 * or order logic.
 */
public final class RayyanMenuLayout {
    public interface ActionHandler {
        void onAction(String action);
    }

    private static final int BG = Color.rgb(3, 12, 23);
    private static final int PANEL = Color.rgb(7, 26, 43);
    private static final int TILE = Color.rgb(10, 36, 56);
    private static final int BORDER = Color.rgb(25, 91, 129);
    private static final int CYAN = Color.rgb(0, 215, 225);
    private static final int GREEN = Color.rgb(0, 225, 135);
    private static final int MUTED = Color.rgb(160, 182, 197);
    private static final int WHITE = Color.WHITE;
    private static AlertDialog activeDialog;

    private RayyanMenuLayout() {}

    /** Dismiss the current custom menu before opening the selected sub-page. */
    public static void dismissActive() {
        AlertDialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private static final class Item {
        final String icon, title, subtitle, action;
        Item(String icon, String title, String subtitle, String action) {
            this.icon = icon; this.title = title; this.subtitle = subtitle; this.action = action;
        }
    }

    public static void showMain(Activity host, String market, String timeframe, ActionHandler actions) {
        LinearLayout root = base(host);
        addHero(host, root, "RAYYAN • MENU UTAMA", market + "   /   TIMEFRAME " + timeframe,
                "Pilih fungsi • tombol dibuat lebih besar");
        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(host, 12), dp(host, 4), dp(host, 12), dp(host, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addSection(host, content, "MARKET & CHART");
        addItems(host, content, Arrays.asList(
            new Item("◈", "Market", "Pilih XAUUSD, BTCUSD, atau ETHUSD", "market"),
            new Item("⌖", "Chart", "Kembali ke chart live", "chart"),
            new Item("▦", "Indicators / Tampilan", "EMA, S/R, Fibonacci, RSI, MACD", "indicators"),
            new Item("↗", "Signal Engine", "Entry, SL, TP, bias dan filter", "signal"),
            new Item("◉", "Confidence Entry", "Ambang minimum 60%, 70%, 80%, atau 90%", "confidence")
        ), actions);

        addSection(host, content, "SMART AUTO ENTRY & KONEKSI");
        addItems(host, content, Arrays.asList(
            new Item("⇄", "Gateway / Queue / ACK", "Status EA, antrean, dan konfirmasi MT5", "gateway"),
            new Item("▤", "Orders / History", "Log dan riwayat aktivitas aplikasi", "orders"),
            new Item("✦", "Smart Auto Entry", "Pengaturan Auto Entry dan LIMIT demo", "mt5")
        ), actions);

        addSection(host, content, "ALAT & PENGATURAN");
        addItems(host, content, Arrays.asList(
            new Item("✦", "Strategy", "Informasi strategi scalping", "strategy"),
            new Item("◒", "Risk Management", "Informasi kontrol risiko", "risk"),
            new Item("✉", "Telegram", "Notifikasi sinyal", "telegram"),
            new Item("⚙", "Settings", "Pengaturan tampilan dan sistem", "settings")
        ), actions);
        addFooter(host, root, "Gateway dan order tetap diatur oleh MainActivity.");
        showDialog(host, root, "rayyan_main_menu");
    }

    public static void showSettings(Activity host, String market, String timeframe, ActionHandler actions) {
        LinearLayout root = base(host);
        addHero(host, root, "RAYYAN • SETTINGS", market + "   /   TIMEFRAME " + timeframe,
                "Pusat pengaturan • pilih salah satu menu");
        ScrollView scroll = new ScrollView(host);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(host, 12), dp(host, 4), dp(host, 12), dp(host, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addSection(host, content, "TAMPILAN & MARKET");
        addItems(host, content, Arrays.asList(
            new Item("▦", "Indicators / Tampilan", "Atur indikator yang terlihat di chart", "settings_indicators"),
            new Item("◈", "Pilih Market", "XAUUSD, BTCUSD, atau ETHUSD", "settings_market")
        ), actions);
        addSection(host, content, "KONEKSI & NOTIFIKASI");
        addItems(host, content, Arrays.asList(
            new Item("⇄", "Gateway / Queue / ACK", "Periksa status EA dan antrean; tidak mengirim order", "settings_gateway"),
            new Item("✦", "Smart Auto Entry", "Pengaturan Auto Entry dan LIMIT demo", "settings_mt5"),
            new Item("✉", "Telegram", "Token, Chat ID, dan notifikasi", "settings_telegram"),
            new Item("▤", "Log Sistem", "Lihat catatan aktivitas aplikasi", "settings_logs")
        ), actions);
        addFooter(host, root, "Perubahan UI ini tidak mengubah konfigurasi koneksi.");
        showDialog(host, root, "rayyan_settings");
    }

    private static LinearLayout base(Activity host) {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(host, 2), dp(host, 2), dp(host, 2), dp(host, 2));
        root.setBackground(background(BG, 18, BORDER));
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        root.setMinimumHeight(dp(host, 520));
        return root;
    }

    private static void addHero(Activity host, LinearLayout root, String title, String subtitle, String note) {
        LinearLayout hero = new LinearLayout(host);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(host, 18), dp(host, 16), dp(host, 18), dp(host, 15));
        hero.setBackground(background(Color.rgb(4, 42, 64), 16, BORDER));
        TextView titleView = text(host, title, 18, CYAN);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setSingleLine(true);
        hero.addView(titleView, new LinearLayout.LayoutParams(-1, dp(host, 28)));
        TextView subtitleView = text(host, subtitle, 12, WHITE);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hero.addView(subtitleView, new LinearLayout.LayoutParams(-1, dp(host, 24)));
        TextView noteView = text(host, note, 11, MUTED);
        hero.addView(noteView, new LinearLayout.LayoutParams(-1, dp(host, 20)));
        root.addView(hero, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void addSection(Activity host, LinearLayout parent, String label) {
        TextView section = text(host, label, 10, CYAN);
        section.setTypeface(null, Typeface.BOLD);
        section.setPadding(dp(host, 5), dp(host, 10), dp(host, 5), dp(host, 4));
        parent.addView(section, new LinearLayout.LayoutParams(-1, dp(host, 32)));
    }

    private static void addItems(Activity host, LinearLayout parent, List<Item> items, ActionHandler actions) {
        for (Item item : items) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(host, 10), dp(host, 5), dp(host, 10), dp(host, 5));
            row.setBackground(background(TILE, 12, BORDER));
            row.setClickable(true);
            row.setFocusable(true);

            TextView icon = text(host, item.icon, 23, GREEN);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(background(Color.rgb(4, 53, 67), 10, Color.rgb(20, 106, 126)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(host, 50), dp(host, 50)));

            LinearLayout labels = new LinearLayout(host);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setPadding(dp(host, 12), 0, dp(host, 6), 0);
            TextView title = text(host, item.title, 15, WHITE);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title, new LinearLayout.LayoutParams(-1, dp(host, 26)));
            TextView subtitle = text(host, item.subtitle, 11, MUTED);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(host, 21)));
            row.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));

            TextView chevron = text(host, "›", 26, CYAN);
            chevron.setGravity(Gravity.CENTER);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(host, 24), -1));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(host, 76));
            rowParams.setMargins(0, dp(host, 4), 0, dp(host, 4));
            parent.addView(row, rowParams);
            row.setOnClickListener(v -> {
                if (actions != null) actions.onAction(item.action);
            });
        }
    }

    private static void addFooter(Activity host, LinearLayout root, String note) {
        TextView footer = text(host, note, 10, MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(host, 8), dp(host, 8), dp(host, 8), dp(host, 8));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(host, 34)));
    }

    private static void showDialog(Activity host, LinearLayout content, String tag) {
        AlertDialog dialog = new AlertDialog.Builder(host).setView(content).create();
        activeDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int screenWidth = host.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = host.getResources().getDisplayMetrics().heightPixels;
                int width = Math.min(screenWidth - dp(host, 24), dp(host, 480));
                int height = Math.min(screenHeight - dp(host, 88), dp(host, 760));
                window.setLayout(Math.max(dp(host, 300), width), Math.max(dp(host, 420), height));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();
        // Re-apply sizing after show; older Android themes may reset layout dimensions.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int sw = host.getResources().getDisplayMetrics().widthPixels;
            int sh = host.getResources().getDisplayMetrics().heightPixels;
            window.setLayout(Math.min(sw - dp(host, 24), dp(host, 480)),
                    Math.min(sh - dp(host, 88), dp(host, 760)));
        }
    }

    private static TextView text(Activity host, String value, float size, int color) {
        TextView text = new TextView(host);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setIncludeFontPadding(false);
        return text;
    }

    private static GradientDrawable background(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp);
        drawable.setStroke(1, strokeColor);
        return drawable;
    }

    private static int dp(Activity host, float value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }
}