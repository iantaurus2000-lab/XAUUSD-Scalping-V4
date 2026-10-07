package com.xauusd.mobileengine;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.*;

public class MainActivity extends Activity {
    private CandleChartView chart;
    private TextView price, status, signal;
    private ScheduledExecutorService ex;
    private String selectedTf = "M1";
    private final Map<String, Button> tfButtons = new HashMap<>();

    private final int bg = Color.rgb(8, 12, 16);
    private final int panel = Color.rgb(17, 22, 29);
    private final int selectedBg = Color.rgb(25, 120, 90);
    private final int unselectedBg = Color.rgb(27, 34, 43);

    TextView tv(String s, float z) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(Color.WHITE);
        v.setTextSize(z);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(12, 0, 12, 0);
        return v;
    }

    Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(48);
        b.setMinWidth(0);
        b.setPadding(8, 0, 8, 0);
        b.setBackground(roundBg(unselectedBg, 12));
        return b;
    }

    GradientDrawable roundBg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(1, Color.rgb(55, 66, 78));
        return g;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUI();
    }

    void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(10, 4, 8, 4);
        head.setBackgroundColor(panel);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv("XAUUSD", 17);
        t.setTypeface(null, 1);
        names.addView(t, new LinearLayout.LayoutParams(-1, 25));
        names.addView(tv("MOBILE TRADING ENGINE", 10), new LinearLayout.LayoutParams(-1, 22));
        head.addView(names, new LinearLayout.LayoutParams(0, 58, 1));

        price = tv("BID --  ASK --", 11);
        head.addView(price, new LinearLayout.LayoutParams(156, 58));

        Button menu = btn("☰  MENU");
        menu.setTextSize(14);
        menu.setBackground(roundBg(Color.rgb(32, 42, 52), 14));
        menu.setOnClickListener(v -> showMenu(menu));
        head.addView(menu, new LinearLayout.LayoutParams(118, 58));
        root.addView(head);

        LinearLayout tf = new LinearLayout(this);
        tf.setPadding(6, 5, 6, 5);
        tf.setBackgroundColor(Color.rgb(13, 18, 24));

        addTfButton(tf, "M1");
        addTfButton(tf, "M5");
        addTfButton(tf, "M15");
        addTfButton(tf, "H1");

        Button live = btn("● LIVE");
        live.setTextColor(Color.rgb(0, 230, 145));
        live.setBackground(roundBg(Color.rgb(18, 43, 37), 12));
        tf.addView(live, new LinearLayout.LayoutParams(0, 48, 1));
        live.setOnClickListener(v -> {
            if (chart != null) chart.goLive();
            selectTimeframe(selectedTf, false);
        });
        root.addView(tf);

        chart = new CandleChartView(this);
        chart.setTimeframe(selectedTf);
        root.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(6, 4, 6, 4);
        actions.setBackgroundColor(panel);

        Button follow = btn("FOLLOW");
        follow.setOnClickListener(v -> chart.goLive());
        actions.addView(follow, new LinearLayout.LayoutParams(0, 52, 1));

        Button cross = btn("CROSSHAIR");
        cross.setOnClickListener(v ->
            Toast.makeText(this, "Crosshair: sentuh dan geser chart", Toast.LENGTH_SHORT).show()
        );
        actions.addView(cross, new LinearLayout.LayoutParams(0, 52, 1));

        Button signalBtn = btn("SIGNAL");
        signalBtn.setOnClickListener(v -> showSignal());
        actions.addView(signalBtn, new LinearLayout.LayoutParams(0, 52, 1));
        root.addView(actions);

        LinearLayout info = new LinearLayout(this);
        info.setBackgroundColor(Color.rgb(11, 16, 21));
        status = tv("● CONNECTING  •  BIQUOTE  •  M1", 10);
        signal = tv("SCANNER • EMA9/21/50 • RSI • MACD • ATR", 10);
        info.addView(status, new LinearLayout.LayoutParams(0, 40, 1));
        info.addView(signal, new LinearLayout.LayoutParams(0, 34, 1));
        root.addView(info);

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(4, 2, 4, 2);
        nav.setBackgroundColor(Color.rgb(15, 20, 26));
        for (String s : new String[]{"MARKET", "CHART", "SIGNAL", "ORDERS", "SETTINGS"}) {
            Button q = btn(s);
            q.setMinWidth(112);
            nav.addView(q, new LinearLayout.LayoutParams(112, 54));
        }
        navScroll.addView(nav);
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, 58));

        setContentView(root);
        selectTimeframe("M1", false);
        load("1m", "M1");
    }

    void addTfButton(LinearLayout parent, String tf) {
        Button q = btn(tf);
        q.setTextSize(14);
        tfButtons.put(tf, q);
        parent.addView(q, new LinearLayout.LayoutParams(0, 48, 1));
        q.setOnClickListener(v -> selectTimeframe(tf, true));
    }

    void selectTimeframe(String tf, boolean reload) {
        selectedTf = tf;
        for (Map.Entry<String, Button> e : tfButtons.entrySet()) {
            boolean active = e.getKey().equals(tf);
            e.getValue().setTextColor(active ? Color.BLACK : Color.WHITE);
            e.getValue().setBackground(roundBg(active ? Color.rgb(0, 220, 145) : unselectedBg, 12));
        }
        if (chart != null) chart.setTimeframe(tf);
        status.setText("● " + ("M1".equals(tf) ? "LIVE" : "LOADING") + "  •  BIQUOTE  •  " + tf);
        if (reload) {
            String interval = "M5".equals(tf) ? "5m" : ("M15".equals(tf) ? "15m" : ("H1".equals(tf) ? "1h" : "1m"));
            load(interval, tf);
        }
    }

    void load(String interval, String tf) {
        new Thread(() -> {
            try {
                Candle[] c = BiquoteClient.candles(interval);
                runOnUiThread(() -> {
                    if (!tf.equals(selectedTf)) return;
                    chart.setData(c);
                    chart.goLive();
                    status.setText("● LIVE  •  BIQUOTE  •  " + tf);
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                    status.setText("● OFFLINE  •  " + tf + "  •  RETRYING")
                );
            }
        }).start();

        if (ex == null) {
            ex = Executors.newSingleThreadScheduledExecutor();
            ex.scheduleAtFixedRate(() -> {
                try {
                    double[] t = BiquoteClient.tick();
                    runOnUiThread(() -> {
                        chart.setTick(t[0], t[1], selectedTf);
                        price.setText(String.format(Locale.US, "BID %.2f  ASK %.2f", t[0], t[1]));
                        signal.setText("SCANNER READY • " + selectedTf + " • EMA9/21/50 • RSI • MACD • ATR");
                        status.setText("● LIVE  •  BIQUOTE  •  " + selectedTf);
                    });
                } catch (Exception e) {
                    runOnUiThread(() ->
                        status.setText("● OFFLINE • retrying • " + selectedTf)
                    );
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    void showMenu(View anchor) {
        final String[] items = {"Market", "Chart", "Signal", "Orders", "Strategy",
            "Risk Management", "Telegram", "MT5 / EA", "Settings"};
        PopupWindow pop = new PopupWindow(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8, 8, 8, 8);
        box.setBackgroundColor(Color.rgb(25, 31, 39));

        for (String s : items) {
            Button b = btn(s);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setTextSize(14);
            b.setMinWidth(260);
            box.addView(b, new LinearLayout.LayoutParams(260, 54));
            b.setOnClickListener(v -> {
                pop.dismiss();
                Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
            });
        }

        pop.setContentView(box);
        pop.setWidth(268);
        pop.setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pop.setOutsideTouchable(true);
        pop.setFocusable(true);
        pop.setElevation(16);
        pop.showAsDropDown(anchor, -154, 4);
    }

    void showSignal() {
        new AlertDialog.Builder(this)
            .setTitle("Signal Engine")
            .setMessage(
                "Wick Rejection / Jarum\n" +
                "Liquidity Sweep\nBOS\nM5 Bias → M1 Entry\n" +
                "EMA 9/21/50 • RSI • MACD • ATR\n\n" +
                "Confidence: menunggu data"
            )
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        if (ex != null) ex.shutdownNow();
        super.onDestroy();
    }
}
