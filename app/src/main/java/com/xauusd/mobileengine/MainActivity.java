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
        // Safety migration: never carry AUTO LIMIT into this revised build from an older preference.
        SharedPreferences gatewayPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        if(!gatewayPrefs.getBoolean("auto_safe_guard_v1",false)){
            gatewayPrefs.edit().putBoolean("auto",false).putString("operation_mode","REST")
                .putBoolean("auto_safe_guard_v1",true).apply();
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
        scoreView=tv("CONFIDENCE 0%",10);scoreView.setGravity(Gravity.CENTER);scoreView.setIncludeFontPadding(false);scoreView.setPadding(0,0,0,0);scoreView.setSingleLine(false);top.addView(scoreView,new LinearLayout.LayoutParams(92,44));
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
        // Human confirmation is the final trigger, but the price is re-evaluated
        // immediately before sending so a 1-second market move cannot use stale levels.
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

    private void applyEntryButtonColors(){
        if(buyLimitBtn==null||sellLimitBtn==null)return;
        if(!entryReady){
            buyLimitBtn.setBackground(roundBg(Color.rgb(0,145,88),10));
            sellLimitBtn.setBackground(roundBg(Color.rgb(190,45,58),10));
            buyLimitBtn.setTextColor(Color.WHITE);
            sellLimitBtn.setTextColor(Color.WHITE);
            return;
        }
        // Only the active entry direction pulses; the opposite side stays dim.
        int buyColor;
        int sellColor;
        if("BUY".equals(readySide)){
            buyColor=blinkOn?Color.rgb(0,245,145):Color.rgb(0,104,67);
            sellColor=blinkOn?Color.rgb(105,40,48):Color.rgb(65,28,35);
        }else{
            buyColor=blinkOn?Color.rgb(15,105,75):Color.rgb(8,58,43);
            sellColor=blinkOn?Color.rgb(255,85,100):Color.rgb(140,28,43);
        }
        buyLimitBtn.setBackground(roundBg(buyColor,10));
        sellLimitBtn.setBackground(roundBg(sellColor,10));
        buyLimitBtn.setTextColor(Color.WHITE);
        sellLimitBtn.setTextColor(Color.WHITE);
    }

    void updateSignal(Candle[] m1,Candle[] m5){
        if(m1==null||m1.length<30||m5==null||m5.length<30){
            if(scoreView!=null)scoreView.setText("SCORE\n0%");
            resetEntryReadyUi("menunggu data M1/M5");
            return;
        }
        double bid=chart==null?0:chart.getBid(),ask=chart==null?0:chart.getAsk();
        SignalResult previous=lastSignal;lastSignal=SignalEngine.evaluate(m1,m5,bid,ask);SignalResult s=lastSignal;

        int displayScore=Math.max(0,Math.min(100,s.score));
        scoreView.setText("SCORE\n"+displayScore+"%");
        scoreView.setTextSize(11);
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
        if(changed&&("BUY".equals(s.side)||"SELL".equals(s.side))){
            // Auto LIMIT is triggered only on a fresh WAIT/other-side -> BUY/SELL transition.
            // Persistent signal key prevents duplicate queueing on UI refresh/reconnect.
            SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
            if(gp.getBoolean("auto",false) && "READY".equals(gp.getString("operation_mode","REST"))){
                String signalKey=s.side+"|"+s.m5Bias+"|"+s.setup;
                String lastKey=gp.getString("auto_last_signal_key","");
                long lastAuto=gp.getLong("auto_last_order_ms",0L);
                boolean filters=s.liquiditySweep&&s.wickRejection&&s.bos&&s.emaFilter&&s.rsiFilter&&s.macdFilter&&s.atrFilter&&s.spreadFilter;
                if(!signalKey.equals(lastKey) && s.confidence>=gp.getInt("min_confidence",4) && filters
                        && GatewayService.isEaRecentlyOnline(this) && System.currentTimeMillis()-lastAuto>=60000L){
                    // Save the signal key before queueing to prevent duplicate attempts on repeated refreshes.
                    gp.edit().putString("auto_last_signal_key",signalKey).putLong("auto_last_order_ms",System.currentTimeMillis()).apply();
                    double lot=lotForMarket();
                    String orderSide="BUY".equals(s.side)?"BUY_LIMIT":"SELL_LIMIT";
                    boolean queued=queueLimit(orderSide,lot,s.entry,s.sl,s.tp2>0?s.tp2:s.tp1);
                    AppLog.add(this,"AUTO LIMIT",queued?"queued "+signalKey:"queue rejected "+signalKey);
                }else if(!GatewayService.isEaRecentlyOnline(this)){
                    AppLog.add(this,"AUTO LIMIT","blocked: EA offline");
                }
            }
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
        for(int value:new int[]{60,70,80,90}){ Button option=btn((current==value?"✓  ":"")+"CONFIDENCE "+value+"%"); option.setTextSize(14); option.setBackground(roundBg(current==value?Color.rgb(0,105,83):Color.rgb(7,34,60),12)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,48);p.setMargins(0,5,0,5);choices.addView(option,p);option.setOnClickListener(v->{getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putInt("min_entry_score",value).apply();Toast.makeText(this,"Confidence minimum "+value+"%",Toast.LENGTH_SHORT).show();if(lastSignal!=null)updateEntryReadyUi(lastSignal,chart.getBid(),chart.getAsk());((Dialog)box.getTag()).dismiss();}); }
        box.addView(choices); AlertDialog d=new AlertDialog.Builder(this).setView(box).setNegativeButton("Tutup",null).create();box.setTag(d);d.setOnShowListener(x->{if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-40,420),-2);}});d.show();if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-40,420),-2);}
    }

    private void showSettingsPanel(View anchor){
        RayyanMenuLayout.showSettings(this,selectedMarket,selectedTf,this::handleMenuAction);
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
        else if("strategy".equals(action))Toast.makeText(this,"Strategy: wick rejection + liquidity sweep + BOS",Toast.LENGTH_LONG).show();
        else if("risk".equals(action))Toast.makeText(this,"Risk Management: gunakan akun demo dan lot minimum saat pengujian",Toast.LENGTH_LONG).show();
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
        if(crashStack.length()>6000)crashStack=crashStack.substring(0,6000)+"\n...[stack dipotong]";
        String msg="GATEWAY: port 8787 berjalan jika tes TCP/HTTP PASS\n"
            +"EA POLLING: "+(eaOnline?"PASS":"BELUM TERDETEKSI")+(age>=0?" • poll "+age+" dtk lalu":"")+
            "\nQUEUE: "+(queue==null||queue.trim().isEmpty()?"KOSONG":"ADA PERINTAH MENUNGGU")+
            "\nACK / ORDER TERAKHIR: "+orderStatus+
            (orderId.isEmpty()?"":"\nID: "+orderId)+
            (detail.isEmpty()?"":"\nDetail: "+detail)+
            "\n\nAKSI TERAKHIR: "+action+(actionTime.isEmpty()?"":" • "+actionTime)+
            "\nCRASH JAVA TERAKHIR: "+crashTime+(crashThread.isEmpty()?"":" • thread "+crashThread)+
            (crashStack.isEmpty()?"":"\n\nSTACK TRACE:\n"+crashStack)+
            "\n\nPemeriksaan ini TIDAK mengirim order atau order tes.";
        TextView v=tv(msg,13);v.setTextIsSelectable(true);v.setGravity(Gravity.TOP|Gravity.START);v.setPadding(18,12,18,12);
        ScrollView sc=new ScrollView(this);sc.addView(v);
        new AlertDialog.Builder(this).setTitle("RAYYAN4 • GATEWAY / QUEUE / ACK")
            .setView(sc).setPositiveButton("PERIKSA LAGI",(d,w)->showGatewayAckDiagnostics())
            .setNegativeButton("TUTUP",null).show();
        AppLog.add(this,"DIAGNOSTIC","Gateway/ACK status checked; EA online="+eaOnline+" ageSec="+age+" order="+orderStatus);
    }
    String marketDescription(String market){
        if("BTCUSD".equals(market))return "Bitcoin / US Dollar";
        if("ETHUSD".equals(market))return "Ethereum / US Dollar";
        return "Gold Spot / US Dollar";
    }

    void showMarketDialog(){
        final String[] markets={"XAUUSD","BTCUSD","ETHUSD"};
        int current=0;
        for(int i=0;i<markets.length;i++)if(markets[i].equals(selectedMarket))current=i;
        new AlertDialog.Builder(this).setTitle("PILIH MARKET")
            .setSingleChoiceItems(markets,current,(dialog,which)->{
                String next=markets[which];
                if(next.equals(selectedMarket)){dialog.dismiss();return;}
                selectedMarket=next;
                lastHeaderPrice=Double.NaN;
                if(priceView!=null){priceView.setText("--");priceView.setTextColor(Color.CYAN);}
                prefs.edit().putString("market",selectedMarket).apply();
                if(marketTitle!=null)marketTitle.setText(selectedMarket);
                if(marketSubtitle!=null)marketSubtitle.setText(marketDescription(selectedMarket));
                if(chart!=null)chart.setMarket(selectedMarket);
                if(signalSummary!=null)signalSummary.setText(selectedMarket+" • "+selectedTf+"\nMenunggu data market...");
                if(status!=null)status.setText("● MENGHUBUNGKAN • "+selectedMarket);
                lastSignal=null;
                resetEntryReadyUi("menunggu data market");
                dialog.dismiss();
                load(interval(selectedTf),selectedTf);
                AppLog.add(this,"MARKET","selected "+selectedMarket+" (APK feed only; MT5 symbol mapping deferred)");
            }).setNegativeButton("BATAL",null).show();
    }

    void showSignalDialog(){
        if(lastSignal==null){Toast.makeText(this,"Menunggu data Signal Engine...",Toast.LENGTH_SHORT).show();return;}
        SignalResult s=lastSignal;
        String msg="MARKET      : "+selectedMarket+"\nSIDE        : "+s.side+"\nM5 BIAS     : "+s.m5Bias+"\nM1 ENTRY    : "+s.entry+"\nSL          : "+s.sl+"\nTP1         : "+s.tp1+"\nTP2         : "+s.tp2+"\nR:R         : "+String.format(Locale.US,"%.2f",s.rr)+"\nCONFIDENCE  : "+s.confidence+"/5\nENTRY SCORE : "+s.score+"%\n\nLiquidity Sweep : "+onoff(s.liquiditySweep)+"\nWick Rejection  : "+onoff(s.wickRejection)+"\nBOS             : "+onoff(s.bos)+"\nEMA Filter      : "+onoff(s.emaFilter)+"\nRSI Filter      : "+onoff(s.rsiFilter)+"\nMACD Filter     : "+onoff(s.macdFilter)+"\nATR Filter      : "+onoff(s.atrFilter)+"\nSpread Filter   : "+onoff(s.spreadFilter);
        new AlertDialog.Builder(this).setTitle("SIGNAL ENGINE").setMessage(msg).setPositiveButton("TUTUP",null).show();
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
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,54);rp.setMargins(0,4,0,4);list.addView(row,rp);
            TextView ico=tv(icons[i],21);ico.setTextColor(Color.rgb(0,225,180));ico.setGravity(Gravity.CENTER);row.addView(ico,new LinearLayout.LayoutParams(38,-1));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(Gravity.CENTER_VERTICAL);TextView nm=tv(names[i],13);nm.setTextColor(Color.WHITE);nm.setTypeface(null,Typeface.BOLD);labels.addView(nm);TextView state=tv(enabled?"TAMPIL • ON":"SEMBUNYI • OFF",10);state.setTextColor(enabled?Color.rgb(0,225,150):Color.LTGRAY);labels.addView(state);row.addView(labels,new LinearLayout.LayoutParams(0,-1,1));
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
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(22,8,22,4);
        EditText token=new EditText(this);token.setHint("Bot Token");token.setText(tgPrefs.getString("token",""));
        EditText chat=new EditText(this);chat.setHint("Chat ID");chat.setInputType(2);chat.setText(tgPrefs.getString("chat",""));
        Switch sw=new Switch(this);sw.setText("Telegram aktif");sw.setChecked(telegramEnabled);box.addView(token);box.addView(chat);box.addView(sw);
        new AlertDialog.Builder(this).setTitle("TELEGRAM").setView(box).setNegativeButton("BATAL",null)
            .setNeutralButton("TEST",(d,w)->TelegramClient.send(token.getText().toString(),chat.getText().toString(),"Rayyan4 Telegram TEST",(ok,x)->{}))
            .setPositiveButton("SIMPAN",(d,w)->{tgPrefs.edit().putString("token",token.getText().toString().trim()).putString("chat",chat.getText().toString().trim()).putBoolean("enabled",sw.isChecked()).apply();telegramEnabled=sw.isChecked();AppLog.add(this,"TELEGRAM",telegramEnabled?"enabled":"disabled");}).show();
    }

    void showLogDialog(String title){
        String data=AppLog.all(this);if(data.isEmpty())data="Belum ada log.";
        TextView v=tv(data,10);v.setTextIsSelectable(true);v.setGravity(Gravity.TOP|Gravity.START);ScrollView sc=new ScrollView(this);sc.addView(v);
        new AlertDialog.Builder(this).setTitle(title).setView(sc).setNegativeButton("TUTUP",null).setNeutralButton("HAPUS LOG",(d,w)->AppLog.clear(this)).show();
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

    String lotText(double lot){return String.format(Locale.US,"%.2f",lot);}

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
        Toast.makeText(this,"Entry queued • menunggu konfirmasi EA MT5",Toast.LENGTH_SHORT).show();
        return true;
        } catch(Exception e) {
            AppLog.add(this,"ORDER","queue error: "+e.getClass().getSimpleName()+" "+e.getMessage());
            Toast.makeText(this,"Gagal antre order: "+e.getMessage(),Toast.LENGTH_LONG).show();
            return false;
        }
    }

    void showMt5Dialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        SharedPreferences gatewayPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        Switch auto=new Switch(this);auto.setText("AUTO LIMIT DEMO • order otomatis");auto.setChecked(gatewayPrefs.getBoolean("auto",false));
        Spinner mode=new Spinner(this);
        String[] modes={"MODE ISTIRAHAT","MODE SIAP TRADING"};
        ArrayAdapter<String> modeAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes);
        mode.setAdapter(modeAdapter);
        mode.setSelection("READY".equals(gatewayPrefs.getString("operation_mode","REST"))?1:0);
        String selectedMt5Symbol=mt5SymbolForMarket(selectedMarket);
        TextView modeHint=tv("DEMO ONLY • "+selectedMt5Symbol+" min lot "+lotText(minLotForSymbol(selectedMt5Symbol))+" • Winlator + MT5 + EA aktif",11);
        modeHint.setPadding(4,8,4,8);
        EditText side=new EditText(this);side.setHint("BUY_LIMIT / SELL_LIMIT");
        EditText vol=new EditText(this);vol.setHint("Lot • minimum "+lotText(minLotForSymbol(selectedMt5Symbol)));vol.setInputType(8194);
        vol.setText(lotText(lotForMarket()));
        EditText en=new EditText(this);en.setHint("Entry");en.setInputType(8194);
        EditText sl=new EditText(this);sl.setHint("SL");sl.setInputType(8194);
        EditText tp=new EditText(this);tp.setHint("TP");tp.setInputType(8194);
        // Pre-fill the MT5 dialog from the latest calculated signal when available.
        SignalResult current=lastSignal;
        if(current!=null && current.entry>0 && current.sl>0 && current.tp1>0){
            if("BUY".equals(current.side))side.setText("BUY_LIMIT");
            else if("SELL".equals(current.side))side.setText("SELL_LIMIT");
            else side.setText("");
            en.setText(String.format(Locale.US,"%.2f",current.entry));
            sl.setText(String.format(Locale.US,"%.2f",current.sl));
            tp.setText(String.format(Locale.US,"%.2f",current.tp2>0?current.tp2:current.tp1));
            if(!"BUY".equals(current.side)&&!"SELL".equals(current.side))
                Toast.makeText(this,"Harga terisi, tunggu BUY/SELL sebelum mengirim",Toast.LENGTH_LONG).show();
        } else {
            side.setText("");
            Toast.makeText(this,"Data harga sinyal belum siap",Toast.LENGTH_LONG).show();
        }
        TextView queuedStatus=tv("",11);queuedStatus.setTextColor(green);queuedStatus.setPadding(4,6,4,2);
        box.addView(mode);box.addView(modeHint);box.addView(auto);box.addView(side);box.addView(vol);box.addView(en);box.addView(sl);box.addView(tp);box.addView(queuedStatus);
        final String[] queuedOrderId={""};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("MT5 / EA • DEMO").setView(box)
            .setNegativeButton("TUTUP",null).setNeutralButton("SIMPAN MODE",null).setPositiveButton("KIRIM LIMIT DEMO",null).create();
        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->{
                gatewayPrefs.edit().putBoolean("auto",auto.isChecked())
                    .putString("operation_mode",mode.getSelectedItemPosition()==1?"READY":"REST").apply();
                AppLog.add(this,"MODE",mode.getSelectedItemPosition()==1?"SIAP TRADING":"ISTIRAHAT");
                Toast.makeText(this,"Mode tersimpan",Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            try{
                gatewayPrefs.edit().putBoolean("auto",auto.isChecked())
                    .putString("operation_mode",mode.getSelectedItemPosition()==1?"READY":"REST").apply();
                boolean queued=queueLimit(side.getText().toString().trim().toUpperCase(Locale.US),
                    Double.parseDouble(vol.getText().toString().trim()),
                    Double.parseDouble(en.getText().toString().trim()),
                    Double.parseDouble(sl.getText().toString().trim()),
                    Double.parseDouble(tp.getText().toString().trim()));
                if(queued){
                    queuedOrderId[0]=gatewayPrefs.getString("order_status_id","");
                    queuedStatus.setText("DIANTREKAN • menunggu EA MT5, batas 15 detik. Belum berarti order terpasang.");
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("SUDAH DIANTREKAN");
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                }
            }catch(Exception e){
                AppLog.add(this,"ORDER","dialog input error: "+e.getClass().getSimpleName()+" "+e.getMessage());
                Toast.makeText(this,"Periksa angka Lot, Entry, SL dan TP",Toast.LENGTH_SHORT).show();
            }catch(Throwable t){
                AppLog.add(this,"ORDER","dialog submit failure: "+t.getClass().getSimpleName()+" "+t.getMessage());
                Toast.makeText(this,"Gagal mengirim LIMIT; aplikasi tetap dibuka",Toast.LENGTH_LONG).show();
            }
            });
        });
        dialog.show();
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
                            queuedStatus.setText("DIANTREKAN • menunggu EA MT5 (maks. 15 detik); belum terpasang.");
                        }else if("EXECUTED".equals(result)){
                            queuedStatus.setTextColor(green);
                            queuedStatus.setText("ORDER TERPASANG • MT5 mengonfirmasi order diterima.");
                        }else if("EXPIRED".equals(result)){
                            queuedStatus.setTextColor(red);
                            queuedStatus.setText("KEDALUWARSA / ACK TIDAK DITERIMA • "+detail+" • cek MT5 Trade/Experts sebelum kirim ulang.");
                        }else{
                            queuedStatus.setTextColor(red);
                            queuedStatus.setText("ORDER GAGAL: "+result+" • "+detail+" • periksa Experts log MT5.");
                        }
                    }else{
                        queuedStatus.setTextColor(Color.rgb(255,190,70));
                        queuedStatus.setText("DIANTREKAN • menunggu EA MT5 (maks. 15 detik); belum terpasang.");
                    }
                }
                ackHandler.postDelayed(this,1000L);
            }
        };
        ackHandler.post(ackWatcher);
    }

    @Override protected void onDestroy(){signalUiHandler.removeCallbacks(entryBlinkRunnable);if(ex!=null)ex.shutdownNow();if(candleRefresh!=null)candleRefresh.shutdownNow();super.onDestroy();}
}