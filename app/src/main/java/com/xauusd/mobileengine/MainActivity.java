package com.xauusd.mobileengine;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static volatile boolean visible = false;
    public static boolean isAppVisible(){ return visible; }
    private CandleChartView chart;
    private TextView priceView, spreadView, status, signalSummary, scoreView, entryView, slView, tp1View, tp2View, marketTitle, marketSubtitle;
    private double lastHeaderPrice=Double.NaN;
    private Button signalSideButton, buyLimitBtn, sellLimitBtn;
    private TextView entryReadyView;
    private final Handler signalUiHandler = new Handler(Looper.getMainLooper());
    private boolean entryReady = false;
    private boolean blinkOn = false;
    private String readySide = "WAIT";
    private final Runnable entryBlinkRunnable = new Runnable() {
        @Override public void run() {
            if (!entryReady || buyLimitBtn == null || sellLimitBtn == null) return;
            blinkOn = !blinkOn;
            applyEntryButtonColors();
            signalUiHandler.postDelayed(this, 520L);
        }
    };
    private EditText quickLotInput;
    private ScheduledExecutorService ex;
    private ScheduledExecutorService candleRefresh;
    private SharedPreferences prefs, tgPrefs;
    private boolean telegramEnabled;
    private String selectedTf="M1";
    private String selectedMarket="XAUUSD";
    private Candle[] m5Data=new Candle[0];
    private SignalResult lastSignal;
    private final Map<String,Button> tfButtons=new HashMap<>();
    private final int bg=Color.rgb(3,10,18);
    private final int green=Color.rgb(0,225,135);
    private final int red=Color.rgb(255,65,82);

    TextView tv(String s,float z){
        TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(z);
        v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(8,0,8,0);return v;
    }

    Button btn(String s){
        Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setTextColor(Color.WHITE);
        b.setAllCaps(false);b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setMinHeight(36);b.setMinimumHeight(36);b.setPadding(3,0,3,0);
        b.setBackground(roundBg(Color.rgb(7,34,60),12));return b;
    }

    GradientDrawable roundBg(int c,int r){
        GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(r);g.setStroke(1,Color.rgb(22,104,157));return g;
    }

    private void installCrashDiagnostics(){
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    StringWriter sw = new StringWriter();
                    error.printStackTrace(new PrintWriter(sw));
                    getSharedPreferences("rayyan4_crashlog", MODE_PRIVATE).edit()
                        .putString("last_crash_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                        .putString("last_crash_thread", thread == null ? "unknown" : thread.getName())
                        .putString("last_crash_stack", sw.toString())
                        .commit();
                } catch (Throwable ignored) {}
                if (previous != null) previous.uncaughtException(thread, error);
            });
        } catch (Throwable ignored) {}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        installCrashDiagnostics();
        try {
            getSharedPreferences("rayyan4_crashlog", MODE_PRIVATE).edit()
                .putString("last_action", "MainActivity onCreate")
                .putString("last_action_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                .apply();
        } catch (Throwable ignored) {}
        getWindow().setStatusBarColor(Color.rgb(1,7,13));
        // Keep the main screen usable when the Lot field has opened the soft keyboard.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs=getSharedPreferences("rayyan4_indicators",MODE_PRIVATE);
        tgPrefs=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE);
        telegramEnabled=tgPrefs.getBoolean("enabled",false);
        selectedMarket=prefs.getString("market","XAUUSD");
        // Explicit project request: enable AUTO LIMIT in DEMO-ready mode.
        // Order submission remains guarded by fresh EA polling, signal validity, spread,
        // LIMIT price geometry, lot minimum, duplicate cooldown, and the single-command queue.
        SharedPreferences gatewayPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        if(!gatewayPrefs.getBoolean("auto_safe_guard_v2",false)){
            gatewayPrefs.edit().putBoolean("auto",true).putString("operation_mode","READY")
                .putBoolean("auto_safe_guard_v2",true).apply();
        }
        buildUI();
    }

    void buildUI(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);

        // HEADER / SPREAD / TIMEFRAME UI is isolated in RayyanUiLayout.
        // Gateway, queueing, and order-sending methods below remain untouched.
        RayyanUiLayout.Header headerUi = RayyanUiLayout.build(this, selectedMarket,
            new RayyanUiLayout.Listener() {
                @Override public void onMenu(View anchor) {
                    showMenu(anchor);
                }
                @Override public void onTimeframe(String timeframe) {
                    selectTimeframe(timeframe, true);
                }
                @Override public void onLive() {
                    if (chart != null) {
                        chart.goLive();
                        selectTimeframe(selectedTf, false);
                    }
                }
            });
        marketTitle = headerUi.marketTitle;
        marketSubtitle = headerUi.marketSubtitle;
        priceView = headerUi.priceView;
        spreadView = headerUi.spreadView;
        tfButtons.clear();
        tfButtons.putAll(headerUi.timeframeButtons);
        root.addView(headerUi.container,
            new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));

        chart=new CandleChartView(this);chart.setMarket(selectedMarket);chart.setTimeframe(selectedTf);loadIndicatorSettings();
        root.addView(chart,new LinearLayout.LayoutParams(-1,0,1));

        // CHART TOOLS
        LinearLayout layers=new LinearLayout(this);layers.setPadding(4,3,4,3);layers.setBackgroundColor(Color.rgb(3,14,24));
        String[] ls={"⌗ S/R","⟋ Fibo","〽 EMA","↗ Buy/Sell","▤ Order","◎ Posisi"};
        for(String s:ls){
            Button b=btn(s);b.setTextSize(9);layers.addView(b,new LinearLayout.LayoutParams(0,38,1));bindQuickLayer(b,s);
        }
        root.addView(layers);

        // SIGNAL CARD
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(8,5,8,5);
        card.setBackground(roundBg(Color.rgb(5,43,53),14));

        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        Button side=btn("");side.setText("");side.setTextSize(1);side.setPadding(0,0,0,0);side.setMinWidth(0);side.setMinimumWidth(0);
        signalSideButton=side;
        side.setBackground(roundBg(Color.rgb(110,115,125),1000));
        side.setOnClickListener(v->Toast.makeText(this,"Gunakan tombol BUY LIMIT / SELL LIMIT untuk mengirim order",Toast.LENGTH_SHORT).show());
        top.addView(side,new LinearLayout.LayoutParams(30,30));
        signalSummary=tv("Menunggu wick valid • 0/5\nWick Rejection + Liquidity Sweep + BOS",10);
        signalSummary.setIncludeFontPadding(false);signalSummary.setMaxLines(2);
        signalSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        signalSummary.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        top.addView(signalSummary,new LinearLayout.LayoutParams(0,50,1));
        scoreView=tv("SCORE 0%",11);scoreView.setGravity(Gravity.CENTER);scoreView.setIncludeFontPadding(false);scoreView.setPadding(uiDp(2),0,uiDp(2),0);scoreView.setSingleLine(true);scoreView.setEllipsize(android.text.TextUtils.TruncateAt.END);scoreView.setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);top.addView(scoreView,new LinearLayout.LayoutParams(uiDp(86),uiDp(44)));
        card.addView(top);

        entryReadyView=tv("WAIT • menunggu setup valid",11);
        entryReadyView.setGravity(Gravity.CENTER);
        entryReadyView.setIncludeFontPadding(false);
        entryReadyView.setSingleLine(true);
        entryReadyView.setTextColor(Color.rgb(255,205,95));
        entryReadyView.setPadding(8,0,8,0);
        entryReadyView.setBackground(roundBg(Color.rgb(35,38,35),10));
        LinearLayout.LayoutParams readyParams=new LinearLayout.LayoutParams(-1,30);
        readyParams.setMargins(0,4,0,3);
        card.addView(entryReadyView,readyParams);

        LinearLayout levels=new LinearLayout(this);levels.setPadding(2,2,2,0);
        String[] names={"Entry","SL","TP1","TP2"};
        for(String n:names){
            TextView v=tv(n+"\n--",11);v.setGravity(Gravity.CENTER);v.setIncludeFontPadding(false);v.setMaxLines(2);v.setPadding(1,2,1,2);v.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            if(n.equals("Entry"))entryView=v;else if(n.equals("SL"))slView=v;else if(n.equals("TP1"))tp1View=v;else tp2View=v;
            levels.addView(v,new LinearLayout.LayoutParams(0,54,1));
        }
        card.addView(levels);
        root.addView(card,new LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT));

        // ONE-TAP DEMO LIMIT CONTROLS stay on the main screen, no MT5/EA dialog.
        LinearLayout limitActions=new LinearLayout(this);
        limitActions.setGravity(Gravity.CENTER_VERTICAL);
        limitActions.setPadding(5,3,5,3);
        limitActions.setBackgroundColor(Color.rgb(3,14,24));
        quickLotInput=new EditText(this);
        quickLotInput.setSingleLine(true);
        quickLotInput.setTextSize(12);
        quickLotInput.setTextColor(Color.WHITE);
        quickLotInput.setHintTextColor(Color.LTGRAY);
        quickLotInput.setHint("LOT");
        quickLotInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        quickLotInput.setText(lotText(lotForMarket()));
        quickLotInput.setSelectAllOnFocus(true);
        quickLotInput.setBackground(roundBg(Color.rgb(12,35,52),8));
        quickLotInput.setPadding(8,0,8,0);
        limitActions.addView(quickLotInput,new LinearLayout.LayoutParams(62,40));
        View lotGap=new View(this);
        limitActions.addView(lotGap,new LinearLayout.LayoutParams(5,1));
        buyLimitBtn=btn("● BUY LIMIT");
        buyLimitBtn.setTextSize(12);
        buyLimitBtn.setTypeface(null,Typeface.BOLD);
        buyLimitBtn.setTextColor(Color.WHITE);
        buyLimitBtn.setBackground(roundBg(Color.rgb(0,145,88),10));
        sellLimitBtn=btn("● SELL LIMIT");
        sellLimitBtn.setTextSize(12);
        sellLimitBtn.setTypeface(null,Typeface.BOLD);
        sellLimitBtn.setTextColor(Color.WHITE);
        sellLimitBtn.setBackground(roundBg(Color.rgb(190,45,58),10));
        limitActions.addView(buyLimitBtn,new LinearLayout.LayoutParams(0,40,1));
        View actionGap=new View(this);
        limitActions.addView(actionGap,new LinearLayout.LayoutParams(7,1));
        limitActions.addView(sellLimitBtn,new LinearLayout.LayoutParams(0,40,1));
        buyLimitBtn.setOnClickListener(v->prepareLimitButton("BUY LIMIT button tapped","BUY",v));
        sellLimitBtn.setOnClickListener(v->prepareLimitButton("SELL LIMIT button tapped","SELL",v));
        root.addView(limitActions,new LinearLayout.LayoutParams(-1,46));
        card.setOnClickListener(v->executeCurrentSignal());
        signalSummary.setOnClickListener(v->executeCurrentSignal());
        scoreView.setOnClickListener(v->executeCurrentSignal());
        entryView.setOnClickListener(v->executeCurrentSignal());
        slView.setOnClickListener(v->executeCurrentSignal());
        tp1View.setOnClickListener(v->executeCurrentSignal());
        tp2View.setOnClickListener(v->executeCurrentSignal());

        status=tv("● LIVE • RAYYAN4 • M1",10);status.setBackgroundColor(Color.rgb(3,17,29));status.setSingleLine(true);status.setIncludeFontPadding(false);
        root.addView(status,new LinearLayout.LayoutParams(-1,25));

        // BOTTOM NAV - 5 fixed tabs
        LinearLayout nav=new LinearLayout(this);nav.setPadding(4,3,4,3);nav.setBackgroundColor(Color.rgb(2,12,22));
        String[] navs={"▥ MARKET","▥ CHART","♧ SIGNAL","▣ ORDERS","⚙ SETTINGS"};
        for(String s:navs){
            Button q=btn(s);q.setTextSize(9);nav.addView(q,new LinearLayout.LayoutParams(0,48,1));
            if(s.contains("MARKET"))q.setOnClickListener(v->showMarketDialog());
            else if(s.contains("SIGNAL"))q.setOnClickListener(v->showSignalDialog());
            else if(s.contains("ORDERS"))q.setOnClickListener(v->showLogDialog("ORDERS / HISTORY"));
            else if(s.contains("SETTINGS"))q.setOnClickListener(v->showSettingsPanel(q));
            else if(s.contains("CHART"))q.setOnClickListener(v->chart.goLive());
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,53));

        setContentView(root);
        requestNotificationPermission();
        startGateway();
        selectTimeframe("M1",false);load("1m","M1");
    }

    LinearLayout priceBox(){
        LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(2,2,2,2);
        TextView n=tv("PRICE",9);n.setTextColor(Color.rgb(0,210,220));
        priceView=tv("--",20);priceView.setTypeface(null,Typeface.BOLD);priceView.setTextColor(Color.CYAN);priceView.setPadding(1,0,1,0);priceView.setGravity(Gravity.CENTER);priceView.setSingleLine(true);priceView.setIncludeFontPadding(false);priceView.setAutoSizeTextTypeUniformWithConfiguration(14,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        b.addView(n,new LinearLayout.LayoutParams(-1,18));
        b.addView(priceView,new LinearLayout.LayoutParams(-1,36));
        return b;
    }

    void updateHeaderPrice(double bid,double ask){
        if(priceView==null||!Double.isFinite(bid)||!Double.isFinite(ask)||bid<=0||ask<=0)return;
        double current=(bid+ask)/2.0;
        int color=Color.CYAN;
        if(!Double.isNaN(lastHeaderPrice)){
            if(current>lastHeaderPrice)color=green;
            else if(current<lastHeaderPrice)color=red;
        }
        priceView.setText(String.format(Locale.US,"%,.2f",current));
        priceView.setTextColor(color);
        lastHeaderPrice=current;
    }

    void addTfButton(LinearLayout parent,String tf){
        Button q=btn(tf);q.setTextSize(11);tfButtons.put(tf,q);parent.addView(q,new LinearLayout.LayoutParams(0,38,1));
        q.setOnClickListener(v->selectTimeframe(tf,true));
    }

    void selectTimeframe(String tf,boolean reload){
        String previousTf=selectedTf;
        selectedTf=tf;
        if(!tf.equals(previousTf))resetEntryReadyUi("memuat timeframe "+tf);
        for(Map.Entry<String,Button> e:tfButtons.entrySet()){
            boolean active=e.getKey().equals(tf);e.getValue().setTextColor(active?Color.BLACK:Color.WHITE);
            e.getValue().setBackground(roundBg(active?Color.rgb(15,150,235):Color.rgb(7,34,60),12));
        }
        if(chart!=null)chart.setTimeframe(tf);
        if(signalSummary!=null)signalSummary.setText(selectedMarket+" • "+tf+"\nWick Rejection + Liquidity Sweep + BOS");
        if(status!=null)status.setText("● "+("M1".equals(tf)?"LIVE":"LOADING")+" • RAYYAN4 • "+tf);
        if(reload)load(interval(tf),tf);
    }

    String interval(String tf){
        if("M5".equals(tf))return "5m";if("M15".equals(tf))return "15m";if("H1".equals(tf))return "1h";
        if("H4".equals(tf))return "4h";if("D1".equals(tf))return "1d";return "1m";
    }

    void load(String interval,String tf){
        new Thread(()->{
            try{
                String market=selectedMarket;
                Candle[] c=BiquoteClient.candles(market,interval);
                Candle[] m5="M5".equals(tf)?c:BiquoteClient.candles(market,"5m");
                runOnUiThread(()->{
                    if(!tf.equals(selectedTf))return;
                    chart.setData(c);chart.goLive();m5Data=m5;updateSignal(c,m5Data);
                    status.setText("● LIVE • RAYYAN4 • "+tf);
                });
            }catch(Exception e){runOnUiThread(()->{status.setText("● OFFLINE • "+tf+" • RETRYING");resetEntryReadyUi("feed offline • menunggu data");});}
        }).start();

        if(ex==null){
            ex=Executors.newSingleThreadScheduledExecutor();
            ex.scheduleAtFixedRate(()->{
                try{
                    String market=selectedMarket;
                    double[] t=BiquoteClient.tick(market);
                    runOnUiThread(()->{
                        if(!market.equals(selectedMarket))return;
                        chart.setTick(t[0],t[1],selectedTf);
                        if(!chart.isEmpty())updateSignal(chart.getDataSnapshot(),m5Data);
                        updateHeaderPrice(t[0],t[1]);
                        spreadView.setText(String.format(Locale.US,"SPREAD  %.2f",t[1]-t[0]));
                        status.setText("● LIVE • RAYYAN4 • "+selectedTf);
                    });
                }catch(Exception e){runOnUiThread(()->{status.setText("● OFFLINE • RETRYING • "+selectedTf);resetEntryReadyUi("feed offline • menunggu data");});}
            },1,1,TimeUnit.SECONDS);
        }

        // Tick polling updates the quote every second, but that alone cannot create
        // new timestamped OHLC bars or refresh the M5 bias. Refresh both candle feeds
        // separately, without blocking the live quote loop or resetting the chart
        // while the user is looking at another market/timeframe.
        if(candleRefresh==null){
            candleRefresh=Executors.newSingleThreadScheduledExecutor();
            candleRefresh.scheduleWithFixedDelay(()->{
                final String market=selectedMarket;
                final String tfNow=selectedTf;
                try{
                    Candle[] fresh=BiquoteClient.candles(market,interval(tfNow));
                    Candle[] freshM5="M5".equals(tfNow)?fresh:BiquoteClient.candles(market,"5m");
                    runOnUiThread(()->{
                        if(!market.equals(selectedMarket)||!tfNow.equals(selectedTf))return;
                        boolean wasFollowing=chart.isFollow();
                        chart.setData(fresh);
                        if(wasFollowing)chart.goLive();
                        m5Data=freshM5;
                        updateSignal(chart.getDataSnapshot(),m5Data);
                        status.setText("● LIVE • "+market+" • "+tfNow+" • OHLC refreshed");
                    });
                }catch(Exception e){
                    runOnUiThread(()->{
                        if(market.equals(selectedMarket)&&tfNow.equals(selectedTf))
                            status.setText("● QUOTE LIVE • OHLC RETRY • "+tfNow);
                    });
                }
            },15,15,TimeUnit.SECONDS);
        }
    }

    private void prepareLimitButton(String action,String side,View button){
        // Hide the Lot input keyboard before placing an order; on small phones it
        // otherwise covers most of the chart and can look like the screen closed.
        try{
            View focus=getCurrentFocus();
            if(focus!=null){
                Object service=getSystemService(INPUT_METHOD_SERVICE);
                if(service instanceof android.view.inputmethod.InputMethodManager)
                    ((android.view.inputmethod.InputMethodManager)service)
                        .hideSoftInputFromWindow(focus.getWindowToken(),0);
            }
            if(quickLotInput!=null)quickLotInput.clearFocus();
            if(button!=null)button.requestFocus();
        }catch(Throwable ignored){}
        recordDiagnosticAction(action);
        executeLimitForSide(side);
    }

    private void recordDiagnosticAction(String action){
        try {
            getSharedPreferences("rayyan4_crashlog", MODE_PRIVATE).edit()
                .putString("last_action", action)
                .putString("last_action_time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                .apply();
            AppLog.add(this,"DIAGNOSTIC",action);
        } catch (Throwable ignored) {}
    }

    void executeLimitForSide(String requestedSide){
        // Guard the entire button path: malformed live candle/signal data must
        // show an error instead of terminating MainActivity.
        try {
            if(chart==null || chart.isEmpty() || m5Data==null || m5Data.length<30){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT: data M1/M5 belum siap");
                Toast.makeText(this,"Data M1/M5 belum siap",Toast.LENGTH_SHORT).show();
                return;
            }
            Candle[] liveM1=chart.getDataSnapshot();
            if(liveM1==null || liveM1.length<30){
                Toast.makeText(this,"Data candle M1 belum cukup",Toast.LENGTH_SHORT).show();
                return;
            }
            SignalResult s=SignalEngine.evaluate(liveM1,m5Data,chart.getBid(),chart.getAsk());
            if(s==null || (!"BUY".equals(s.side)&&!"SELL".equals(s.side)) || s.entry<=0 || s.sl<=0 || s.tp1<=0){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT: sinyal belum valid; side="+(s==null?"null":s.side));
                Toast.makeText(this,"Belum ada setup valid • order tidak dikirim",Toast.LENGTH_SHORT).show();
                return;
            }
            lastSignal=s;
            if(!requestedSide.equals(s.side)){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT blocked: sinyal aktif "+s.side+"; tidak mengirim order berlawanan");
                Toast.makeText(this,"Sinyal aktif "+s.side+" • tombol "+requestedSide+" tidak dikirim",Toast.LENGTH_LONG).show();
                return;
            }
            double lot=readQuickLot();
            if(lot<=0)return;
            String orderSide="BUY".equals(requestedSide)?"BUY_LIMIT":"SELL_LIMIT";
            // Start the gateway only after the order is successfully queued.
            // A rejected/invalid order must leave the APK open without starting
            // unnecessary background work.
            boolean queued = queueLimit(orderSide,lot,s.entry,s.sl,s.tp2>0?s.tp2:s.tp1);
            if(queued){
                AppLog.add(this,"ORDER","button accepted queue; starting Gateway");
                startGateway();
            }else{
                AppLog.add(this,"ORDER","button order rejected before queue; Gateway not started");
            }
        } catch (Throwable e) {
            try { AppLog.add(this,"CRASH_GUARD","LIMIT "+requestedSide+" "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())); } catch(Throwable ignored) {}
            try { Toast.makeText(this,"LIMIT gagal diproses • APK tetap dibuka. Periksa Log.",Toast.LENGTH_LONG).show(); } catch(Throwable ignored) {}
        }
    }

    void executeCurrentSignal(){
        // Manual chart-tap entry only. AUTO LIMIT has its own direct path below and must never wait for a confirmation dialog.
        // Re-evaluate the signal immediately before queueing so stale levels are not used.
        if(chart==null || chart.isEmpty() || m5Data==null || m5Data.length<30){
            Toast.makeText(this,"Data M1/M5 belum siap",Toast.LENGTH_SHORT).show();
            return;
        }
        Candle[] liveM1=chart.getDataSnapshot();
        double liveBid=chart.getBid(), liveAsk=chart.getAsk();
        SignalResult s=SignalEngine.evaluate(liveM1,m5Data,liveBid,liveAsk);
        lastSignal=s;

        if(s==null || (!"BUY".equals(s.side)&&!"SELL".equals(s.side)) || s.entry<=0 || s.sl<=0 || s.tp1<=0){
            Toast.makeText(this,"Setup sudah berubah • tidak dieksekusi",Toast.LENGTH_SHORT).show();
            return;
        }

        startGateway();
        String side="BUY".equals(s.side)?"BUY_LIMIT":"SELL_LIMIT";
        double lot=lotForMarket();
        queueLimit(side,lot,s.entry,s.sl,s.tp2>0?s.tp2:s.tp1);
    }

    private boolean isEntryReady(SignalResult signal,double bid,double ask){
        if(signal==null||bid<=0||ask<=0)return false;
        if(!"BUY".equals(signal.side)&&!"SELL".equals(signal.side))return false;
        int score=Math.max(0,Math.min(100,signal.score));
        if(score<getMinimumEntryConfidence()||!signal.spreadFilter||!signal.atrFilter)return false;
        if(signal.entry<=0||signal.sl<=0||signal.tp1<=0)return false;
        if("BUY".equals(signal.side))return signal.entry<ask&&signal.sl<signal.entry&&signal.tp1>signal.entry;
        return signal.entry>bid&&signal.tp1<signal.entry&&signal.sl>signal.entry;
    }

    private double validAutoTarget(SignalResult signal){
        if(signal==null||signal.entry<=0)return 0;
        if("BUY".equals(signal.side)){
            if(signal.tp2>signal.entry)return signal.tp2;
            if(signal.tp1>signal.entry)return signal.tp1;
        }else if("SELL".equals(signal.side)){
            if(signal.tp2>0&&signal.tp2<signal.entry)return signal.tp2;
            if(signal.tp1>0&&signal.tp1<signal.entry)return signal.tp1;
        }
        return 0;
    }

    private boolean isAutoEntryEligible(SignalResult signal,double bid,double ask,boolean m1Context){
        if(!m1Context||!isEntryReady(signal,bid,ask)||!signal.wickRejection)return false;
        double target=validAutoTarget(signal);
        if(target<=0)return false;
        if("BUY".equals(signal.side))
            return signal.entry<ask&&signal.sl<signal.entry&&target>signal.entry;
        if("SELL".equals(signal.side))
            return signal.entry>bid&&target<signal.entry&&signal.sl>signal.entry;
        return false;
    }

    private void updateEntryReadyUi(SignalResult signal,double bid,double ask){
        boolean nextReady=isEntryReady(signal,bid,ask);
        String nextSide=nextReady?signal.side:"WAIT";
        if(nextReady){
            boolean sideChanged=!readySide.equals(nextSide);
            if(!entryReady||sideChanged){
                signalUiHandler.removeCallbacks(entryBlinkRunnable);
                blinkOn=false;
                readySide=nextSide;
                entryReady=true;
                signalUiHandler.post(entryBlinkRunnable);
            }else{
                entryReady=true;
                readySide=nextSide;
            }
            entryReadyView.setText("● ENTRY READY • "+nextSide+" LIMIT");
            entryReadyView.setTextColor(Color.WHITE);
            entryReadyView.setBackground(roundBg("BUY".equals(nextSide)?Color.rgb(0,105,68):Color.rgb(140,28,43),10));
        }else{
            signalUiHandler.removeCallbacks(entryBlinkRunnable);
            entryReady=false;
            readySide="WAIT";
            blinkOn=false;
            boolean hasSide=signal!=null&&("BUY".equals(signal.side)||"SELL".equals(signal.side));
            entryReadyView.setText(hasSide?"WAIT • "+signal.side+" setup belum memenuhi filter/level":"WAIT • menunggu setup valid");
            entryReadyView.setTextColor(Color.rgb(255,205,95));
            entryReadyView.setBackground(roundBg(Color.rgb(35,38,35),10));
        }
        applyEntryButtonColors();
    }

    private void resetEntryReadyUi(String reason){
        signalUiHandler.removeCallbacks(entryBlinkRunnable);
        entryReady=false;
        readySide="WAIT";
        blinkOn=false;
        if(entryReadyView!=null){
            entryReadyView.setText("WAIT • "+reason);
            entryReadyView.setTextColor(Color.rgb(255,205,95));
            entryReadyView.setBackground(roundBg(Color.rgb(35,38,35),10));
        }
        applyEntryButtonColors();
    }

    private GradientDrawable entryButtonBg(int color,boolean active,int radius){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(uiDp(radius));
        g.setStroke(uiDp(active?2:1),active?color:Color.rgb(55,68,78));
        return g;
    }

    private void applyEntryButtonColors(){
        if(buyLimitBtn==null||sellLimitBtn==null)return;
        int buyColor, sellColor;
        boolean buyActive=entryReady&&"BUY".equals(readySide);
        boolean sellActive=entryReady&&"SELL".equals(readySide);
        if(buyActive){
            buyColor=blinkOn?Color.rgb(0,255,145):Color.rgb(0,112,70);
            sellColor=Color.rgb(38,47,57);
        }else if(sellActive){
            buyColor=Color.rgb(38,47,57);
            sellColor=blinkOn?Color.rgb(255,55,75):Color.rgb(132,18,35);
        }else{
            buyColor=Color.rgb(32,75,58);
            sellColor=Color.rgb(76,42,48);
        }
        buyLimitBtn.setBackground(entryButtonBg(buyColor,buyActive,10));
        sellLimitBtn.setBackground(entryButtonBg(sellColor,sellActive,10));
        buyLimitBtn.setText(buyActive?"● BUY LIMIT":"○ BUY LIMIT");
        sellLimitBtn.setText(sellActive?"● SELL LIMIT":"○ SELL LIMIT");
        buyLimitBtn.setTextColor(buyActive||!entryReady?Color.WHITE:Color.rgb(158,172,181));
        sellLimitBtn.setTextColor(sellActive||!entryReady?Color.WHITE:Color.rgb(158,172,181));
    }

    private double preferenceDouble(SharedPreferences p,String key,double fallback){
        try{
            String v=p.getString(key,"");
            double n=Double.parseDouble(v);
            return Double.isFinite(n)&&n>0?n:fallback;
        }catch(Exception e){return fallback;}
    }

    private String normalizedStateSide(String side){
        if(side==null)return "";
        if(side.startsWith("BUY"))return "BUY";
        if(side.startsWith("SELL"))return "SELL";
        return side;
    }

    private void smartLogOnce(SharedPreferences p,String key,String message){
        if(!key.equals(p.getString("smart_last_log_key",""))){
            AppLog.add(this,"SMART LIMIT",message);
            p.edit().putString("smart_last_log_key",key).apply();
        }
    }

    private boolean m5DoesNotOppose(String side,String bias){
        if("BUY".equals(side))return !"SELL".equals(bias);
        if("SELL".equals(side))return !"BUY".equals(bias);
        return false;
    }

    private String makeSignalKey(SignalResult s,long candleStamp){
        return selectedMarket+"|"+s.side+"|"+s.m5Bias+"|"+s.setup+"|"+candleStamp;
    }

    private boolean queueAutoSignal(SignalResult s,Candle[] m1,SharedPreferences gp,
                                    double lot,long candleStamp,String signalKey){
        double target=validAutoTarget(s);
        if(target<=0)return false;
        String orderSide="BUY".equals(s.side)?"BUY_LIMIT":"SELL_LIMIT";
        boolean queued=queueLimit(orderSide,lot,s.entry,s.sl,target);
        if(!queued)return false;
        String orderId=gp.getString("order_status_id","");
        long now=System.currentTimeMillis();
        gp.edit().putString("auto_last_signal_key",signalKey)
            .putLong("auto_last_order_ms",now)
            .putString("smart_phase","QUEUED")
            .putString("smart_active_id",orderId)
            .putString("smart_active_side",s.side)
            .putString("smart_active_symbol",mt5SymbolForMarket(selectedMarket))
            .putString("smart_active_entry",Double.toString(s.entry))
            .putString("smart_active_ticket","")
            .putLong("smart_active_start_candle_ms",candleStamp)
            .putLong("smart_active_queue_ms",now)
            .putBoolean("smart_reentry_allowed",false)
            .remove("smart_cancel_command_id")
            .apply();
        AppLog.add(this,"AUTO LIMIT","queued "+signalKey+"; lifecycle locked pending EA state");
        return true;
    }

    private void processAutoLimit(SignalResult s,Candle[] m1,Candle[] m5,
                                  double bid,double ask,long candleStamp){
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        if(!gp.getBoolean("auto",false)
                ||!"READY".equals(gp.getString("operation_mode","REST")))return;
        if(!"M1".equals(selectedTf)||m1==null||m1.length<30||m5==null||m5.length<30)return;

        long now=System.currentTimeMillis();
        boolean eaOnline=GatewayService.isEaRecentlyOnline(this);
        long stateAt=gp.getLong("ea_order_state_at",0L);
        String remoteState=gp.getString("ea_order_state","UNKNOWN");
        String remoteId=gp.getString("ea_order_state_id","");
        boolean stateFresh=stateAt>0L&&now>=stateAt&&now-stateAt<=8000L&&eaOnline;
        if(!eaOnline){
            smartLogOnce(gp,"EA_OFFLINE","blocked: EA MT5 tidak polling aktif; tidak antre/re-entry");
            return;
        }
        if(!stateFresh){
            smartLogOnce(gp,"STATE_STALE","blocked: status order EA belum segar; aman menunggu, bukan membuat order baru");
            return;
        }

        String phase=gp.getString("smart_phase","IDLE");
        String activeId=gp.getString("smart_active_id","");
        String activeSide=gp.getString("smart_active_side","");
        double activeEntry=preferenceDouble(gp,"smart_active_entry",0.0);
        String activeSymbol=gp.getString("smart_active_symbol",mt5SymbolForMarket(selectedMarket));
        String activeTicket=gp.getString("smart_active_ticket","");
        long startedAt=gp.getLong("smart_active_start_candle_ms",candleStamp);
        String sideNow=normalizedStateSide(activeSide);
        double atr=SmartLimitReentry.atr(m1,14);
        String signalKey=makeSignalKey(s,candleStamp);

        // EA has positively confirmed a pending order or an open position.
        if("PENDING".equals(remoteState)||"POSITION".equals(remoteState)){
            if(activeId.isEmpty()||"IDLE".equals(phase)){
                activeId=remoteId;
                String remoteSide=gp.getString("ea_order_state_side","");
                sideNow=normalizedStateSide(remoteSide);
                activeEntry=preferenceDouble(gp,"ea_order_state_price",0.0);
                activeTicket=gp.getString("ea_order_state_ticket","");
                activeSymbol=gp.getString("ea_order_state_symbol",activeSymbol);
                startedAt=candleStamp; // restore conservatively after app restart
                // An order discovered without its original APK signal levels is locked,
                // because broker-mapped price may differ from the signal-feed entry.
                phase="POSITION".equals(remoteState)?"POSITION":"LOCKED_UNCERTAIN";
                gp.edit().putString("smart_active_id",activeId)
                    .putString("smart_active_side",sideNow)
                    .putString("smart_active_symbol",activeSymbol)
                    .putString("smart_active_entry",Double.toString(activeEntry))
                    .putString("smart_active_ticket",activeTicket)
                    .putLong("smart_active_start_candle_ms",startedAt)
                    .putString("smart_phase",phase).apply();
                AppLog.add(this,"SMART LIMIT","recovered EA state "+remoteState+" id="+activeId+
                    ("LOCKED_UNCERTAIN".equals(phase)?" • conservatively locked; original feed entry unavailable":""));
            }else if(!activeId.equals(remoteId)){
                smartLogOnce(gp,"STATE_ID_MISMATCH|"+activeId+"|"+remoteId,
                    "blocked: EA active order id berbeda dari antrean APK; cek MT5 Trade/Experts sebelum melanjutkan");
                return;
            }

            if("POSITION".equals(remoteState)){
                if(!"POSITION".equals(phase)){
                    gp.edit().putString("smart_phase","POSITION").apply();
                    AppLog.add(this,"SMART LIMIT","position detected; AUTO locked until EA reports no position");
                }
                return;
            }

            // A pending order can be cancelled only after the exact order ticket and
            // original R4-id are confirmed by the EA state heartbeat.
            if("CANCEL_REQUESTED".equals(phase)){
                String cancelId=gp.getString("smart_cancel_command_id","");
                if(cancelId.equals(gp.getString("order_status_id",""))){
                    String cancelResult=gp.getString("order_status","UNKNOWN");
                    if("CANCEL_FAILED".equals(cancelResult)||"FAILED".equals(cancelResult)
                            ||"NOT_DEMO".equals(cancelResult)||"EXPIRED".equals(cancelResult)
                            ||"REJECT".equals(cancelResult)){
                        gp.edit().putString("smart_phase","ACTIVE")
                            .putLong("smart_cancel_retry_after_ms",now+60000L)
                            .putBoolean("smart_reentry_allowed",false).apply();
                        AppLog.add(this,"SMART LIMIT","cancel ACK failed while order remains pending; retaining original and pausing retry");
                    }
                }
                return;
            }
            if("LOCKED_UNCERTAIN".equals(phase))return;
            if("QUEUED".equals(phase)){
                gp.edit().putString("smart_phase","ACTIVE").apply();
                phase="ACTIVE";
            }
            if(!"ACTIVE".equals(phase)){
                gp.edit().putString("smart_phase","ACTIVE").apply();
                phase="ACTIVE";
            }
            activeTicket=gp.getString("ea_order_state_ticket",activeTicket);
            // Keep the original Biquote signal entry for movement tests; the EA's
            // state price is broker-mapped and is not an interchangeable reference.
            activeSide=gp.getString("ea_order_state_side",activeSide);
            sideNow=normalizedStateSide(activeSide);
            activeSymbol=gp.getString("ea_order_state_symbol",activeSymbol);
            if(!activeSymbol.equals(mt5SymbolForMarket(selectedMarket))){
                smartLogOnce(gp,"SYMBOL_MISMATCH|"+activeSymbol+"|"+selectedMarket,
                    "blocked: pending order milik simbol lain; tidak membatalkan atau membuat order pada market yang berbeda");
                return;
            }
            gp.edit().putString("smart_active_ticket",activeTicket)
                .putString("smart_active_entry",Double.toString(activeEntry))
                .putString("smart_active_side",sideNow)
                .putString("smart_active_symbol",activeSymbol).apply();

            if(!gp.getBoolean("smart_reentry_enabled",true))return;
            int elapsed=SmartLimitReentry.elapsedM1Bars(startedAt,candleStamp);
            boolean movedAway=SmartLimitReentry.movedAwayInFavor(sideNow,activeEntry,bid,ask,atr);
            boolean continuation=SmartLimitReentry.hasContinuation(m1,sideNow)
                &&m5DoesNotOppose(sideNow,s.m5Bias);
            boolean validFresh=isAutoEntryEligible(s,bid,ask,true)&&s.side.equals(sideNow);
            double maxDistanceAtr=smartReentryMaxDistanceAtr(gp);
            boolean withinMaxDistance=SmartLimitReentry.withinMaxDistanceFromOriginalEntry(
                activeEntry,s.entry,atr,maxDistanceAtr);
            boolean replacement=elapsed>=3&&elapsed<smartReentryMaxAgeBars(gp)
                &&movedAway&&continuation&&validFresh&&withinMaxDistance
                &&SmartLimitReentry.materiallyDifferentEntry(activeEntry,s.entry,atr);
            boolean structureInvalid=("BUY".equals(sideNow)&&("SELL".equals(s.side)||"SELL".equals(s.m5Bias)))
                ||("SELL".equals(sideNow)&&("BUY".equals(s.side)||"BUY".equals(s.m5Bias)));
            boolean staleRun=elapsed>=4&&movedAway;
            boolean maxAge=elapsed>=smartReentryMaxAgeBars(gp);
            long retryAfter=gp.getLong("smart_cancel_retry_after_ms",0L);
            boolean shouldCancel=structureInvalid||replacement||staleRun||maxAge;
            if(shouldCancel&&now>=retryAfter){
                boolean queued=queueCancelPending(activeId,activeTicket,activeSymbol);
                if(queued){
                    String cancelCommandId=gp.getString("order_status_id","");
                    boolean allowReplacement=replacement&&!structureInvalid&&!maxAge;
                    gp.edit().putString("smart_phase","CANCEL_REQUESTED")
                        .putString("smart_cancel_command_id",cancelCommandId)
                        .putBoolean("smart_reentry_allowed",allowReplacement)
                        .putString("smart_cancel_reason",structureInvalid?"M1/M5 structure invalid"
                            :(replacement?"fresh trend pullback setup":"stale LIMIT without valid re-entry"))
                        .apply();
                    AppLog.add(this,"SMART LIMIT","cancel requested id="+activeId+
                        " ageM1="+elapsed+" replacement="+allowReplacement);
                }
            }
            return;
        }

        // Wait for the order queue/ACK to settle. EXECUTED is not enough to conclude
        // an order is still pending; the separate EA state endpoint must identify it.
        if("QUEUED".equals(phase)){
            String orderId=gp.getString("order_status_id","");
            String result=gp.getString("order_status","UNKNOWN");
            if(activeId.equals(orderId)&&"QUEUED".equals(result))return;
            if(activeId.equals(orderId)&&"EXECUTED".equals(result)){
                if(remoteId.equals(activeId)&&"NONE".equals(remoteState)){
                    gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                        .putString("smart_last_log_key","EXECUTED_WITHOUT_ACTIVE_ORDER").apply();
                    AppLog.add(this,"SMART LIMIT","EA reports no pending order/position after EXECUTED; no immediate resend");
                }else if(now-gp.getLong("smart_active_queue_ms",now)>10000L){
                    gp.edit().putString("smart_phase","LOCKED_UNCERTAIN").apply();
                    AppLog.add(this,"SMART LIMIT","execution acknowledged but state is inconclusive; AUTO locked to prevent duplicate");
                }
                return;
            }
            if(activeId.equals(orderId)&&("NO_BROKER_CONNECTION".equals(result)||"UNKNOWN".equals(result))){
                gp.edit().putString("smart_phase","LOCKED_UNCERTAIN").apply();
                AppLog.add(this,"SMART LIMIT","ambiguous execution result; locked pending manual MT5 verification");
                return;
            }
            if(activeId.equals(orderId)&&!"QUEUED".equals(result)){
                gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                    .putString("smart_last_log_key","ORDER_REJECTED|"+activeId).apply();
                AppLog.add(this,"SMART LIMIT","initial order rejected: "+result+"; no automatic duplicate retry");
                return;
            }
            return;
        }

        if("LOCKED_UNCERTAIN".equals(phase)){
            // Explicitly fail closed: connection/ACK ambiguity must be checked in MT5.
            return;
        }

        if("CANCEL_REQUESTED".equals(phase)){
            String cancelId=gp.getString("smart_cancel_command_id","");
            String statusId=gp.getString("order_status_id","");
            String result=gp.getString("order_status","UNKNOWN");
            if(!cancelId.isEmpty()&&cancelId.equals(statusId)){
                if("CANCELLED".equals(result)){
                    // Wait for a fresh EA snapshot proving the original ticket disappeared.
                    if(!stateFresh||!activeId.equals(remoteId)||!"NONE".equals(remoteState))return;
                    boolean allow=gp.getBoolean("smart_reentry_allowed",false);
                    boolean candidate=allow&&isAutoEntryEligible(s,bid,ask,true)
                        &&s.side.equals(sideNow)
                        &&m5DoesNotOppose(sideNow,s.m5Bias)
                        &&SmartLimitReentry.hasContinuation(m1,sideNow)
                        &&SmartLimitReentry.elapsedM1Bars(startedAt,candleStamp)
                            <smartReentryMaxAgeBars(gp)
                        &&SmartLimitReentry.movedAwayInFavor(sideNow,activeEntry,bid,ask,atr)
                        &&SmartLimitReentry.withinMaxDistanceFromOriginalEntry(activeEntry,s.entry,atr,
                            smartReentryMaxDistanceAtr(gp))
                        &&SmartLimitReentry.materiallyDifferentEntry(activeEntry,s.entry,atr);
                    if(candidate){
                        String nextKey=makeSignalKey(s,candleStamp);
                        double lot=lotForMarket();
                        if(Double.isFinite(lot)&&lot>=minLotForSymbol(mt5SymbolForMarket(selectedMarket))
                                &&queueAutoSignal(s,m1,gp,lot,candleStamp,nextKey)){
                            AppLog.add(this,"SMART LIMIT","re-entry queued after CANCELLED ACK + EA NONE; side="+sideNow);
                        }else{
                            AppLog.add(this,"SMART LIMIT","old order cancelled; replacement not queued because fresh validation/queue failed");
                            gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                                .putBoolean("smart_reentry_allowed",false).apply();
                        }
                    }else{
                        AppLog.add(this,"SMART LIMIT","old order confirmed cancelled; fresh replacement conditions no longer valid; waiting");
                        gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                            .putBoolean("smart_reentry_allowed",false)
                            .putString("smart_blocked_signal_key",signalKey).apply();
                    }
                    return;
                }
                if("CANCEL_FAILED".equals(result)||"FAILED".equals(result)||"NOT_DEMO".equals(result)
                        ||"EXPIRED".equals(result)||"REJECT".equals(result)){
                    if("PENDING".equals(remoteState)&&activeId.equals(remoteId)){
                        gp.edit().putString("smart_phase","ACTIVE")
                            .putLong("smart_cancel_retry_after_ms",now+60000L)
                            .putBoolean("smart_reentry_allowed",false).apply();
                        AppLog.add(this,"SMART LIMIT","cancel not confirmed; retain old order state and pause retry for 60s");
                    }else if("POSITION".equals(remoteState)){
                        gp.edit().putString("smart_phase","POSITION").apply();
                        AppLog.add(this,"SMART LIMIT","cancel race resolved as POSITION; replacement prohibited");
                    }else{
                        gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                            .putBoolean("smart_reentry_allowed",false).apply();
                        AppLog.add(this,"SMART LIMIT","cancel outcome inconclusive; active state cleared only after EA reports no active exposure");
                    }
                    return;
                }
            }
            return;
        }

        if("POSITION".equals(phase)){
            // A closed position never triggers a replacement for that same setup.
            if("NONE".equals(remoteState)){
                gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                    .putBoolean("smart_reentry_allowed",false).apply();
                AppLog.add(this,"SMART LIMIT","position lifecycle ended; waiting for a new signal");
            }
            return;
        }

        if("ACTIVE".equals(phase)){
            // Pending/position vanished without our confirmed cancel ACK (e.g. fill,
            // TP/SL, manual cancel). Never infer "unfilled"; do not re-enter this setup.
            if("NONE".equals(remoteState)){
                gp.edit().putString("smart_phase","IDLE").remove("smart_active_id")
                    .putBoolean("smart_reentry_allowed",false).apply();
                AppLog.add(this,"SMART LIMIT","active order ended outside managed cancel; no re-entry for old order");
            }
            return;
        }

        // Fail closed if the EA snapshot is not a verified flat state.
        if(!"NONE".equals(remoteState))return;
        if(!"NONE".equals(remoteId)&&remoteId.isEmpty())return;
        if(!queueIsFree(gp))return;

        boolean m1Context="M1".equals(selectedTf)&&m1.length>=30&&m5.length>=30;
        if(!isAutoEntryEligible(s,bid,ask,m1Context))return;
        double lot=lotForMarket();
        double minLot=minLotForSymbol(mt5SymbolForMarket(selectedMarket));
        if(!Double.isFinite(lot)||lot<=0||lot+1e-8<minLot){
            smartLogOnce(gp,"LOT_INVALID|"+selectedMarket,"blocked: lot "+lotText(lot)+" di bawah minimum "+lotText(minLot));
            return;
        }
        if(signalKey.equals(gp.getString("auto_last_signal_key",""))
                ||signalKey.equals(gp.getString("smart_blocked_signal_key",""))){
            smartLogOnce(gp,"DUPLICATE_SIGNAL|"+signalKey,"blocked: sinyal/Setup ini sudah diproses; menunggu candle/setup baru");
            return;
        }
        long lastAuto=gp.getLong("auto_last_order_ms",0L);
        if(now-lastAuto<60000L){
            smartLogOnce(gp,"COOLDOWN|"+signalKey,"blocked: cooldown 60 detik setelah command terakhir");
            return;
        }
        double target=validAutoTarget(s);
        if(target<=0)return;
        if(!queueAutoSignal(s,m1,gp,lot,candleStamp,signalKey)){
            smartLogOnce(gp,"QUEUE_REJECTED|"+signalKey,"queue rejected; no immediate automatic resend");
        }else{
            gp.edit().remove("smart_last_log_key").apply();
        }
    }

    private boolean queueIsFree(SharedPreferences p){
        String queue=p.getString("next","");
        return queue==null||queue.trim().isEmpty();
    }

    void updateSignal(Candle[] m1,Candle[] m5){
        if(m1==null||m1.length<30||m5==null||m5.length<30){
            if(scoreView!=null)scoreView.setText("SCORE 0%");
            resetEntryReadyUi("menunggu data M1/M5");
            return;
        }
        double bid=chart==null?0:chart.getBid(),ask=chart==null?0:chart.getAsk();
        SignalResult previous=lastSignal;lastSignal=SignalEngine.evaluate(m1,m5,bid,ask);SignalResult s=lastSignal;

        int displayScore=Math.max(0,Math.min(100,s.score));
        scoreView.setText("SCORE "+displayScore+"%");
        scoreView.setTextSize(11);
        scoreView.setSingleLine(true);
        scoreView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        scoreView.setIncludeFontPadding(false);
        scoreView.setGravity(Gravity.CENTER);
        scoreView.setTextColor(displayScore>=75?Color.YELLOW:displayScore>=60?Color.CYAN:Color.WHITE);
        signalSummary.setText(("BUY".equals(s.side)||"SELL".equals(s.side)?("M1 "+s.side+" • M5 "+s.m5Bias):("Menunggu wick valid"))+"\n"+s.setup+" • CONF "+s.confidence+"/5");
        entryView.setText("Entry\n"+price(s.entry));slView.setText("SL\n"+price(s.sl));tp1View.setText("TP1\n"+price(s.tp1));tp2View.setText("TP2\n"+price(s.tp2));
        updateEntryReadyUi(s,bid,ask);

        View v=((ViewGroup)scoreView.getParent()).getChildAt(0);
        if(v instanceof Button){
            Button b=(Button)v;
            b.setText("");
            b.setTextSize(1);
            b.setBackground(roundBg("BUY".equals(s.side)?Color.rgb(0,225,135):"SELL".equals(s.side)?Color.rgb(255,65,82):Color.rgb(110,115,125),1000));
        }
        chart.setSignalLevels(s.entry,s.sl,s.tp1,s.tp2,"BUY".equals(s.side)||"SELL".equals(s.side));

        boolean changed=previous==null||!s.side.equals(previous.side);
        long candleStamp=m1[m1.length-1].t;
        String candleKey=selectedMarket+"|"+selectedTf+"|"+candleStamp;
        SharedPreferences noticePrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        boolean alreadyNotified=candleKey.equals(noticePrefs.getString("signal_notified_candle",""));
        // The lifecycle manager owns one Rayyan LIMIT/position at a time. It
        // queues, cancels, and re-enters only after fresh EA state plus matching ACK.
        processAutoLimit(s,m1,m5,bid,ask,candleStamp);
        if(changed&&("BUY".equals(s.side)||"SELL".equals(s.side))){
            String msg=selectedMarket+" SCALPING\n\n"+s.side+"\nEntry: "+price(s.entry)+"\nSL: "+price(s.sl)+"\nTP1: "+price(s.tp1)+"\nTP2: "+price(s.tp2)+"\nTF: M1\nBias: M5 "+s.m5Bias+"\nSetup: "+s.setup+"\nConfidence: "+s.confidence+"/5";
            AppLog.add(this,"SIGNAL",s.side+" "+price(s.entry)+" score="+s.score);
            if(!alreadyNotified){
                noticePrefs.edit().putString("signal_notified_candle",candleKey).apply();
                if(GatewayService.isEaRecentlyOnline(this)){
                    if(telegramEnabled)sendTelegram(msg);
                    notifyLocal(selectedMarket+" SIGNAL "+s.side,"Maksimal satu notifikasi per candle • Entry "+price(s.entry)+" | SL "+price(s.sl)+" | TP1 "+price(s.tp1));
                }else{
                    AppLog.add(this,"SIGNAL","notification suppressed: EA offline; signal is not an MT5 order");
                }
            }
        }
    }

    String stars(int n){StringBuilder s=new StringBuilder();for(int i=0;i<5;i++)s.append(i<n?"★":"☆");return s.toString();}
    String price(double v){return v>0?String.format(Locale.US,"%.2f",v):"--";}

    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=getPackageManager().PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1001);
    }

    @Override protected void onResume(){ super.onResume(); visible=true; }
    @Override protected void onPause(){ visible=false; super.onPause(); }

    void showMenu(View anchor){
        RayyanMenuLayout.showMain(this,selectedMarket,selectedTf,this::handleMenuAction);
    }

    private int getMinimumEntryConfidence(){ return getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).getInt("min_entry_score",60); }

    private void showConfidenceDialog(){
        int current=getMinimumEntryConfidence();
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24,18,24,18); box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("ENTRY CONFIDENCE",18); title.setTextColor(Color.CYAN); title.setTypeface(null,Typeface.BOLD); box.addView(title,new LinearLayout.LayoutParams(-1,42));
        TextView note=tv("Pilih ambang confidence minimum",12); note.setTextColor(Color.LTGRAY); box.addView(note,new LinearLayout.LayoutParams(-1,34));
        LinearLayout choices=new LinearLayout(this); choices.setOrientation(LinearLayout.VERTICAL);
        for(int value:new int[]{60,70,80,90}){ Button option=btn((current==value?"✓  ":"")+"CONFIDENCE "+value+"%"); option.setTextSize(14); option.setBackground(roundBg(current==value?Color.rgb(0,105,83):Color.rgb(7,34,60),12)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,58);p.setMargins(0,6,0,6);choices.addView(option,p);option.setOnClickListener(v->{getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putInt("min_entry_score",value).apply();Toast.makeText(this,"Confidence minimum "+value+"%",Toast.LENGTH_SHORT).show();if(lastSignal!=null)updateEntryReadyUi(lastSignal,chart.getBid(),chart.getAsk());((Dialog)box.getTag()).dismiss();}); }
        box.addView(choices); AlertDialog d=new AlertDialog.Builder(this).setView(box).setNegativeButton("Tutup",null).create();box.setTag(d);d.setOnShowListener(x->{if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-40,420),-2);}});d.show();if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-40,420),-2);}
    }

    private void showSettingsPanel(View anchor){
        RayyanMenuLayout.showSettings(this,selectedMarket,selectedTf,this::handleMenuAction);
    }

    private void showStrategyDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(18),uiDp(16),uiDp(18),uiDp(12));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("SCALPING STRATEGY",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(40)));
        TextView note=tv("Panduan baca-sinyal • bukan jaminan hasil",12);note.setTextColor(Color.LTGRAY);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(36)));
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        addPanelRow(rows,"01 • MARKET BIAS","Gunakan M5 untuk arah konteks, lalu cari pemicu masuk pada M1.",Color.WHITE);
        addPanelRow(rows,"02 • WICK REJECTION","Cari ekor candle yang menolak level; jangan masuk hanya karena candle tampak panjang.",Color.WHITE);
        addPanelRow(rows,"03 • LIQUIDITY SWEEP","Perhatikan sapuan swing high/low dan reaksi harga sesudahnya.",Color.WHITE);
        addPanelRow(rows,"04 • BOS","Tunggu struktur terkonfirmasi; BOS membantu validasi, bukan sinyal tunggal.",Color.WHITE);
        addPanelRow(rows,"05 • FILTER","EMA, RSI, MACD, ATR, dan spread membantu konteks; periksa spread/feed sebelum keputusan.",Color.WHITE);
        addPanelRow(rows,"06 • ENTRY PLAN","Tinjau Entry, SL, TP1, TP2, dan Risk:Reward sebelum LIMIT demo.",Color.CYAN);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);sc.addView(rows);
        box.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setPositiveButton("TUTUP",null).create();
        showSizedDialog(d,0.80f,450);
    }

    private void showRiskManagementDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(18),uiDp(16),uiDp(18),uiDp(12));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("RISK MANAGEMENT",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(40)));
        TextView note=tv("Aturan kendali risiko sebelum mengirim order",12);note.setTextColor(Color.LTGRAY);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(36)));
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        addPanelRow(rows,"AKUN DEMO","Gunakan demo untuk verifikasi alur APK → Gateway → EA/MT5; hasil demo tidak menjamin hasil live.",Color.WHITE);
        addPanelRow(rows,"UKURAN LOT","Mulai dari lot minimum yang valid untuk simbol pada akun. Jangan memakai lot default tanpa memeriksa spesifikasi simbol.",Color.WHITE);
        addPanelRow(rows,"STOP LOSS","Periksa SL sebelum konfirmasi. Jangan mengirim order dengan SL yang salah atau level tidak valid.",Color.WHITE);
        addPanelRow(rows,"TARGET","Tinjau TP1/TP2 dan Risk:Reward; level sinyal dapat berubah mengikuti data baru.",Color.WHITE);
        addPanelRow(rows,"KONDISI KONEKSI","Jangan kirim ulang ketika ACK belum jelas. Periksa status order di MT5 Trade/Experts dan antrean Gateway dahulu.",Color.rgb(255,205,95));
        addPanelRow(rows,"BATASAN","Ini pengingat proses, bukan perhitungan lot otomatis atau nasihat keuangan.",Color.LTGRAY);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);sc.addView(rows);
        box.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setPositiveButton("TUTUP",null).create();
        showSizedDialog(d,0.80f,450);
    }

    private void handleMenuAction(String action){
        RayyanMenuLayout.dismissActive();
        if("confidence".equals(action))showConfidenceDialog();
        else if("market".equals(action)||"settings_market".equals(action))showMarketDialog();
        else if("chart".equals(action)){if(chart!=null)chart.goLive();}
        else if("indicators".equals(action)||"settings_indicators".equals(action))showIndicatorDialog();
        else if("signal".equals(action))showSignalDialog();
        else if("gateway".equals(action)||"settings_gateway".equals(action))showGatewayAckDiagnostics();
        else if("orders".equals(action)||"settings_logs".equals(action))showLogDialog("ORDERS / SYSTEM LOG");
        else if("mt5".equals(action)||"settings_mt5".equals(action))showMt5Dialog();
        else if("telegram".equals(action)||"settings_telegram".equals(action))showTelegramDialog();
        else if("settings".equals(action))showSettingsPanel(null);
        else if("strategy".equals(action))showStrategyDialog();
        else if("risk".equals(action))showRiskManagementDialog();
    }

    private int uiDp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private void addPanelRow(LinearLayout parent,String label,String value,int valueColor){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(uiDp(12),uiDp(7),uiDp(12),uiDp(7));
        card.setBackground(roundBg(Color.rgb(7,30,47),12));
        TextView key=tv(label,11);key.setTextColor(Color.CYAN);key.setTypeface(null,Typeface.BOLD);
        card.addView(key,new LinearLayout.LayoutParams(-1,uiDp(20)));
        TextView val=tv(value==null?"—":value,14);val.setTextColor(valueColor);val.setGravity(Gravity.TOP|Gravity.START);
        val.setPadding(uiDp(8),uiDp(2),uiDp(8),uiDp(3));val.setTextIsSelectable(true);
        card.addView(val,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,0,0,uiDp(7));parent.addView(card,params);
        card.setMinimumHeight(uiDp(56));
    }

    private void styleDialogInput(EditText field){
        field.setTextColor(Color.WHITE);field.setHintTextColor(Color.rgb(150,175,190));
        field.setTextSize(16);field.setSingleLine(true);field.setPadding(uiDp(14),uiDp(8),uiDp(14),uiDp(8));
        field.setBackground(roundBg(Color.rgb(7,30,47),12));
        field.setMinHeight(uiDp(54));field.setMinimumHeight(uiDp(54));
    }

    private void showSizedDialog(AlertDialog dialog,float heightFraction,int widthDp){
        dialog.setOnShowListener(ignored->{
            resizeDialogButtons(dialog);
            if(dialog.getWindow()!=null){
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                int sw=getResources().getDisplayMetrics().widthPixels;
                int sh=getResources().getDisplayMetrics().heightPixels;
                int width=Math.min(sw-uiDp(32),uiDp(widthDp));
                int height=Math.min((int)(sh*heightFraction),Math.min(uiDp(780),sh-uiDp(72)));
                dialog.getWindow().setLayout(width,height);
            }
        });
        dialog.show();
        resizeDialogButtons(dialog);
        if(dialog.getWindow()!=null){
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int sw=getResources().getDisplayMetrics().widthPixels;
            int sh=getResources().getDisplayMetrics().heightPixels;
            int width=Math.min(sw-uiDp(32),uiDp(widthDp));
            int height=Math.min((int)(sh*heightFraction),Math.min(uiDp(780),sh-uiDp(72)));
            dialog.getWindow().setLayout(width,height);
        }
    }

    private void resizeDialogButtons(AlertDialog dialog){
        int[] ids={AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL};
        for(int id:ids){
            Button button=dialog.getButton(id);
            if(button!=null){
                button.setMinHeight(uiDp(50));button.setMinimumHeight(uiDp(50));
                button.setTextSize(13);button.setPadding(uiDp(10),uiDp(6),uiDp(10),uiDp(6));
            }
        }
    }

    void showGatewayAckDiagnostics(){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        long now=System.currentTimeMillis();
        long lastPoll=p.getLong("ea_last_poll_ms",0L);
        long age=lastPoll>0L?Math.max(0L,(now-lastPoll)/1000L):-1L;
        String orderId=p.getString("order_status_id","");
        String orderStatus=p.getString("order_status","NONE");
        String detail=p.getString("order_status_detail","Belum ada hasil order/ACK tersimpan");
        String queue=p.getString("next","");
        boolean eaOnline=lastPoll>0L && now-lastPoll<=90000L;
        SharedPreferences crash=getSharedPreferences("rayyan4_crashlog",MODE_PRIVATE);
        String action=crash.getString("last_action","tidak terekam");
        String actionTime=crash.getString("last_action_time","");
        String crashTime=crash.getString("last_crash_time","tidak ada crash Java tercatat");
        String crashThread=crash.getString("last_crash_thread","");
        String crashStack=crash.getString("last_crash_stack","");
        if(crashStack.length()>6000)crashStack=crashStack.substring(0,6000)+"\\n...[stack dipotong]";

        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(18),uiDp(16),uiDp(18),uiDp(12));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("GATEWAY / QUEUE / ACK",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(38)));
        TextView note=tv("Diagnostik baca-saja • tidak mengirim order tes",12);note.setTextColor(Color.LTGRAY);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(38)));

        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        addPanelRow(rows,"GATEWAY","Port 8787 • status layanan diperiksa terpisah",Color.WHITE);
        addPanelRow(rows,"EA POLLING",(eaOnline?"PASS":"BELUM TERDETEKSI")+(age>=0?" • poll "+age+" dtk lalu":""),eaOnline?green:Color.rgb(255,190,70));
        addPanelRow(rows,"QUEUE",queue==null||queue.trim().isEmpty()?"KOSONG":"ADA PERINTAH MENUNGGU",(queue==null||queue.trim().isEmpty())?green:Color.rgb(255,190,70));
        addPanelRow(rows,"ACK / ORDER TERAKHIR",orderStatus+(orderId.isEmpty()?"":" • ID "+orderId),Color.CYAN);
        if(detail!=null&&!detail.isEmpty())addPanelRow(rows,"DETAIL ACK",detail,Color.WHITE);
        addPanelRow(rows,"AKSI TERAKHIR",action+(actionTime.isEmpty()?"":" • "+actionTime),Color.WHITE);
        addPanelRow(rows,"CRASH JAVA TERAKHIR",crashTime+(crashThread.isEmpty()?"":" • thread "+crashThread),crashTime.startsWith("tidak ada")?green:Color.rgb(255,190,70));
        if(!crashStack.isEmpty()){
            TextView stackLabel=tv("STACK TRACE",12);stackLabel.setTextColor(Color.CYAN);stackLabel.setTypeface(null,Typeface.BOLD);
            rows.addView(stackLabel,new LinearLayout.LayoutParams(-1,uiDp(30)));
            TextView stack=tv(crashStack,12);stack.setGravity(Gravity.TOP|Gravity.START);stack.setTextIsSelectable(true);
            stack.setPadding(uiDp(12),uiDp(10),uiDp(12),uiDp(10));stack.setBackground(roundBg(Color.rgb(6,25,40),10));
            rows.addView(stack,new LinearLayout.LayoutParams(-1,-2));
        }
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);sc.addView(rows);
        box.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        AlertDialog d=new AlertDialog.Builder(this).setView(box)
            .setPositiveButton("PERIKSA LAGI",(dialog,which)->showGatewayAckDiagnostics())
            .setNegativeButton("TUTUP",null).create();
        showSizedDialog(d,0.80f,450);
        AppLog.add(this,"DIAGNOSTIC","Gateway/ACK status checked; EA online="+eaOnline+" ageSec="+age+" order="+orderStatus);
    }
    String marketDescription(String market){
        if("BTCUSD".equals(market))return "Bitcoin / US Dollar";
        if("ETHUSD".equals(market))return "Ethereum / US Dollar";
        return "Gold Spot / US Dollar";
    }

    void showMarketDialog(){
        final String[] markets={"XAUUSD","BTCUSD","ETHUSD"};
        final String[] icons={"◈","₿","◆"};
        final String[] details={"Gold Spot / US Dollar","Bitcoin / US Dollar","Ethereum / US Dollar"};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(22,16,22,14);box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("SELECT MARKET",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);box.addView(title,new LinearLayout.LayoutParams(-1,40));
        TextView note=tv("Pilih instrumen untuk chart dan signal engine",12);note.setTextColor(Color.LTGRAY);box.addView(note,new LinearLayout.LayoutParams(-1,36));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setNegativeButton("TUTUP",null).create();
        for(int i=0;i<markets.length;i++){final String market=markets[i];boolean selected=market.equals(selectedMarket);
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(12,8,12,8);row.setBackground(roundBg(selected?Color.rgb(5,65,58):Color.rgb(7,30,47),12));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,68);rp.setMargins(0,6,0,6);box.addView(row,rp);
            TextView icon=tv(icons[i],22);icon.setTextColor(Color.rgb(0,225,180));icon.setGravity(Gravity.CENTER);row.addView(icon,new LinearLayout.LayoutParams(42,-1));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(Gravity.CENTER_VERTICAL);TextView name=tv(market,16);name.setTextColor(Color.WHITE);name.setTypeface(null,Typeface.BOLD);labels.addView(name);TextView sub=tv(details[i],12);sub.setTextColor(Color.LTGRAY);labels.addView(sub);row.addView(labels,new LinearLayout.LayoutParams(0,-1,1));
            TextView mark=tv(selected?"✓ ACTIVE":"SELECT  ›",10);mark.setTextColor(selected?Color.rgb(0,235,160):Color.CYAN);row.addView(mark);
            row.setOnClickListener(v->{if(market.equals(selectedMarket)){d.dismiss();return;}selectedMarket=market;lastHeaderPrice=Double.NaN;if(priceView!=null){priceView.setText("--");priceView.setTextColor(Color.CYAN);}prefs.edit().putString("market",selectedMarket).apply();if(marketTitle!=null)marketTitle.setText(selectedMarket);if(marketSubtitle!=null)marketSubtitle.setText(marketDescription(selectedMarket));if(chart!=null)chart.setMarket(selectedMarket);if(signalSummary!=null)signalSummary.setText(selectedMarket+" • "+selectedTf+"\nMenunggu data market...");if(status!=null)status.setText("● MENGHUBUNGKAN • "+selectedMarket);lastSignal=null;resetEntryReadyUi("menunggu data market");d.dismiss();load(interval(selectedTf),selectedTf);AppLog.add(this,"MARKET","selected "+selectedMarket+" (APK feed only; MT5 symbol mapping deferred)");});
        }
        d.show();if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-36,430),-2);}
    }
    void showSignalDialog(){
        if(lastSignal==null){Toast.makeText(this,"Menunggu data Signal Engine...",Toast.LENGTH_SHORT).show();return;}
        SignalResult s=lastSignal;
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(18),uiDp(16),uiDp(18),uiDp(12));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("SIGNAL ENGINE",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(38)));
        TextView note=tv(selectedMarket+" • M5 bias + M1 entry",12);note.setTextColor(Color.LTGRAY);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(34)));
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        addPanelRow(rows,"MARKET",selectedMarket,Color.WHITE);
        addPanelRow(rows,"SIDE",s.side,("BUY".equals(s.side)?green:("SELL".equals(s.side)?red:Color.rgb(255,190,70))));
        addPanelRow(rows,"M5 BIAS",String.valueOf(s.m5Bias),Color.CYAN);
        addPanelRow(rows,"M1 ENTRY",String.valueOf(s.entry),Color.WHITE);
        addPanelRow(rows,"STOP LOSS (SL)",String.valueOf(s.sl),red);
        addPanelRow(rows,"TAKE PROFIT 1 (TP1)",String.valueOf(s.tp1),green);
        addPanelRow(rows,"TAKE PROFIT 2 (TP2)",String.valueOf(s.tp2),green);
        addPanelRow(rows,"RISK : REWARD",String.format(Locale.US,"%.2f",s.rr),Color.WHITE);
        addPanelRow(rows,"CONFIDENCE",s.confidence+"/5  •  ENTRY SCORE "+s.score+"%",Color.CYAN);
        addPanelRow(rows,"LIQUIDITY SWEEP",onoff(s.liquiditySweep),s.liquiditySweep?green:Color.LTGRAY);
        addPanelRow(rows,"WICK REJECTION",onoff(s.wickRejection),s.wickRejection?green:Color.LTGRAY);
        addPanelRow(rows,"BOS",onoff(s.bos),s.bos?green:Color.LTGRAY);
        addPanelRow(rows,"EMA FILTER",onoff(s.emaFilter),s.emaFilter?green:Color.LTGRAY);
        addPanelRow(rows,"RSI FILTER",onoff(s.rsiFilter),s.rsiFilter?green:Color.LTGRAY);
        addPanelRow(rows,"MACD FILTER",onoff(s.macdFilter),s.macdFilter?green:Color.LTGRAY);
        addPanelRow(rows,"ATR FILTER",onoff(s.atrFilter),s.atrFilter?green:Color.LTGRAY);
        addPanelRow(rows,"SPREAD FILTER",onoff(s.spreadFilter),s.spreadFilter?green:Color.LTGRAY);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);sc.addView(rows);
        box.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setPositiveButton("TUTUP",null).create();
        showSizedDialog(d,0.82f,450);
    }

    String onoff(boolean v){return v?"ON":"OFF";}
    boolean ind(String key,boolean def){return prefs.getBoolean(key,def);}

    void loadIndicatorSettings(){
        chart.setShowEMA9(ind("ema9",true));chart.setShowEMA21(ind("ema21",true));chart.setShowEMA50(ind("ema50",true));
        chart.setShowSR(ind("sr",true));chart.setShowFibo(ind("fibo",true));chart.setShowRSI(ind("rsi",true));chart.setShowMACD(ind("macd",true));chart.setShowATR(ind("atr",true));
        chart.setShowSignals(ind("signals",true));chart.setShowWickBos(ind("wickbos",true));chart.setShowTradeLevels(ind("orders",true));chart.setShowPosition(ind("position",true));chart.setShowBidAsk(ind("bidask",true));
    }

    void setInd(String key,boolean enabled){
        prefs.edit().putBoolean(key,enabled).apply();
        switch(key){
            case "ema9":chart.setShowEMA9(enabled);break;case "ema21":chart.setShowEMA21(enabled);break;case "ema50":chart.setShowEMA50(enabled);break;
            case "sr":chart.setShowSR(enabled);break;case "fibo":chart.setShowFibo(enabled);break;case "rsi":chart.setShowRSI(enabled);break;case "macd":chart.setShowMACD(enabled);break;case "atr":chart.setShowATR(enabled);break;
            case "signals":chart.setShowSignals(enabled);break;case "wickbos":chart.setShowWickBos(enabled);break;case "orders":chart.setShowTradeLevels(enabled);break;case "position":chart.setShowPosition(enabled);break;case "bidask":chart.setShowBidAsk(enabled);break;
        }
    }

    void bindQuickLayer(Button b,String label){
        b.setOnClickListener(v->{if(label.contains("S/R"))setInd("sr",!ind("sr",true));else if(label.contains("Fibo"))setInd("fibo",!ind("fibo",true));
            else if(label.contains("EMA")){boolean next=!(ind("ema9",true)&&ind("ema21",true)&&ind("ema50",true));setInd("ema9",next);setInd("ema21",next);setInd("ema50",next);}
            else if(label.contains("Buy/Sell"))setInd("signals",!ind("signals",true));else if(label.contains("Order"))setInd("orders",!ind("orders",true));else if(label.contains("Posisi"))setInd("position",!ind("position",true));updateQuickLayerButton(b,label);});
        updateQuickLayerButton(b,label);
    }

    void updateQuickLayerButton(Button b,String label){
        boolean on;if(label.contains("S/R"))on=ind("sr",true);else if(label.contains("Fibo"))on=ind("fibo",true);else if(label.contains("EMA"))on=ind("ema9",true)||ind("ema21",true)||ind("ema50",true);else if(label.contains("Buy/Sell"))on=ind("signals",true);else if(label.contains("Order"))on=ind("orders",true);else on=ind("position",true);
        b.setTextColor(on?green:Color.LTGRAY);b.setBackground(roundBg(on?Color.rgb(8,65,48):Color.rgb(7,34,60),12));
    }

    void showIndicatorDialog(){
        final String[] names={"EMA 9","EMA 21","EMA 50","Support / Resistance","Fibonacci","RSI 14","MACD","ATR","Buy / Sell","Wick / Sweep / BOS","Bid / Ask","Entry / SL / TP"};
        final String[] keys={"ema9","ema21","ema50","sr","fibo","rsi","macd","atr","signals","wickbos","bidask","orders"};
        final String[] icons={"〽","〽","〽","⌗","⟋","◌","∿","↕","↗","〰","⇄","▤"};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(22,16,22,14);box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("CHART LAYERS",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);box.addView(title,new LinearLayout.LayoutParams(-1,40));
        TextView note=tv("Aktifkan layer yang ingin ditampilkan pada chart",12);note.setTextColor(Color.LTGRAY);box.addView(note,new LinearLayout.LayoutParams(-1,38));
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        for(int i=0;i<names.length;i++){final int index=i;boolean enabled=ind(keys[i],true);
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(12,7,12,7);row.setBackground(roundBg(enabled?Color.rgb(5,48,55):Color.rgb(7,30,47),12));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,64);rp.setMargins(0,5,0,5);list.addView(row,rp);
            TextView ico=tv(icons[i],21);ico.setTextColor(Color.rgb(0,225,180));ico.setGravity(Gravity.CENTER);row.addView(ico,new LinearLayout.LayoutParams(38,-1));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(Gravity.CENTER_VERTICAL);TextView nm=tv(names[i],14);nm.setTextColor(Color.WHITE);nm.setTypeface(null,Typeface.BOLD);labels.addView(nm);TextView state=tv(enabled?"TAMPIL • ON":"SEMBUNYI • OFF",11);state.setTextColor(enabled?Color.rgb(0,225,150):Color.LTGRAY);labels.addView(state);row.addView(labels,new LinearLayout.LayoutParams(0,-1,1));
            Switch sw=new Switch(this);sw.setChecked(enabled);row.addView(sw);sw.setOnCheckedChangeListener((button,isChecked)->{setInd(keys[index],isChecked);row.setBackground(roundBg(isChecked?Color.rgb(5,48,55):Color.rgb(7,30,47),12));state.setText(isChecked?"TAMPIL • ON":"SEMBUNYI • OFF");state.setTextColor(isChecked?Color.rgb(0,225,150):Color.LTGRAY);});
            row.setOnClickListener(v->sw.setChecked(!sw.isChecked()));
        }
        box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setPositiveButton("SELESAI",null).create();d.setOnShowListener(v->{if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-36,430),Math.min((int)(getResources().getDisplayMetrics().heightPixels*.82),760));}});d.show();if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-36,430),Math.min((int)(getResources().getDisplayMetrics().heightPixels*.82),760));}
    }

    void sendTelegram(String msg){TelegramClient.send(tgPrefs.getString("token",""),tgPrefs.getString("chat",""),msg,(ok,detail)->AppLog.add(this,"TELEGRAM",ok?"sent":"error"));}

    void notifyLocal(String title,String msg){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);String id="rayyan4_alerts";
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(id,"Rayyan4 Alerts",NotificationManager.IMPORTANCE_HIGH));
        nm.notify((int)(System.currentTimeMillis()&0x7fffffff),new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(msg).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).build());
    }

    void showTelegramDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(20),uiDp(16),uiDp(20),uiDp(14));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("TELEGRAM NOTIFICATIONS",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(40)));
        TextView note=tv("Atur token bot dan Chat ID untuk notifikasi",12);note.setTextColor(Color.LTGRAY);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(38)));

        TextView tokenLabel=tv("BOT TOKEN",12);tokenLabel.setTextColor(Color.CYAN);tokenLabel.setTypeface(null,Typeface.BOLD);
        box.addView(tokenLabel,new LinearLayout.LayoutParams(-1,uiDp(28)));
        EditText token=new EditText(this);token.setHint("Masukkan Bot Token");token.setText(tgPrefs.getString("token",""));
        styleDialogInput(token);box.addView(token,new LinearLayout.LayoutParams(-1,uiDp(58)));

        TextView chatLabel=tv("CHAT ID",12);chatLabel.setTextColor(Color.CYAN);chatLabel.setTypeface(null,Typeface.BOLD);
        box.addView(chatLabel,new LinearLayout.LayoutParams(-1,uiDp(28)));
        EditText chat=new EditText(this);chat.setHint("Masukkan Chat ID");chat.setInputType(2);chat.setText(tgPrefs.getString("chat",""));
        styleDialogInput(chat);box.addView(chat,new LinearLayout.LayoutParams(-1,uiDp(58)));

        Switch sw=new Switch(this);sw.setText("Telegram aktif");sw.setTextColor(Color.WHITE);sw.setTextSize(15);
        sw.setMinHeight(uiDp(54));sw.setMinimumHeight(uiDp(54));sw.setPadding(uiDp(8),uiDp(6),uiDp(8),uiDp(6));
        sw.setChecked(telegramEnabled);box.addView(sw,new LinearLayout.LayoutParams(-1,uiDp(58)));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setNegativeButton("BATAL",null)
            .setNeutralButton("TEST",(dialog,which)->TelegramClient.send(token.getText().toString(),chat.getText().toString(),"Rayyan4 Telegram TEST",(ok,x)->{}))
            .setPositiveButton("SIMPAN",(dialog,which)->{tgPrefs.edit().putString("token",token.getText().toString().trim()).putString("chat",chat.getText().toString().trim()).putBoolean("enabled",sw.isChecked()).apply();telegramEnabled=sw.isChecked();AppLog.add(this,"TELEGRAM",telegramEnabled?"enabled":"disabled");}).create();
        showSizedDialog(d,0.78f,440);
    }

    void showLogDialog(String title){
        String data=AppLog.all(this);if(data.isEmpty())data="Belum ada log.";
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(18),uiDp(16),uiDp(18),uiDp(12));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView heading=tv(title,18);heading.setTextColor(Color.CYAN);heading.setTypeface(null,Typeface.BOLD);
        box.addView(heading,new LinearLayout.LayoutParams(-1,uiDp(40)));
        TextView note=tv("Catatan aktivitas • tekan lama untuk memilih teks",12);note.setTextColor(Color.LTGRAY);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(34)));
        TextView v=tv(data,12);v.setTextIsSelectable(true);v.setGravity(Gravity.TOP|Gravity.START);
        v.setPadding(uiDp(12),uiDp(12),uiDp(12),uiDp(12));v.setBackground(roundBg(Color.rgb(6,25,40),12));
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);sc.addView(v);
        box.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setNegativeButton("TUTUP",null)
            .setNeutralButton("HAPUS LOG",(dialog,which)->AppLog.clear(this)).create();
        showSizedDialog(d,0.82f,450);
    }

    void startGateway(){
        try{startForegroundService(new Intent(this,GatewayService.class));}
        catch(Exception e){try{startService(new Intent(this,GatewayService.class));}catch(Exception ignored){}}
        AppLog.add(this,"MT5","gateway start requested on port 8787");
    }

    String mt5SymbolForMarket(String market){
        if("BTCUSD".equalsIgnoreCase(market))return "BTCUSDm";
        if("ETHUSD".equalsIgnoreCase(market))return "ETHUSDm";
        return "XAUUSDm";
    }

    double minLotForSymbol(String symbol){
        if("ETHUSDm".equalsIgnoreCase(symbol))return 0.10; // confirmed by current Exness MT5 log
        if("BTCUSDm".equalsIgnoreCase(symbol))return 0.10; // BTC minimum confirmed from MT5 log
        return 0.01; // XAUUSDm default profile; MT5 EA validates final symbol rules
    }

    double lotForMarket(){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String symbol=mt5SymbolForMarket(selectedMarket);
        return p.getFloat("lot_"+symbol,0.01f);
    }

    /**
     * Save the market-specific lot used by AUTO LIMIT as well as the selected mode.
     * AUTO computes Entry/SL/TP from the live signal; those manual fields are not used.
     */
    private boolean saveAutoEntrySettings(Switch auto,Switch smartReentry,Spinner mode,EditText volume,
                                          SharedPreferences gatewayPrefs,String symbol){
        final double lot;
        try{
            lot=Double.parseDouble(volume.getText().toString().trim());
        }catch(Exception e){
            Toast.makeText(this,"Isi lot yang valid terlebih dahulu",Toast.LENGTH_SHORT).show();
            return false;
        }
        double min=minLotForSymbol(symbol);
        if(!Double.isFinite(lot)||lot<=0||lot+1e-8<min){
            Toast.makeText(this,"Lot minimum "+symbol+" = "+lotText(min),Toast.LENGTH_LONG).show();
            volume.setText(lotText(min));
            return false;
        }
        String operation=mode.getSelectedItemPosition()==1?"READY":"REST";
        gatewayPrefs.edit().putFloat("lot_"+symbol,(float)lot)
            .putBoolean("auto",auto.isChecked())
            .putBoolean("smart_reentry_enabled",smartReentry.isChecked())
            .putString("operation_mode",operation).apply();
        AppLog.add(this,"MODE",operation.equals("READY")?"SIAP TRADING":"ISTIRAHAT");
        AppLog.add(this,"AUTO LIMIT","settings saved symbol="+symbol+" lot="+lotText(lot)
            +" auto="+auto.isChecked()+" mode="+operation);
        return true;
    }

    String lotText(double lot){return String.format(Locale.US,"%.2f",lot);}

    private double smartReentryMaxDistanceAtr(SharedPreferences gp){
        double value=gp.getFloat("smart_reentry_max_distance_atr",1.5f);
        return Double.isFinite(value)&&value>=0.5&&value<=5.0?value:1.5;
    }

    private int smartReentryMaxAgeBars(SharedPreferences gp){
        int value=gp.getInt("smart_reentry_max_age_m1_bars",8);
        return value>=3&&value<=20?value:8;
    }

    private boolean validSmartReentryLimits(EditText distance,EditText age){
        try{
            double d=Double.parseDouble(distance.getText().toString().trim());
            int a=Integer.parseInt(age.getText().toString().trim());
            if(!Double.isFinite(d)||d<0.5||d>5.0||a<3||a>20)throw new NumberFormatException();
            return true;
        }catch(Exception e){
            Toast.makeText(this,"Jarak maksimum 0,5–5,0 ATR dan usia maksimum 3–20 candle M1.",
                Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void persistSmartReentryLimits(EditText distance,EditText age,SharedPreferences gp){
        double d=Double.parseDouble(distance.getText().toString().trim());
        int a=Integer.parseInt(age.getText().toString().trim());
        gp.edit().putFloat("smart_reentry_max_distance_atr",(float)d)
            .putInt("smart_reentry_max_age_m1_bars",a).apply();
        AppLog.add(this,"SMART LIMIT","limits saved maxDistance="+d+" ATR; maxAge="+a+" M1 bars");
    }

    double readQuickLot(){
        try{
            double lot=Double.parseDouble(quickLotInput.getText().toString().trim());
            double min=minLotForSymbol(mt5SymbolForMarket(selectedMarket));
            if(lot+1e-8<min){
                Toast.makeText(this,"Lot minimum "+mt5SymbolForMarket(selectedMarket)+" = "+lotText(min),Toast.LENGTH_LONG).show();
                quickLotInput.setText(lotText(min));
                return -1;
            }
            getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit()
                .putFloat("lot_"+mt5SymbolForMarket(selectedMarket),(float)lot).apply();
            return lot;
        }catch(Exception e){
            Toast.makeText(this,"Isi lot yang valid terlebih dahulu",Toast.LENGTH_SHORT).show();
            return -1;
        }
    }

    private boolean queueCancelPending(String targetId,String ticket,String symbol){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        try{
            if(targetId==null||targetId.isEmpty()||ticket==null||ticket.isEmpty()
                    ||Long.parseLong(ticket)<=0){
                AppLog.add(this,"SMART LIMIT","cancel blocked: missing original id/ticket");
                return false;
            }
            if(!GatewayService.isEaRecentlyOnline(this)){
                AppLog.add(this,"SMART LIMIT","cancel blocked: EA not polling; no replacement permitted");
                return false;
            }
            String existing=p.getString("next","");
            if(existing!=null&&!existing.trim().isEmpty()){
                AppLog.add(this,"SMART LIMIT","cancel blocked: gateway queue is busy");
                return false;
            }
            String commandId=Long.toString(System.currentTimeMillis());
            String safeSymbol=(symbol==null||symbol.isEmpty())?mt5SymbolForMarket(selectedMarket):symbol;
            String command="id="+commandId+";type=CANCEL;targetId="+targetId+
                ";ticket="+ticket+";symbol="+safeSymbol;
            p.edit().putString("next",command)
                .putString("order_status_id",commandId)
                .putString("order_status","QUEUED")
                .putString("order_status_detail","Menunggu EA memverifikasi dan membatalkan LIMIT lama")
                .putLong("order_status_at",System.currentTimeMillis()).apply();
            AppLog.add(this,"SMART LIMIT","cancel command queued target="+targetId+" ticket="+ticket);
            return true;
        }catch(Exception e){
            AppLog.add(this,"SMART LIMIT","cancel queue error: "+e.getClass().getSimpleName()+" "+e.getMessage());
            return false;
        }
    }

    boolean queueLimit(String side,double volume,double entry,double sl,double tp){
        try {
        side=side==null?"":side.trim().toUpperCase(Locale.US);
        if("BUY".equals(side))side="BUY_LIMIT";
        if("SELL".equals(side))side="SELL_LIMIT";
        if(!"BUY_LIMIT".equals(side)&&!"SELL_LIMIT".equals(side)){
            Toast.makeText(this,"Side harus BUY_LIMIT atau SELL_LIMIT",Toast.LENGTH_SHORT).show();return false;
        }
        if(volume<=0||entry<=0||sl<=0||tp<=0){Toast.makeText(this,"Nilai order tidak valid",Toast.LENGTH_SHORT).show();return false;}
        String mt5Symbol=mt5SymbolForMarket(selectedMarket);
        double minLot=minLotForSymbol(mt5Symbol);
        if(volume+1e-8<minLot){
            AppLog.add(this,"ORDER","blocked BAD_VOLUME symbol="+mt5Symbol+" volume="+lotText(volume)+" min="+lotText(minLot));
            Toast.makeText(this,"Lot minimum "+mt5Symbol+" = "+lotText(minLot)+" • order tidak dikirim",Toast.LENGTH_LONG).show();
            return false;
        }
        if("BUY_LIMIT".equals(side)){
            if(entry>=chart.getAsk()){Toast.makeText(this,"BUY LIMIT harus di bawah ASK",Toast.LENGTH_SHORT).show();return false;}
            if(!(sl<entry&&tp>entry)){Toast.makeText(this,"BUY LIMIT: SL < Entry < TP",Toast.LENGTH_SHORT).show();return false;}
        }
        if("SELL_LIMIT".equals(side)){
            if(entry<=chart.getBid()){Toast.makeText(this,"SELL LIMIT harus di atas BID",Toast.LENGTH_SHORT).show();return false;}
            if(!(tp<entry&&sl>entry)){Toast.makeText(this,"SELL LIMIT: TP < Entry < SL",Toast.LENGTH_SHORT).show();return false;}
        }
        SharedPreferences gatewayPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String existing=gatewayPrefs.getString("next","");
        if(existing!=null&&!existing.trim().isEmpty()){
            String existingId="";
            for(String part:existing.split(";")) if(part.startsWith("id=")){existingId=part.substring(3);break;}
            long existingAt=0L;
            try{existingAt=Long.parseLong(existingId);}catch(Exception ignored){}
            if(existingAt>0L && System.currentTimeMillis()-existingAt>15000L){
                gatewayPrefs.edit().remove("next")
                    .putString("order_status_id",existingId)
                    .putString("order_status","EXPIRED")
                    .putString("order_status_detail","Perintah belum diambil EA dalam 15 detik; periksa tab Trade dan Experts MT5 sebelum mengirim ulang")
                    .putLong("order_status_at",System.currentTimeMillis()).apply();
                AppLog.add(this,"ORDER","stale queue cleared without ACK id="+existingId+"; verify MT5 before resending");
                Toast.makeText(this,"Perintah kedaluwarsa >15 detik. Cek MT5 Trade/Experts sebelum entry baru.",Toast.LENGTH_LONG).show();
                return false;
            }
            AppLog.add(this,"ORDER","queue busy; refusing to overwrite unacknowledged command");
            Toast.makeText(this,"Antrean masih berisi order, tunggu ACK MT5",Toast.LENGTH_SHORT).show();
            return false;
        }
        String id=Long.toString(System.currentTimeMillis());
        String cmd="id="+id+";type=LIMIT;symbol="+mt5Symbol+";side="+side+";volume="+volume+";entry="+entry+";sl="+sl+";tp="+tp+";refBid="+chart.getBid()+";refAsk="+chart.getAsk();
        gatewayPrefs.edit().putString("next",cmd).putFloat("lot_"+mt5Symbol,(float)volume)
            .putString("order_status_id",id).putString("order_status","QUEUED")
            .putString("order_status_detail","Menunggu EA MT5 mengambil perintah")
            .putLong("order_status_at",System.currentTimeMillis()).apply();
        HistoryStore.add(this,"PENDING",side,entry,sl,tp,tp,lastSignal==null?0:lastSignal.confidence);
        AppLog.add(this,"ORDER","queued "+cmd);notifyLocal("MT5 "+side,"Entry queued for demo EA");
        Toast.makeText(this,"Entry masuk antrean • EA MT5 akan memproses otomatis",Toast.LENGTH_SHORT).show();
        return true;
        } catch(Exception e) {
            AppLog.add(this,"ORDER","queue error: "+e.getClass().getSimpleName()+" "+e.getMessage());
            Toast.makeText(this,"Gagal antre order: "+e.getMessage(),Toast.LENGTH_LONG).show();
            return false;
        }
    }

    void showMt5Dialog(){
        SharedPreferences gatewayPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String selectedMt5Symbol=mt5SymbolForMarket(selectedMarket);

        // Fixed header and footer keep KIRIM LIMIT / SIMPAN / TUTUP visible on small phones.
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(uiDp(14),uiDp(12),uiDp(14),uiDp(10));
        root.setBackground(roundBg(Color.rgb(3,18,30),16));

        TextView panelTitle=tv("AUTO ENTRY / MT5 • DEMO",18);
        panelTitle.setTextColor(Color.CYAN);panelTitle.setTypeface(null,Typeface.BOLD);
        root.addView(panelTitle,new LinearLayout.LayoutParams(-1,uiDp(36)));
        TextView panelNote=tv("Pemicu M1 + bias M5 • kirim hanya saat EA terhubung",12);
        panelNote.setTextColor(Color.LTGRAY);
        root.addView(panelNote,new LinearLayout.LayoutParams(-1,uiDp(32)));

        LinearLayout fields=new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(0,uiDp(3),0,uiDp(5));

        Switch auto=new Switch(this);
        auto.setText("AUTO LIMIT DEMO • order otomatis");
        auto.setTextSize(14);auto.setPadding(uiDp(10),uiDp(4),uiDp(10),uiDp(4));
        auto.setBackground(roundBg(Color.rgb(7,30,47),12));
        auto.setMinHeight(uiDp(52));auto.setMinimumHeight(uiDp(52));
        auto.setChecked(gatewayPrefs.getBoolean("auto",false));

        Switch smartReentry=new Switch(this);
        smartReentry.setText("SMART LIMIT RE-ENTRY • evaluasi 3 candle M1");
        smartReentry.setTextSize(13);
        smartReentry.setPadding(uiDp(10),uiDp(4),uiDp(10),uiDp(4));
        smartReentry.setBackground(roundBg(Color.rgb(7,30,47),12));
        smartReentry.setMinHeight(uiDp(52));smartReentry.setMinimumHeight(uiDp(52));
        smartReentry.setChecked(gatewayPrefs.getBoolean("smart_reentry_enabled",true));

        EditText maxDistance=new EditText(this);
        maxDistance.setHint("Jarak entry baru maksimum (ATR M1), default 1.5");
        maxDistance.setInputType(8194);styleDialogInput(maxDistance);
        maxDistance.setText(String.format(Locale.US,"%.1f",
            gatewayPrefs.getFloat("smart_reentry_max_distance_atr",1.5f)));
        EditText maxAge=new EditText(this);
        maxAge.setHint("Usia maksimum order lama (candle M1), default 8");
        maxAge.setInputType(2);styleDialogInput(maxAge);
        maxAge.setText(Integer.toString(gatewayPrefs.getInt("smart_reentry_max_age_m1_bars",8)));

        Spinner mode=new Spinner(this);
        String[] modes={"MODE ISTIRAHAT","MODE SIAP TRADING"};
        ArrayAdapter<String> modeAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes);
        mode.setAdapter(modeAdapter);mode.setMinimumHeight(uiDp(50));
        mode.setBackground(roundBg(Color.rgb(7,30,47),12));
        mode.setSelection("READY".equals(gatewayPrefs.getString("operation_mode","REST"))?1:0);

        TextView modeHint=tv("DEMO ONLY • "+selectedMt5Symbol+" • minimum lot "+lotText(minLotForSymbol(selectedMt5Symbol))+
            " • Auto entry hanya pada chart M1; EA MT5 harus polling aktif.",11);
        modeHint.setTextSize(11);modeHint.setTextColor(Color.CYAN);
        modeHint.setPadding(uiDp(10),uiDp(7),uiDp(10),uiDp(7));
        modeHint.setBackground(roundBg(Color.rgb(7,30,47),10));

        EditText side=new EditText(this);side.setHint("BUY_LIMIT / SELL_LIMIT");styleDialogInput(side);
        EditText vol=new EditText(this);vol.setHint("Lot • minimum "+lotText(minLotForSymbol(selectedMt5Symbol)));
        vol.setInputType(8194);styleDialogInput(vol);vol.setText(lotText(lotForMarket()));
        EditText en=new EditText(this);en.setHint("Entry");en.setInputType(8194);styleDialogInput(en);
        EditText sl=new EditText(this);sl.setHint("Stop Loss (SL)");sl.setInputType(8194);styleDialogInput(sl);
        EditText tp=new EditText(this);tp.setHint("Take Profit (TP)");tp.setInputType(8194);styleDialogInput(tp);

        SignalResult current=lastSignal;
        if(current!=null&&current.entry>0&&current.sl>0&&current.tp1>0){
            if("BUY".equals(current.side))side.setText("BUY_LIMIT");
            else if("SELL".equals(current.side))side.setText("SELL_LIMIT");
            en.setText(String.format(Locale.US,"%.2f",current.entry));
            sl.setText(String.format(Locale.US,"%.2f",current.sl));
            double target=validAutoTarget(current);
            if(target>0)tp.setText(String.format(Locale.US,"%.2f",target));
        }else{
            side.setText("");
        }

        fields.addView(mode,new LinearLayout.LayoutParams(-1,uiDp(52)));
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(-1,-2);
        hintParams.setMargins(0,uiDp(5),0,uiDp(6));fields.addView(modeHint,hintParams);
        fields.addView(auto,new LinearLayout.LayoutParams(-1,uiDp(54)));
        fields.addView(smartReentry,new LinearLayout.LayoutParams(-1,uiDp(54)));
        fields.addView(maxDistance,new LinearLayout.LayoutParams(-1,uiDp(54)));
        fields.addView(maxAge,new LinearLayout.LayoutParams(-1,uiDp(54)));
        fields.addView(vol,new LinearLayout.LayoutParams(-1,uiDp(54)));

        // Manual one-shot LIMIT fields are separate from automatic signal execution.
        // With AUTO enabled, only market-specific lot and operating mode need adjustment.
        LinearLayout manualFields=new LinearLayout(this);
        manualFields.setOrientation(LinearLayout.VERTICAL);
        TextView manualTitle=tv("MANUAL LIMIT • Entry / SL / TP",11);
        manualTitle.setTextColor(Color.CYAN);
        manualTitle.setPadding(uiDp(3),uiDp(6),uiDp(3),uiDp(3));
        manualFields.addView(manualTitle,new LinearLayout.LayoutParams(-1,uiDp(28)));
        manualFields.addView(side,new LinearLayout.LayoutParams(-1,uiDp(54)));
        manualFields.addView(en,new LinearLayout.LayoutParams(-1,uiDp(54)));
        manualFields.addView(sl,new LinearLayout.LayoutParams(-1,uiDp(54)));
        manualFields.addView(tp,new LinearLayout.LayoutParams(-1,uiDp(54)));
        fields.addView(manualFields,new LinearLayout.LayoutParams(-1,-2));

        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(false);sc.setSmoothScrollingEnabled(true);sc.addView(fields);
        root.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));

        TextView queuedStatus=tv("Belum ada perintah baru.",11);
        queuedStatus.setTextColor(Color.LTGRAY);
        queuedStatus.setPadding(uiDp(4),uiDp(4),uiDp(4),uiDp(4));
        queuedStatus.setMaxLines(3);
        queuedStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(queuedStatus,new LinearLayout.LayoutParams(-1,-2));

        Button send=btn("KIRIM LIMIT DEMO");
        send.setTextSize(12);send.setTypeface(null,Typeface.BOLD);
        send.setTextColor(Color.WHITE);send.setBackground(roundBg(Color.rgb(0,110,85),10));
        send.setMinHeight(uiDp(46));send.setMinimumHeight(uiDp(46));
        root.addView(send,new LinearLayout.LayoutParams(-1,uiDp(48)));

        LinearLayout footer=new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        Button save=btn("SIMPAN MODE");
        save.setTextSize(11);save.setTextColor(Color.WHITE);
        save.setBackground(roundBg(Color.rgb(8,70,103),10));
        Button close=btn("TUTUP");
        close.setTextSize(11);close.setTextColor(Color.WHITE);
        close.setBackground(roundBg(Color.rgb(53,65,77),10));
        LinearLayout.LayoutParams buttonParams=new LinearLayout.LayoutParams(0,uiDp(46),1f);
        buttonParams.setMargins(0,uiDp(5),uiDp(4),0);footer.addView(save,buttonParams);
        LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(0,uiDp(46),1f);
        closeParams.setMargins(uiDp(4),uiDp(5),0,0);footer.addView(close,closeParams);
        root.addView(footer,new LinearLayout.LayoutParams(-1,uiDp(51)));

        AlertDialog dialog=new AlertDialog.Builder(this).setView(root).create();
        final String[] queuedOrderId={""};
        Runnable updateAutoUi=()->{
            int visibility=auto.isChecked()?View.GONE:View.VISIBLE;
            manualFields.setVisibility(visibility);
            send.setVisibility(visibility);
        };
        auto.setOnCheckedChangeListener((buttonView,isChecked)->updateAutoUi.run());
        updateAutoUi.run();

        save.setOnClickListener(v->{
            if(!validSmartReentryLimits(maxDistance,maxAge))return;
            if(!saveAutoEntrySettings(auto,smartReentry,mode,vol,gatewayPrefs,selectedMt5Symbol))return;
            persistSmartReentryLimits(maxDistance,maxAge,gatewayPrefs);
            Toast.makeText(this,"Pengaturan AUTO ENTRY dan batas Smart LIMIT tersimpan untuk "+selectedMt5Symbol,Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        close.setOnClickListener(v->dialog.dismiss());
        send.setOnClickListener(v->{
            try{
                if(!validSmartReentryLimits(maxDistance,maxAge))return;
                if(!saveAutoEntrySettings(auto,smartReentry,mode,vol,gatewayPrefs,selectedMt5Symbol))return;
                persistSmartReentryLimits(maxDistance,maxAge,gatewayPrefs);
                if(!GatewayService.isEaRecentlyOnline(this)){
                    queuedStatus.setTextColor(Color.rgb(255,190,70));
                    queuedStatus.setText("DITAHAN • EA MT5 belum polling aktif. Periksa koneksi MT5/Winlator; tidak ada order yang diantrekan.");
                    AppLog.add(this,"ORDER_BLOCKED","manual LIMIT blocked: EA offline");
                    Toast.makeText(this,"EA MT5 belum terhubung • LIMIT tidak dikirim",Toast.LENGTH_LONG).show();
                    return;
                }
                boolean queued=queueLimit(side.getText().toString().trim().toUpperCase(Locale.US),
                    Double.parseDouble(vol.getText().toString().trim()),
                    Double.parseDouble(en.getText().toString().trim()),
                    Double.parseDouble(sl.getText().toString().trim()),
                    Double.parseDouble(tp.getText().toString().trim()));
                if(queued){
                    queuedOrderId[0]=gatewayPrefs.getString("order_status_id","");
                    queuedStatus.setTextColor(Color.rgb(255,190,70));
                    queuedStatus.setText("DIANTREKAN • menunggu ACK EA MT5. Ini belum berarti order telah terpasang.");
                    send.setText("LIMIT SUDAH DIANTREKAN");
                    send.setEnabled(false);
                }
            }catch(Exception e){
                AppLog.add(this,"ORDER","dialog input error: "+e.getClass().getSimpleName()+" "+e.getMessage());
                Toast.makeText(this,"Periksa angka Lot, Entry, SL dan TP",Toast.LENGTH_SHORT).show();
            }catch(Throwable t){
                AppLog.add(this,"ORDER","dialog submit failure: "+t.getClass().getSimpleName()+" "+t.getMessage());
                Toast.makeText(this,"Gagal memproses LIMIT; aplikasi tetap dibuka",Toast.LENGTH_LONG).show();
            }
        });

        showSizedDialog(dialog,0.86f,450);
        android.os.Handler ackHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable ackWatcher=new Runnable(){
            @Override public void run(){
                if(!dialog.isShowing())return;
                if(!queuedOrderId[0].isEmpty()){
                    SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
                    String statusId=p.getString("order_status_id","");
                    if(queuedOrderId[0].equals(statusId)){
                        String result=p.getString("order_status","UNKNOWN");
                        String detail=p.getString("order_status_detail","");
                        if("QUEUED".equals(result)){
                            queuedStatus.setTextColor(Color.rgb(255,190,70));
                            queuedStatus.setText("DIANTREKAN • menunggu ACK EA MT5; belum terpasang.");
                        }else if("EXECUTED".equals(result)){
                            queuedStatus.setTextColor(green);
                            queuedStatus.setText("ORDER TERPASANG • MT5 mengonfirmasi order diterima.");
                        }else if("EXPIRED".equals(result)){
                            queuedStatus.setTextColor(red);
                            queuedStatus.setText("ACK KEDALUWARSA • "+detail+" • cek MT5 Trade/Experts sebelum kirim ulang.");
                        }else{
                            queuedStatus.setTextColor(red);
                            queuedStatus.setText("ORDER GAGAL: "+result+" • "+detail+" • periksa Experts log MT5.");
                        }
                    }else{
                        queuedStatus.setTextColor(Color.rgb(255,190,70));
                        queuedStatus.setText("DIANTREKAN • menunggu ACK EA MT5; belum terpasang.");
                    }
                }
                ackHandler.postDelayed(this,1000L);
            }
        };
        ackHandler.post(ackWatcher);
    }

    @Override protected void onDestroy(){signalUiHandler.removeCallbacks(entryBlinkRunnable);if(ex!=null)ex.shutdownNow();if(candleRefresh!=null)candleRefresh.shutdownNow();super.onDestroy();}
}