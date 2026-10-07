package com.xauusd.mobileengine;

import android.app.*;
import android.os.*;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private CandleChartView chart;
    private TextView bidView, askView, spreadView, status, signalSummary, scoreView, entryView, slView, tp1View, tp2View;
    private ScheduledExecutorService ex;
    private SharedPreferences prefs;
    private SharedPreferences tgPrefs;
    private boolean telegramEnabled;
    private String selectedTf = "M1";
    private Candle[] m5Data = new Candle[0];
    private SignalResult lastSignal;
    private final Map<String, Button> tfButtons = new HashMap<>();
    private final int bg = Color.rgb(4, 11, 20);
    private final int panel = Color.rgb(8, 22, 38);
    private final int blue = Color.rgb(12, 105, 190);
    private final int green = Color.rgb(0, 225, 135);
    private final int red = Color.rgb(255, 65, 82);

    TextView tv(String s, float z) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(z);
        v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(10, 0, 10, 0);
        return v;
    }

    Button btn(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(13); b.setTextColor(Color.WHITE);
        b.setAllCaps(false); b.setMinHeight(48); b.setPadding(7, 0, 7, 0);
        b.setBackground(roundBg(Color.rgb(9, 35, 61), 12));
        return b;
    }

    GradientDrawable roundBg(int c, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c); g.setCornerRadius(r);
        g.setStroke(1, Color.rgb(22, 104, 157));
        return g;
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(2, 8, 15));
        prefs = getSharedPreferences("rayyan4_indicators", MODE_PRIVATE);
        tgPrefs = getSharedPreferences("rayyan4_telegram", MODE_PRIVATE);
        telegramEnabled = tgPrefs.getBoolean("enabled", false);
        buildUI();
    }

    void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // Professional header: XAUUSD + realtime bid/ask + menu.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(8, 4, 8, 4);
        head.setBackgroundColor(Color.rgb(3, 14, 27));

        TextView gold = tv("🟡", 27);
        head.addView(gold, new LinearLayout.LayoutParams(45, 68));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        TextView x = tv("XAUUSD", 19); x.setTypeface(null, 1);
        title.addView(x, new LinearLayout.LayoutParams(-1, 31));
        title.addView(tv("Gold Spot / US Dollar", 10), new LinearLayout.LayoutParams(-1, 22));
        TextView rt = tv("● REALTIME", 10); rt.setTextColor(green);
        title.addView(rt, new LinearLayout.LayoutParams(-1, 18));
        head.addView(title, new LinearLayout.LayoutParams(0, 68, 1));

        LinearLayout quote = new LinearLayout(this);
        quote.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout bidBox = quoteBox("BID", green);
        LinearLayout askBox = quoteBox("ASK", red);
        quote.addView(bidBox, new LinearLayout.LayoutParams(0, 68, 1));
        quote.addView(askBox, new LinearLayout.LayoutParams(0, 68, 1));
        head.addView(quote, new LinearLayout.LayoutParams(235, 68));

        Button menu = btn("☰\nMENU"); menu.setTextSize(12);
        menu.setBackground(roundBg(Color.rgb(7, 42, 70), 12));
        menu.setOnClickListener(v -> showMenu(menu));
        head.addView(menu, new LinearLayout.LayoutParams(74, 68));
        root.addView(head);

        // Spread strip.
        LinearLayout spread = new LinearLayout(this);
        spread.setGravity(Gravity.CENTER);
        spread.setBackgroundColor(Color.rgb(6, 27, 45));
        spreadView = tv("SPREAD  --", 11);
        spreadView.setGravity(Gravity.CENTER);
        spread.addView(spreadView, new LinearLayout.LayoutParams(-1, 30));
        root.addView(spread);

        // Timeframes: every button changes the actual OHLC dataset.
        LinearLayout tf = new LinearLayout(this);
        tf.setPadding(6, 5, 6, 5); tf.setBackgroundColor(Color.rgb(3, 15, 27));
        addTfButton(tf, "M1"); addTfButton(tf, "M5"); addTfButton(tf, "M15"); addTfButton(tf, "H1");
        Button live = btn("◉ LIVE"); live.setTextColor(green);
        live.setBackground(roundBg(Color.rgb(8, 65, 48), 12));
        tf.addView(live, new LinearLayout.LayoutParams(0, 48, 1));
        live.setOnClickListener(v -> { chart.goLive(); selectTimeframe(selectedTf, false); });
        root.addView(tf);

        chart = new CandleChartView(this);
        chart.setTimeframe(selectedTf);
        loadIndicatorSettings();
        root.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));

        // Quick chart layer controls. These now really toggle the chart layers.
        LinearLayout layers = new LinearLayout(this);
        layers.setPadding(5, 3, 5, 3);
        String[] ls = {"▥ S/R", "▤ Fibo", "〽 EMA", "↕ Buy/Sell", "▣ Order", "◎ Posisi"};
        for (String s : ls) {
            Button b = btn(s); b.setTextSize(10);
            layers.addView(b, new LinearLayout.LayoutParams(0, 42, 1));
            bindQuickLayer(b, s);
        }
        root.addView(layers);

        // Signal card with dynamic entry score percentage.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setPadding(9, 6, 9, 6);
        card.setBackground(roundBg(Color.rgb(7, 39, 49), 14));

        LinearLayout top = new LinearLayout(this);
        Button side = btn("WAIT"); side.setTextSize(16); side.setTypeface(null, 1);
        side.setTextColor(Color.WHITE); side.setBackground(roundBg(Color.rgb(76, 78, 85), 12));
        top.addView(side, new LinearLayout.LayoutParams(76, 48));
        signalSummary = tv("XAUUSD • " + selectedTf + "\nWick + Sweep + BOS", 11);
        top.addView(signalSummary, new LinearLayout.LayoutParams(0, 48, 1));
        scoreView = tv("ENTRY\n--%", 12); scoreView.setGravity(Gravity.CENTER);
        top.addView(scoreView, new LinearLayout.LayoutParams(82, 48));
        card.addView(top);

        LinearLayout levels = new LinearLayout(this);
        levels.setPadding(4, 4, 4, 0);
        String[] names = {"Entry", "SL", "TP1", "TP2"};
        for (String n : names) {
            TextView v = tv(n + "\n--", 10); v.setGravity(Gravity.CENTER);
            if ("Entry".equals(n)) entryView = v;
            else if ("SL".equals(n)) slView = v;
            else if ("TP1".equals(n)) tp1View = v;
            else if ("TP2".equals(n)) tp2View = v;
            levels.addView(v, new LinearLayout.LayoutParams(0, 48, 1));
        }
        card.addView(levels);
        root.addView(card);

        status = tv("● CONNECTING • BIQUOTE • M1", 10);
        status.setBackgroundColor(Color.rgb(3, 17, 29));
        root.addView(status, new LinearLayout.LayoutParams(-1, 30));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(4, 3, 4, 3); nav.setBackgroundColor(Color.rgb(3, 13, 23));
        for (String s : new String[]{"▥ MARKET", "▥ CHART", "♧ SIGNAL", "▣ ORDERS", "⚙ SETTINGS"}) {
            Button q = btn(s); q.setTextSize(11);
            nav.addView(q, new LinearLayout.LayoutParams(0, 52, 1));
        }
        navScroll.addView(nav);
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, 58));

        setContentView(root);
        selectTimeframe("M1", false);
        load("1m", "M1");
    }

    LinearLayout quoteBox(String name, int color) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL); b.setPadding(5, 2, 5, 2);
        TextView n = tv(name, 9); n.setTextColor(color);
        TextView v = tv("--", 16); v.setTextColor(color);
        if ("BID".equals(name)) bidView = v; else askView = v;
        b.addView(n, new LinearLayout.LayoutParams(-1, 22));
        b.addView(v, new LinearLayout.LayoutParams(-1, 40));
        return b;
    }

    void addTfButton(LinearLayout parent, String tf) {
        Button q = btn(tf); q.setTextSize(14); tfButtons.put(tf, q);
        parent.addView(q, new LinearLayout.LayoutParams(0, 48, 1));
        q.setOnClickListener(v -> selectTimeframe(tf, true));
    }

    void selectTimeframe(String tf, boolean reload) {
        selectedTf = tf;
        for (Map.Entry<String, Button> e : tfButtons.entrySet()) {
            boolean active = e.getKey().equals(tf);
            e.getValue().setTextColor(active ? Color.BLACK : Color.WHITE);
            e.getValue().setBackground(roundBg(active ? Color.rgb(15, 150, 235) : Color.rgb(9, 35, 61), 12));
        }
        if (chart != null) chart.setTimeframe(tf);
        if (signalSummary != null) signalSummary.setText("XAUUSD • " + tf + "\nWick + Sweep + BOS");
        if (status != null) status.setText("● " + ("M1".equals(tf) ? "LIVE" : "LOADING") + " • BIQUOTE • " + tf);
        if (reload) {
            String interval = "M5".equals(tf) ? "5m" : ("M15".equals(tf) ? "15m" : ("H1".equals(tf) ? "1h" : "1m"));
            load(interval, tf);
        }
    }

    void load(String interval, String tf) {
        new Thread(() -> {
            try {
                Candle[] c = BiquoteClient.candles(interval);
                Candle[] m5 = "M5".equals(tf) ? c : BiquoteClient.candles("5m");
                runOnUiThread(() -> {
                    if (!tf.equals(selectedTf)) return;
                    chart.setData(c); chart.goLive();
                    if ("M5".equals(tf)) m5Data = c;
                    else m5Data = m5;
                    updateSignal(c, m5Data);
                    status.setText("● LIVE • BIQUOTE • " + tf);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("● OFFLINE • " + tf + " • RETRYING"));
            }
        }).start();

        if (ex == null) {
            ex = Executors.newSingleThreadScheduledExecutor();
            ex.scheduleAtFixedRate(() -> {
                try {
                    double[] t = BiquoteClient.tick();
                    runOnUiThread(() -> {
                        chart.setTick(t[0], t[1], selectedTf);
                        if (!chart.isEmpty()) updateSignal(chart.getDataSnapshot(), m5Data);
                        bidView.setText(String.format(Locale.US, "%.2f", t[0]));
                        askView.setText(String.format(Locale.US, "%.2f", t[1]));
                        spreadView.setText(String.format(Locale.US, "SPREAD  %.2f", t[1] - t[0]));
                        status.setText("● LIVE • BIQUOTE • " + selectedTf);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("● OFFLINE • RETRYING • " + selectedTf));
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    // Signal Engine: M5 bias + M1 entry confirmation.
    void updateSignal(Candle[] m1, Candle[] m5) {
        if (m1 == null || m1.length < 30 || m5 == null || m5.length < 30) return;
        double[] tick = new double[]{chart == null ? 0 : chart.getBid(), chart == null ? 0 : chart.getAsk()};
        SignalResult previous = lastSignal;
        lastSignal = SignalEngine.evaluate(m1, m5, tick[0], tick[1]);
        SignalResult s = lastSignal;

        scoreView.setText("ENTRY\n" + s.score + "%");
        scoreView.setTextColor(s.score >= 75 ? green : s.score >= 60 ? Color.YELLOW : Color.WHITE);

        signalSummary.setText("M1 " + s.side + " • M5 " + s.m5Bias + "\n" + s.setup +
                " • " + s.confidence + "/5");

        entryView.setText("Entry\n" + price(s.entry));
        slView.setText("SL\n" + price(s.sl));
        tp1View.setText("TP1\n" + price(s.tp1));
        tp2View.setText("TP2\n" + price(s.tp2));

        View v = ((ViewGroup)scoreView.getParent()).getChildAt(0);
        if (v instanceof Button) {
            Button b = (Button)v;
            b.setText(s.side);
            b.setBackground(roundBg("BUY".equals(s.side) ? Color.rgb(0,190,105) :
                    "SELL".equals(s.side) ? Color.rgb(220,45,60) : Color.rgb(76,78,85), 12));
        }
        chart.setSignalLevels(s.entry, s.sl, s.tp1, s.tp2, "BUY".equals(s.side) || "SELL".equals(s.side));
        boolean changed = previous == null || !s.side.equals(previous.side);
        if (changed && ("BUY".equals(s.side) || "SELL".equals(s.side))) {
            String msg = "XAUUSD SCALPING\\n\\n" + s.side + "\\nEntry: " + price(s.entry) +
                    "\\nSL: " + price(s.sl) + "\\nTP1: " + price(s.tp1) + "\\nTP2: " + price(s.tp2) +
                    "\\nTF: M1\\nBias: M5 " + s.m5Bias + "\\nSetup: " + s.setup +
                    "\\nConfidence: " + s.confidence + "/5";
            AppLog.add(this, "SIGNAL", s.side + " " + price(s.entry) + " score=" + s.score);
            if (telegramEnabled) sendTelegram(msg);
            notifyLocal("XAUUSD " + s.side, "Entry " + price(s.entry) + " | SL " + price(s.sl) + " | TP1 " + price(s.tp1));
        }
    }

    String price(double v) {
        return v > 0 ? String.format(Locale.US, "%.2f", v) : "--";
    }

    double ema(Candle[] a, int n) {
        double k = 2.0 / (n + 1.0), e = a[0].c;
        for (int i = 1; i < a.length; i++) e = a[i].c * k + e * (1 - k);
        return e;
    }

    void showMenu(View anchor) {
        String[] items = {"Market", "Chart", "Indicators", "Signal", "Orders", "Strategy",
            "Risk Management", "Telegram", "MT5 / EA", "Settings"};
        PopupWindow pop = new PopupWindow(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(8, 8, 8, 8);
        box.setBackgroundColor(Color.rgb(5, 24, 42));
        for (String s : items) {
            Button b = btn(s); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setTextSize(14); box.addView(b, new LinearLayout.LayoutParams(270, 54));
            b.setOnClickListener(v -> {
                pop.dismiss();
                if ("Indicators".equals(s)) showIndicatorDialog();
                else if ("Signal".equals(s)) showSignalDialog();
                else if ("Telegram".equals(s)) showTelegramDialog();
                else if ("Orders".equals(s) || "Settings".equals(s)) showLogDialog(s);
                else Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
            });
        }
        pop.setContentView(box); pop.setWidth(278);
        pop.setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pop.setOutsideTouchable(true); pop.setFocusable(true); pop.setElevation(16);
        pop.showAsDropDown(anchor, -204, 4);
    }

    void showSignalDialog() {
        if (lastSignal == null) {
            Toast.makeText(this, "Menunggu data Signal Engine...", Toast.LENGTH_SHORT).show();
            return;
        }
        SignalResult s = lastSignal;
        String msg =
            "SIDE        : " + s.side + "\n" +
            "M5 BIAS     : " + s.m5Bias + "\n" +
            "M1 ENTRY    : " + s.entry + "\n" +
            "SL          : " + s.sl + "\n" +
            "TP1         : " + s.tp1 + "\n" +
            "TP2         : " + s.tp2 + "\n" +
            "R:R         : " + String.format(Locale.US, "%.2f", s.rr) + "\n" +
            "CONFIDENCE  : " + s.confidence + "/5\n" +
            "ENTRY SCORE : " + s.score + "%\n\n" +
            "Liquidity Sweep : " + onoff(s.liquiditySweep) + "\n" +
            "Wick Rejection  : " + onoff(s.wickRejection) + "\n" +
            "BOS             : " + onoff(s.bos) + "\n" +
            "EMA Filter      : " + onoff(s.emaFilter) + "\n" +
            "RSI Filter      : " + onoff(s.rsiFilter) + "\n" +
            "MACD Filter     : " + onoff(s.macdFilter) + "\n" +
            "ATR Filter      : " + onoff(s.atrFilter) + "\n" +
            "Spread Filter   : " + onoff(s.spreadFilter);
        new AlertDialog.Builder(this).setTitle("SIGNAL ENGINE")
            .setMessage(msg).setPositiveButton("TUTUP", null).show();
    }

    String onoff(boolean v) { return v ? "ON" : "OFF"; }

    boolean ind(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    void loadIndicatorSettings() {
        chart.setShowEMA9(ind("ema9", true));
        chart.setShowEMA21(ind("ema21", true));
        chart.setShowEMA50(ind("ema50", true));
        chart.setShowSR(ind("sr", true));
        chart.setShowFibo(ind("fibo", true));
        chart.setShowRSI(ind("rsi", true));
        chart.setShowMACD(ind("macd", true));
        chart.setShowATR(ind("atr", true));
        chart.setShowSignals(ind("signals", true));
        chart.setShowWickBos(ind("wickbos", true));
        chart.setShowTradeLevels(ind("orders", true));
        chart.setShowPosition(ind("position", true));
        chart.setShowBidAsk(ind("bidask", true));
    }

    void setInd(String key, boolean enabled) {
        prefs.edit().putBoolean(key, enabled).apply();
        switch (key) {
            case "ema9": chart.setShowEMA9(enabled); break;
            case "ema21": chart.setShowEMA21(enabled); break;
            case "ema50": chart.setShowEMA50(enabled); break;
            case "sr": chart.setShowSR(enabled); break;
            case "fibo": chart.setShowFibo(enabled); break;
            case "rsi": chart.setShowRSI(enabled); break;
            case "macd": chart.setShowMACD(enabled); break;
            case "atr": chart.setShowATR(enabled); break;
            case "signals": chart.setShowSignals(enabled); break;
            case "wickbos": chart.setShowWickBos(enabled); break;
            case "orders": chart.setShowTradeLevels(enabled); break;
            case "position": chart.setShowPosition(enabled); break;
            case "bidask": chart.setShowBidAsk(enabled); break;
        }
    }

    void bindQuickLayer(Button b, String label) {
        b.setOnClickListener(v -> {
            if (label.contains("S/R")) {
                setInd("sr", !ind("sr", true));
            } else if (label.contains("Fibo")) {
                setInd("fibo", !ind("fibo", true));
            } else if (label.contains("EMA")) {
                boolean next = !(ind("ema9", true) && ind("ema21", true) && ind("ema50", true));
                setInd("ema9", next); setInd("ema21", next); setInd("ema50", next);
            } else if (label.contains("Buy/Sell")) {
                setInd("signals", !ind("signals", true));
            } else if (label.contains("Order")) {
                setInd("orders", !ind("orders", true));
            } else if (label.contains("Posisi")) {
                setInd("position", !ind("position", true));
            }
            updateQuickLayerButton(b, label);
        });
        updateQuickLayerButton(b, label);
    }

    void updateQuickLayerButton(Button b, String label) {
        boolean on;
        if (label.contains("S/R")) on = ind("sr", true);
        else if (label.contains("Fibo")) on = ind("fibo", true);
        else if (label.contains("EMA")) on = ind("ema9", true) || ind("ema21", true) || ind("ema50", true);
        else if (label.contains("Buy/Sell")) on = ind("signals", true);
        else if (label.contains("Order")) on = ind("orders", true);
        else on = ind("position", true);
        b.setTextColor(on ? green : Color.LTGRAY);
        b.setBackground(roundBg(on ? Color.rgb(8, 65, 48) : Color.rgb(9, 35, 61), 12));
    }

    void showIndicatorDialog() {
        final String[] names = {
            "EMA 9", "EMA 21", "EMA 50", "Support / Resistance", "Fibonacci",
            "RSI 14", "MACD", "ATR", "Buy / Sell", "Wick / Sweep / BOS",
            "Bid / Ask", "Entry / SL / TP"
        };
        final String[] keys = {
            "ema9", "ema21", "ema50", "sr", "fibo",
            "rsi", "macd", "atr", "signals", "wickbos",
            "bidask", "orders"
        };
        boolean[] checked = {
            ind("ema9", true), ind("ema21", true), ind("ema50", true), ind("sr", true), ind("fibo", true),
            ind("rsi", true), ind("macd", true), ind("atr", true), ind("signals", true), ind("wickbos", true),
            ind("bidask", true), ind("orders", true)
        };
        new AlertDialog.Builder(this)
            .setTitle("INDICATORS • ON / OFF")
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> setInd(keys[which], isChecked))
            .setPositiveButton("TUTUP", null)
            .show();
    }


    void sendTelegram(String msg) {
        TelegramClient.send(tgPrefs.getString("token",""), tgPrefs.getString("chat",""), msg,
            (ok, detail) -> AppLog.add(this, "TELEGRAM", ok ? "sent" : "error"));
    }
    void notifyLocal(String title, String msg) {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        String id="rayyan4_alerts";
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel(id,"Rayyan4 Alerts",NotificationManager.IMPORTANCE_HIGH));
        nm.notify((int)(System.currentTimeMillis() & 0x7fffffff),
            new NotificationCompat.Builder(this,id).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(msg).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build());
    }
    void showTelegramDialog() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(22,8,22,4);
        EditText token=new EditText(this); token.setHint("Bot Token"); token.setText(tgPrefs.getString("token",""));
        EditText chat=new EditText(this); chat.setHint("Chat ID"); chat.setInputType(2); chat.setText(tgPrefs.getString("chat",""));
        Switch sw=new Switch(this); sw.setText("Telegram aktif"); sw.setChecked(telegramEnabled);
        box.addView(token); box.addView(chat); box.addView(sw);
        new AlertDialog.Builder(this).setTitle("TELEGRAM").setView(box)
            .setNegativeButton("BATAL",null)
            .setNeutralButton("TEST",(d,w)->TelegramClient.send(token.getText().toString(),chat.getText().toString(),"Rayyan4 Telegram TEST",(ok,x)->{}))
            .setPositiveButton("SIMPAN",(d,w)->{
                tgPrefs.edit().putString("token",token.getText().toString().trim()).putString("chat",chat.getText().toString().trim()).putBoolean("enabled",sw.isChecked()).apply();
                telegramEnabled=sw.isChecked(); AppLog.add(this,"TELEGRAM",telegramEnabled?"enabled":"disabled");
            }).show();
    }
    void showLogDialog(String title) {
        String data=AppLog.all(this); if(data.isEmpty()) data="Belum ada log.";
        TextView v=tv(data,10); v.setTextIsSelectable(true); v.setGravity(Gravity.TOP|Gravity.START);
        ScrollView sc=new ScrollView(this); sc.addView(v);
        new AlertDialog.Builder(this).setTitle(title).setView(sc).setNegativeButton("TUTUP",null)
            .setNeutralButton("HAPUS LOG",(d,w)->AppLog.clear(this)).show();
    }

    @Override protected void onDestroy() {
        if (ex != null) ex.shutdownNow();
        super.onDestroy();
    }
}
