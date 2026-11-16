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
        addHero(host, root, "RAYYAN • TRADING", market + "   /   " + timeframe,
                "Menu samping • chart native MT5");
        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(host, 12), dp(host, 4), dp(host, 12), dp(host, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addSection(host, content, "MARKET & CHART");
        addItems(host, content, Arrays.asList(
            new Item("◈", "Market", "Pilih instrumen MT5", "market"),
            new Item("⌖", "Chart Live", "Kembali ke chart native MT5", "chart"),
            new Item("◉", "Harmonic + Fibonacci", "Pola harga dan level swing", "harmonic_fibo")
        ), actions);

        addSection(host, content, "TRADING POWERFULL");
        addItems(host, content, Arrays.asList(
            new Item("⚡", "POWERFULL EA", "Status, ON/OFF, dan parameter EA MT5", "powerfull"),
            new Item("▤", "Orders / History", "Posisi terbuka dan riwayat", "orders"),
            new Item("⇄", "Gateway / MT5 Sync", "Koneksi, antrean, dan ACK", "gateway"),
            new Item("◒", "Risk Management", "Informasi pengelolaan risiko", "risk")
        ), actions);

        addSection(host, content, "PENGATURAN");
        addItems(host, content, Arrays.asList(
            new Item("⚙", "Settings", "Tema dan preferensi aplikasi", "settings"),
            new Item("✉", "Telegram", "Notifikasi Telegram", "telegram"),
            new Item("♧", "Notifikasi", "Notifikasi aplikasi", "notifications")
        ), actions);
        addFooter(host, root, "Indikator lama dihapus dari tampilan. POWERFULL tetap dikendalikan EA MT5.");
        showDialog(host, root, "rayyan_main_drawer");
    }

    public static void showSettings(Activity host, String market, String timeframe, ActionHandler actions) {
        LinearLayout root = base(host);
        addHero(host, root, "RAYYAN • SETTINGS", market + "   /   " + timeframe,
                "Pengaturan aplikasi dan chart");
        ScrollView scroll = new ScrollView(host);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(host, 12), dp(host, 4), dp(host, 12), dp(host, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addSection(host, content, "TAMPILAN");
        addItems(host, content, Arrays.asList(
            new Item("◐", "Tema Aplikasi", "Tema gelap atau terang", "settings_theme"),
            new Item("◈", "Market", "Pilih instrumen MT5", "settings_market"),
            new Item("⌖", "Harmonic + Fibonacci", "Tampilkan pola dan level swing", "harmonic_fibo")
        ), actions);
        addSection(host, content, "KONEKSI & NOTIFIKASI");
        addItems(host, content, Arrays.asList(
            new Item("⚡", "POWERFULL EA", "Kontrol EA MT5", "powerfull"),
            new Item("⇄", "Gateway / MT5 Sync", "Status koneksi dan ACK", "settings_gateway"),
            new Item("✉", "Telegram", "Token bot dan Chat ID", "settings_telegram"),
            new Item("♧", "Notifikasi", "Pengaturan notifikasi", "settings_notifications"),
            new Item("▤", "Log Sistem", "Catatan aktivitas aplikasi", "settings_logs")
        ), actions);
        addFooter(host, root, "Chart memakai OHLC dan Bid/Ask dari MT5.");
        showDialog(host, root, "rayyan_settings_drawer");
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
        RayyanTheme.apply(host, content,
                host.getSharedPreferences("rayyan4_ui", Activity.MODE_PRIVATE).getString("app_theme", RayyanTheme.DEFAULT));
        AlertDialog dialog = new AlertDialog.Builder(host).setView(content).create();
        activeDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int screenWidth = host.getResources().getDisplayMetrics().widthPixels;
                int drawerWidth = Math.min(dp(host, 340), Math.round(screenWidth * 0.86f));
                window.setGravity(Gravity.START | Gravity.TOP);
                window.setLayout(drawerWidth, WindowManager.LayoutParams.MATCH_PARENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = 0.48f;
                params.gravity = Gravity.START | Gravity.TOP;
                window.setAttributes(params);
                window.setWindowAnimations(android.R.style.Animation_Activity);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int sw = host.getResources().getDisplayMetrics().widthPixels;
            window.setGravity(Gravity.START | Gravity.TOP);
            window.setLayout(Math.min(dp(host, 340), Math.round(sw * 0.86f)),
                    WindowManager.LayoutParams.MATCH_PARENT);
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