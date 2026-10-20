package com.xauusd.mobileengine;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    private ImageView chartMirror;
    private View mainRoot;
    private String appTheme = RayyanTheme.DEFAULT;
    private int defaultNavigationBarColor = Color.BLACK;
    private int defaultSystemUiVisibility = 0;
    private String chartDisplayMode = "BIQUOTE";
    private long chartMirrorModified=0L;
    private final Handler chartMirrorHandler=new Handler(Looper.getMainLooper());
    private final Runnable chartMirrorRefresh=new Runnable(){
        @Override public void run(){
            try{
                java.io.File f=getFileStreamPath("mt5_chart.png");
                long modified=f.isFile()?f.lastModified():0L;
                // Keep the last valid MT5 image visible through brief upload/network pauses.
                // Never cover the native chart with an empty ImageView if a PNG is partial/corrupt.
                if(chartMirror!=null&&modified>0L&&modified!=chartMirrorModified){
                    Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath());
                    if(b!=null){
                        chartMirror.setImageBitmap(b);
                        chartMirrorModified=modified;
                    }
                }
                if(chartMirror!=null){
                    boolean showMt5 = "MT5".equals(chartDisplayMode) && chartMirror.getDrawable()!=null;
                    chartMirror.setVisibility(showMt5 ? View.VISIBLE : View.GONE);
                    if(chart!=null)chart.setVisibility(showMt5 ? View.GONE : View.VISIBLE);
                }
            }catch(Exception ignored){}
            chartMirrorHandler.postDelayed(this,750L);
        }
    };
    private TextView priceView, spreadView, status, signalSummary, scoreView, entryView, slView, tp1View, tp2View, marketTitle, marketSubtitle;
    private double lastHeaderPrice=Double.NaN;
    private String lastSpreadLabel="";
    private long lastFeedQuoteAt=0L;
    private static final long ORDER_STATE_MAX_AGE_MS=8000L;
    private static final long M1_BAR_MAX_AGE_MS=120000L;
    private static final long M5_BAR_MAX_AGE_MS=600000L;
    private static final String OPEN_ACTION_SWEEP_FIRST="SWEEP_FIRST";
    private static final String OPEN_ACTION_WAIT_BOUNCE="WAIT_BOUNCE";
    private static final int AUTO_ENTRY_OFFSET_POINTS=10;
    private Button signalSideButton, buyLimitBtn, sellLimitBtn;
    private TextView entryReadyView;
    private final Handler signalUiHandler = new Handler(Looper.getMainLooper());
    private boolean entryReady = false;
    private boolean blinkOn = false;
    private String readySide = "WAIT";
    private String readyCandleKey = "";
    private String readyCandleSide = "WAIT";
    private String queueOrigin = "MANUAL";
    private double queueOutcomeTarget = 0;
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
    private Candle[] m1Data=new Candle[0];
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
        if(android.os.Build.VERSION.SDK_INT>=21)defaultNavigationBarColor=getWindow().getNavigationBarColor();
        defaultSystemUiVisibility=getWindow().getDecorView().getSystemUiVisibility();
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
        SharedPreferences uiPrefs=getSharedPreferences("rayyan4_ui",MODE_PRIVATE);
        chartDisplayMode=uiPrefs.getString("chart_display_mode","BIQUOTE");
        appTheme=RayyanTheme.normalize(uiPrefs.getString("app_theme",RayyanTheme.DEFAULT));
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
        FrameLayout chartArea=new FrameLayout(this);
        chartArea.setBackgroundColor(Color.rgb(2,10,18));
        chartArea.addView(chart,new FrameLayout.LayoutParams(-1,-1));
        chartMirror=new ImageView(this);
        chartMirror.setScaleType(ImageView.ScaleType.FIT_XY);
        // Use the full mirrored MT5 frame so the broker's right price scale sits at
        // the actual right edge; right-side candle positioning is configured by the EA.
        chartMirror.setPadding(0,0,0,0);
        chartMirror.setBackgroundColor(Color.rgb(2,10,18));
        chartMirror.setContentDescription("Tampilan chart asli MT5 dari Winlator");
        chartMirror.setClickable(false);chartMirror.setFocusable(false);
        chartMirror.setVisibility(View.GONE);
        chartArea.addView(chartMirror,new FrameLayout.LayoutParams(-1,-1));
        root.addView(chartArea,new LinearLayout.LayoutParams(-1,0,1));
        chartMirrorHandler.removeCallbacks(chartMirrorRefresh);
        chartMirrorHandler.post(chartMirrorRefresh);

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
        mainRoot = root;
        applyCurrentAppTheme();
        requestNotificationPermission();
        startGateway();
        selectTimeframe("M1",false);load("1m","M1");
    }

    private void showChartDisplayDialog(){
        final String[] choices={"LIVE BIQUOTE • Tampilan profesional","CHART MT5 • Tampilan terminal MT5"};
        int selected="MT5".equals(chartDisplayMode)?1:0;
        new AlertDialog.Builder(this).setTitle("PILIH TAMPILAN LIVE")
            .setSingleChoiceItems(choices,selected,(dialog,which)->{
                setChartDisplayMode(which==1?"MT5":"BIQUOTE");
                dialog.dismiss();
            }).setNegativeButton("BATAL",null).show();
    }

    private void setChartDisplayMode(String mode){
        chartDisplayMode="MT5".equals(mode)?"MT5":"BIQUOTE";
        getSharedPreferences("rayyan4_ui",MODE_PRIVATE).edit()
            .putString("chart_display_mode",chartDisplayMode).apply();
        boolean showMt5="MT5".equals(chartDisplayMode)&&chartMirror!=null&&chartMirror.getDrawable()!=null;
        if(chart!=null)chart.setVisibility(showMt5?View.GONE:View.VISIBLE);
        if(chartMirror!=null)chartMirror.setVisibility(showMt5?View.VISIBLE:View.GONE);
        Toast.makeText(this,"Tampilan: "+("MT5".equals(chartDisplayMode)?"Chart MT5":"LIVE BIQUOTE"),Toast.LENGTH_SHORT).show();
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
        if(priceView==null||!QuoteSafety.valid(bid,ask))return;
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

    /** Last valid MT5 Bid/Ask/point plus terminal-tick and LAN-receipt freshness. */
    private double[] cachedBrokerQuote(String market){
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String symbol=mt5SymbolForMarket(market);
        double bid=preferenceDouble(gp,"broker_quote_bid_"+symbol,0.0);
        double ask=preferenceDouble(gp,"broker_quote_ask_"+symbol,0.0);
        return QuoteSafety.valid(bid,ask)?new double[]{bid,ask}:null;
    }

    private double[] freshBrokerQuote(String market){
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String symbol=mt5SymbolForMarket(market);
        long received=gp.getLong("broker_quote_received_ms_"+symbol,0L);
        long tickAt=gp.getLong("broker_quote_tick_ms_"+symbol,0L);
        if(!QuoteSafety.isFresh(received,tickAt,System.currentTimeMillis()))return null;
        return cachedBrokerQuote(market);
    }

    private boolean hasFreshEntryQuotes(){
        long now=System.currentTimeMillis();
        boolean feedFresh=lastFeedQuoteAt>0L&&now>=lastFeedQuoteAt
            &&now-lastFeedQuoteAt<=5000L&&chart!=null
            &&QuoteSafety.valid(chart.getBid(),chart.getAsk());
        boolean m1Fresh=m1Data!=null&&m1Data.length>0
            &&QuoteSafety.isFreshBar(m1Data[m1Data.length-1].t,now,M1_BAR_MAX_AGE_MS);
        boolean m5Fresh=m5Data!=null&&m5Data.length>0
            &&QuoteSafety.isFreshBar(m5Data[m5Data.length-1].t,now,M5_BAR_MAX_AGE_MS);
        return feedFresh&&m1Fresh&&m5Fresh&&freshBrokerQuote(selectedMarket)!=null;
    }

    /** Chart prices remain in the candle feed's scale; spread uses real broker quotes. */
    private void applyQuoteSnapshot(String market,double[] feedTick){
        if(!market.equals(selectedMarket))return;
        long now=System.currentTimeMillis();
        if(feedTick!=null&&feedTick.length>=2&&QuoteSafety.valid(feedTick[0],feedTick[1])){
            lastFeedQuoteAt=now;
            double feedMid=(feedTick[0]+feedTick[1])*0.5;
            // Keep the forming M1/M5 bars synchronized with the one-second quote loop.
            // This removes the old 15-second OHLC lag from wick detection and LIMIT pricing.
            m1Data=LiveCandleUpdater.update(m1Data,60_000L,feedMid,now);
            m5Data=LiveCandleUpdater.update(m5Data,300_000L,feedMid,now);
            if(chart!=null)chart.updateLiveBar(feedTick[0],feedTick[1],now,selectedTf);
            updateHeaderPrice(feedTick[0],feedTick[1]);
        }
        boolean feedFresh=lastFeedQuoteAt>0L&&now>=lastFeedQuoteAt
            &&now-lastFeedQuoteAt<=5000L&&chart!=null
            &&QuoteSafety.valid(chart.getBid(),chart.getAsk());
        double[] broker=freshBrokerQuote(market);
        if(broker!=null){
            // The MT5 broker quote is the execution reference. Keep the candle history
            // from the chart feed, but align the live price/header and signal tick to MT5.
            chart.setQuote(broker[0],broker[1],selectedTf);
            updateHeaderPrice(broker[0],broker[1]);
            SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
            double spread=broker[1]-broker[0];
            double point=preferenceDouble(gp,"broker_quote_point_"+mt5SymbolForMarket(market),0.0);
            lastSpreadLabel=point>0.0
                ?String.format(Locale.US,"SPREAD %.2f • %.0f MT5 points",spread,spread/point)
                :String.format(Locale.US,"SPREAD %.2f • MT5",spread);
            if(spreadView!=null)spreadView.setText(feedFresh?lastSpreadLabel:lastSpreadLabel+" • CHART FEED STALE");
        }else if(spreadView!=null){
            spreadView.setText(lastSpreadLabel.isEmpty()?"SPREAD -- • MT5 STALE":lastSpreadLabel+" • MT5 STALE");
        }
        if(!feedFresh||broker==null){
            status.setText(!feedFresh?"● CHART FEED STALE • ENTRY LOCKED • "+selectedTf
                :"● MT5 QUOTE STALE • ENTRY LOCKED • "+selectedTf);
            resetEntryReadyUi(!feedFresh?"chart price stale":"harga MT5 belum segar");
        }else{
            updateLiveGatewayStatus(market);
        }
        syncBrokerEntryLine();
        if(m1Data!=null&&m1Data.length>=30&&m5Data!=null&&m5Data.length>=30)
            updateSignal(m1Data,m5Data);
    }

    /** Show the real blocker for AUTO without changing the trade safety gates. */
    private void updateLiveGatewayStatus(String market){
        if(status==null)return;
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        if(!gp.getBoolean("auto",false)){
            status.setText("● QUOTE LIVE • SMART AUTO OFF • "+market+" • "+selectedTf);
            return;
        }
        if(!"READY".equals(gp.getString("operation_mode","REST"))){
            status.setText("● QUOTE LIVE • MODE REST • SMART AUTO PAUSED");
            return;
        }
        if(!"M1".equals(selectedTf)){
            status.setText("● QUOTE LIVE • SMART AUTO MENUNGGU M1");
            return;
        }
        if(!GatewayService.isEaRecentlyOnline(this)){
            status.setText("● QUOTE LIVE • TRADE EA OFFLINE • AUTO MENUNGGU");
            return;
        }
        long now=System.currentTimeMillis();
        long stateAt=gp.getLong("ea_order_state_at",0L);
        if(stateAt<=0L||now<stateAt||now-stateAt>8000L){
            status.setText("● QUOTE LIVE • STATUS ORDER EA BELUM SEGAR");
            return;
        }
        status.setText("● MT5 + CHART LIVE • SMART AUTO MEMANTAU • "+market+" • M1");
    }

    /** Keep last prices/spread visible when the feed pauses, but block all new entries. */
    private void markQuoteStale(){
        if(spreadView!=null)spreadView.setText(lastSpreadLabel.isEmpty()
            ?"SPREAD -- • STALE":lastSpreadLabel+" • STALE");
        if(status!=null)status.setText("● QUOTE STALE • ENTRY LOCKED • "+selectedTf);
        resetEntryReadyUi("harga belum segar");
    }

    /** Draw only broker-confirmed orders. A fresh NONE snapshot is authoritative closure. */
    private void syncBrokerEntryLine(){
        if(chart==null)return;
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        long now=System.currentTimeMillis(),at=gp.getLong("ea_order_state_at",0L);
        // A stale heartbeat is not proof of closure: preserve the last confirmed line
        // until MT5 sends a fresh state. This avoids both ghost-line clearing on outages
        // and stale-price lines being mistaken for live prices.
        if(at<=0L||now<at||now-at>ORDER_STATE_MAX_AGE_MS)return;
        String state=gp.getString("ea_order_state","UNKNOWN");
        if("NONE".equals(state)){
            chart.setPositionEntryLevel(0.0);
            return;
        }
        if(!"PENDING".equals(state)&&!"POSITION".equals(state))return;
        String orderSymbol=gp.getString("ea_order_state_symbol","");
        if(!mt5SymbolForMarket(selectedMarket).equals(orderSymbol)){
            chart.setPositionEntryLevel(0.0);
            return;
        }
        double mt5Entry=preferenceDouble(gp,"ea_order_state_price",0.0);
        double[] broker=freshBrokerQuote(selectedMarket);
        if(broker==null||!QuoteSafety.valid(chart.getBid(),chart.getAsk()))return;
        double line=BrokerOrderLinePolicy.displayPrice(state,mt5SymbolForMarket(selectedMarket),
            orderSymbol,mt5Entry,chart.getBid(),chart.getAsk(),broker[0],broker[1]);
        if(line>0.0)chart.setPositionEntryLevel(line);
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
        queueNativeIndicatorState();
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
                Candle[] m1="M1".equals(tf)?c:BiquoteClient.candles(market,"1m");
                Candle[] m5="M5".equals(tf)?c:BiquoteClient.candles(market,"5m");
                runOnUiThread(()->{
                    if(!tf.equals(selectedTf)||!market.equals(selectedMarket))return;
                    chart.setData(c);chart.goLive();m1Data=m1;m5Data=m5;
                    syncBrokerEntryLine();updateSignal(m1Data,m5Data);
                    status.setText("● CHART LOADED • "+market+" • "+tf);
                });
            }catch(Exception e){runOnUiThread(()->{status.setText("● OFFLINE • "+tf+" • RETRYING");resetEntryReadyUi("feed offline • menunggu data");});}
        }).start();

        if(ex==null){
            ex=Executors.newSingleThreadScheduledExecutor();
            ex.scheduleAtFixedRate(()->{
                String market=selectedMarket;
                double[] feedTick=null;
                try{feedTick=BiquoteClient.tick(market);}catch(Exception ignored){}
                final double[] fallbackTick=feedTick;
                runOnUiThread(()->{
                    if(!market.equals(selectedMarket))return;
                    applyQuoteSnapshot(market,fallbackTick);
                });
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
                    Candle[] freshM1="M1".equals(tfNow)?fresh:BiquoteClient.candles(market,"1m");
                    Candle[] freshM5="M5".equals(tfNow)?fresh:BiquoteClient.candles(market,"5m");
                    runOnUiThread(()->{
                        if(!market.equals(selectedMarket)||!tfNow.equals(selectedTf))return;
                        boolean wasFollowing=chart.isFollow();
                        chart.setData(fresh);
                        if(wasFollowing)chart.goLive();
                        m1Data=freshM1;m5Data=freshM5;
                        syncBrokerEntryLine();updateSignal(m1Data,m5Data);
                        status.setText("● OHLC REFRESHED • "+market+" • "+tfNow);
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
        // Keep signal levels in their candle-feed scale, but require an up-to-date
        // broker quote and chart quote before enqueuing a LIMIT.
        try {
            double[] broker=freshBrokerQuote(selectedMarket);
            if(broker==null||!hasFreshEntryQuotes()){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT: broker/chart quote tidak segar");
                Toast.makeText(this,"Harga broker/chart belum segar • order diblokir",Toast.LENGTH_LONG).show();
                return;
            }
            if(chart==null || chart.isEmpty() || m1Data==null || m1Data.length<30 || m5Data==null || m5Data.length<30){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT: data M1/M5 belum siap");
                Toast.makeText(this,"Data M1/M5 belum siap",Toast.LENGTH_SHORT).show();
                return;
            }
            Candle[] liveM1=m1Data;
            if(liveM1==null || liveM1.length<30){
                Toast.makeText(this,"Data candle M1 belum cukup",Toast.LENGTH_SHORT).show();
                return;
            }
            SignalResult s=SignalEngine.evaluate(liveM1,m5Data,chart.getBid(),chart.getAsk(),broker[0],broker[1]);
            lastSignal=s;
            double liveBid=chart.getBid(),liveAsk=chart.getAsk();

            // Manual and Smart Auto share one complete entry filter. Manual chooses
            // when to submit, but cannot bypass spread, ATR, wick, confidence, M5 or LIMIT checks.
            String blockReason=autoEntryBlockReason(s,liveBid,liveAsk,"M1".equals(selectedTf));
            if(!"READY".equals(blockReason)){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT • shared filter="+blockReason+
                    " • "+spreadAuditText(liveM1));
                Toast.makeText(this,"Order diblokir • "+blockReason,Toast.LENGTH_LONG).show();
                return;
            }
            if(!requestedSide.equals(s.side)){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT • arah berbeda dari signal READY="+s.side+
                    " • tidak melewati filter bersama");
                Toast.makeText(this,"Arah signal READY adalah "+s.side+" • "+requestedSide+" diblokir",Toast.LENGTH_LONG).show();
                return;
            }
            double target=validAutoTarget(s);
            if(target<=0.0){
                AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT • target TP tidak valid");
                Toast.makeText(this,"Target TP tidak valid",Toast.LENGTH_LONG).show();
                return;
            }
            double lot=readQuickLot();
            if(lot<=0){AppLog.add(this,"ORDER_BLOCKED",requestedSide+" LIMIT • lot tidak valid");return;}
            String orderSide="BUY".equals(requestedSide)?"BUY_LIMIT":"SELL_LIMIT";
            // Queue TP1 metadata just like Smart Auto, keeping EA exit protection consistent.
            boolean queued = queueLimit(orderSide,lot,s.entry,s.sl,target,s.tp1);
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
        if(chart==null || m1Data==null || m1Data.length<30 || m5Data==null || m5Data.length<30){
            Toast.makeText(this,"Data M1/M5 belum siap",Toast.LENGTH_SHORT).show();
            return;
        }
        double[] broker=freshBrokerQuote(selectedMarket);
        if(broker==null||!hasFreshEntryQuotes()){
            Toast.makeText(this,"Harga broker/chart belum segar • order diblokir",Toast.LENGTH_LONG).show();
            return;
        }
        Candle[] liveM1=m1Data;
        double liveBid=chart.getBid(), liveAsk=chart.getAsk();
        SignalResult s=SignalEngine.evaluate(liveM1,m5Data,liveBid,liveAsk,broker[0],broker[1]);
        lastSignal=s;

        String blockReason=autoEntryBlockReason(s,liveBid,liveAsk,"M1".equals(selectedTf));
        if(!"READY".equals(blockReason)){
            AppLog.add(this,"ORDER_BLOCKED","Manual chart entry • shared filter="+blockReason+
                " • "+spreadAuditText(liveM1));
            Toast.makeText(this,"Setup diblokir • "+blockReason,Toast.LENGTH_LONG).show();
            return;
        }

        String side="BUY".equals(s.side)?"BUY_LIMIT":"SELL_LIMIT";
        double lot=lotForMarket();
        double target=validAutoTarget(s);
        if(target<=0.0||lot<=0.0){
            AppLog.add(this,"ORDER_BLOCKED","Manual chart entry • lot/target tidak valid");
            return;
        }
        boolean queued=queueLimit(side,lot,s.entry,s.sl,target,s.tp1);
        if(queued)startGateway();
    }

    private boolean isEntryReady(SignalResult signal,double bid,double ask){
        if(signal==null||bid<=0||ask<=0)return false;
        if(!hasFreshEntryQuotes())return false;
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

    private String autoEntryBlockReason(SignalResult signal,double bid,double ask,boolean m1Context){
        if(!m1Context)return "TF BUKAN M1 / DATA M1-M5 BELUM CUKUP";
        if(signal==null)return "SIGNAL BELUM TERSEDIA";
        if(!hasFreshEntryQuotes())return "QUOTE BROKER/CHART STALE";
        if(!"BUY".equals(signal.side)&&!"SELL".equals(signal.side))return "BELUM ADA ARAH BUY/SELL";
        if(signal.score<getMinimumEntryConfidence())return "SCORE DI BAWAH MINIMUM "+getMinimumEntryConfidence()+"%";
        // ATR validity comes first because spread acceptance also requires valid ATR.
        if(!signal.atrFilter)return "ATR FILTER GAGAL";
        if(!signal.spreadFilter)return "SPREAD FILTER GAGAL";
        if(!signal.wickRejection)return "WICK REJECTION BELUM VALID";
        // A confirmed sweep/rejection can be an early counter-bias scalp; M5 is context,
        // not a blanket veto against BUY wick setups.
        if(!m5DoesNotOppose(signal.side,signal.m5Bias)&&!signal.liquiditySweep)return "ARAH M5 BERLAWANAN";
        if(signal.entry<=0||signal.sl<=0||signal.tp1<=0)return "LEVEL ENTRY/SL/TP TIDAK VALID";
        if("BUY".equals(signal.side)&&!(signal.entry<ask&&signal.sl<signal.entry&&signal.tp1>signal.entry))return "LEVEL BUY LIMIT/SL/TP TIDAK VALID";
        if("SELL".equals(signal.side)&&!(signal.entry>bid&&signal.tp1<signal.entry&&signal.sl>signal.entry))return "LEVEL SELL LIMIT/SL/TP TIDAK VALID";
        if(validAutoTarget(signal)<=0)return "TARGET LIMIT TIDAK VALID";
        return "READY";
    }

    private boolean isAutoEntryEligible(SignalResult signal,double bid,double ask,boolean m1Context){
        return "READY".equals(autoEntryBlockReason(signal,bid,ask,m1Context));
    }

    /** Show why broker quote freshness failed instead of collapsing missing and stale into one label. */
    private String brokerQuoteAuditText(){
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String symbol=mt5SymbolForMarket(selectedMarket);
        long now=System.currentTimeMillis();
        long received=gp.getLong("broker_quote_received_ms_"+symbol,0L);
        long tickAt=gp.getLong("broker_quote_tick_ms_"+symbol,0L);
        double bid=preferenceDouble(gp,"broker_quote_bid_"+symbol,0.0);
        double ask=preferenceDouble(gp,"broker_quote_ask_"+symbol,0.0);
        String state=(received<=0L||tickAt<=0L)?"MISSING"
            :(QuoteSafety.isFresh(received,tickAt,now)?"FRESH":"STALE");
        String receiptAge=received<=0L?"NA":Long.toString(now-received);
        String tickAge=tickAt<=0L?"NA":Long.toString(now-tickAt);
        return String.format(Locale.US,
            "brokerQuote=%s symbol=%s cachedBid=%.5f cachedAsk=%.5f receiptAgeMs=%s tickAgeMs=%s",
            state,symbol,bid,ask,receiptAge,tickAge);
    }

    /** Actual MT5 spread and the active ATR threshold, included in block/queue audit logs. */
    private String spreadAuditText(Candle[] m1){
        double[] q=freshBrokerQuote(selectedMarket);
        String quoteAudit=brokerQuoteAuditText();
        if(q==null||!QuoteSafety.valid(q[0],q[1]))return quoteAudit;
        double atr=SmartLimitReentry.atr(m1,14);
        double spread=q[1]-q[0];
        double max=QuoteSafety.maxAllowedSpread(q[0],q[1],atr);
        double point=brokerPoint(getSharedPreferences("rayyan4_gateway",MODE_PRIVATE));
        String ratio=atr>0.0?String.format(Locale.US,"%.1f%%",100.0*spread/atr):"N/A";
        String points=point>0.0?String.format(Locale.US,"%.1f",spread/point):"N/A";
        return quoteAudit+" "+String.format(Locale.US,
            "brokerSymbol=%s BID=%.5f ASK=%.5f spread=%.5f points=%s ATR=%.5f maxSpread=%.5f spread/ATR=%s",
            mt5SymbolForMarket(selectedMarket),q[0],q[1],spread,points,atr,max,ratio);
    }

    private void updateEntryReadyUi(SignalResult signal,double bid,double ask,Candle[] activeM1,long candleStamp){
        String candleKey=selectedMarket+"|"+selectedTf+"|"+candleStamp;
        if(!candleKey.equals(readyCandleKey)){
            readyCandleKey=candleKey;
            readyCandleSide="WAIT";
        }
        boolean baseReady=isAutoEntryEligible(signal,bid,ask,"M1".equals(selectedTf));
        SharedPreferences actionPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        boolean nextReady=baseReady&&isOpenActionEntryReady(
            signal,activeM1,bid,ask,candleStamp,actionPrefs);
        String nextSide=nextReady?signal.side:"WAIT";
        // READY follows the newest valid wick direction, including a fresh opposite-side
        // rejection later in the same candle. Order lifecycle guards prevent duplicates.
        readyCandleSide=nextReady?nextSide:"WAIT";
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
            String blockReason=autoEntryBlockReason(signal,bid,ask,"M1".equals(selectedTf));
            if("READY".equals(blockReason)){
                String actionMode=actionPrefs.getString("open_action_entry_mode",OPEN_ACTION_SWEEP_FIRST);
                if(OPEN_ACTION_WAIT_BOUNCE.equals(actionMode)){
                    boolean armed=selectedMarket.equals(actionPrefs.getString("open_action_wait_market",""))
                        &&signal.side.equals(actionPrefs.getString("open_action_wait_side",""));
                    if(!signal.liquiditySweep&&!armed)blockReason="LIQUIDITY SWEEP BELUM VALID";
                    else if(armed)blockReason="MENUNGGU SATU PANTULAN KE UJUNG WICK";
                    else blockReason="MENUNGGU LIQUIDITY SWEEP";
                }else if(!signal.liquiditySweep){
                    blockReason="LIQUIDITY SWEEP BELUM VALID";
                }else{
                    blockReason="MENUNGGU SAPUAN 1/3 CANDLE";
                }
            }
            entryReadyView.setText("WAIT • "+blockReason);
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
        // Both controls stay tappable for manual orders. Visual emphasis follows
        // the actual ENTRY READY side, never quote freshness alone.
        boolean buyReady=entryReady&&"BUY".equals(readySide)&&hasFreshEntryQuotes();
        boolean sellReady=entryReady&&"SELL".equals(readySide)&&hasFreshEntryQuotes();
        int buyColor=buyReady?(blinkOn?Color.rgb(0,255,145):Color.rgb(0,112,70)):Color.rgb(32,75,58);
        int sellColor=sellReady?(blinkOn?Color.rgb(255,55,75):Color.rgb(132,18,35)):Color.rgb(76,42,48);
        buyLimitBtn.setBackground(entryButtonBg(buyColor,buyReady,10));
        sellLimitBtn.setBackground(entryButtonBg(sellColor,sellReady,10));
        buyLimitBtn.setText(buyReady?(blinkOn?"● BUY LIMIT":"BUY LIMIT"):"○ BUY LIMIT");
        sellLimitBtn.setText(sellReady?(blinkOn?"● SELL LIMIT":"SELL LIMIT"):"○ SELL LIMIT");
        // UI state mirrors the trading gate: only the side validated by ENTRY READY
        // is actionable. The click path still revalidates fresh quotes before queueing.
        // Keep both manual controls tappable from app launch. This does not bypass
        // price/order validation: executeLimitForSide and queueLimit still fail closed.
        buyLimitBtn.setEnabled(true);
        sellLimitBtn.setEnabled(true);
        buyLimitBtn.setAlpha(1.0f);
        sellLimitBtn.setAlpha(1.0f);
        buyLimitBtn.setTextColor(Color.WHITE);
        sellLimitBtn.setTextColor(Color.WHITE);
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

    /** Deduplicate independently by diagnostic category; READY logs must not reset FILTER logs. */
    private void autoDiagnostic(SharedPreferences p,String eventKey,String message){
        if(eventKey==null)eventKey="";
        int split=eventKey.indexOf('|');
        String category=split>0?eventKey.substring(0,split):eventKey;
        String prefKey="smart_diag_last_"+category;
        if(!eventKey.equals(p.getString(prefKey,""))){
            AppLog.add(this,"SMART AUTO DIAG",message);
            p.edit().putString(prefKey,eventKey)
                .putString("smart_diag_last_message",message)
                .putLong("smart_diag_last_at",System.currentTimeMillis()).apply();
        }
    }

    private void queueBlockDiagnostic(String reason){
        if(!"SMART_AUTO".equals(queueOrigin))return;
        String code="GENERAL";
        if(reason.startsWith("quote/chart stale"))code="QUOTE_STALE";
        else if(reason.startsWith("invalid side"))code="INVALID_SIDE";
        else if(reason.startsWith("invalid order"))code="INVALID_VALUES";
        else if(reason.startsWith("BUY LIMIT entry"))code="BUY_LIMIT_PRICE";
        else if(reason.startsWith("BUY geometry"))code="BUY_LIMIT_GEOMETRY";
        else if(reason.startsWith("SELL LIMIT entry"))code="SELL_LIMIT_PRICE";
        else if(reason.startsWith("SELL geometry"))code="SELL_LIMIT_GEOMETRY";
        else if(reason.startsWith("queue stale"))code="QUEUE_STALE";
        else if(reason.startsWith("gateway queue busy"))code="QUEUE_BUSY";
        else if(reason.startsWith("exception"))code="QUEUE_EXCEPTION";
        long candle=System.currentTimeMillis();
        try{
            if(chart!=null&&!chart.isEmpty()){
                Candle[] bars=chart.getDataSnapshot();
                if(bars!=null&&bars.length>0)candle=bars[bars.length-1].t;
            }
        }catch(Exception ignored){}
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        autoDiagnostic(p,"QUEUE_BLOCK_"+code+"|"+selectedMarket+"|"+candle,
            "QUEUE_REJECTED • "+reason+" • command not queued • market="+selectedMarket);
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
        // One same-side automatic entry per M1 candle. A different price/setup label
        // from every live tick must not create another SELL/BUY order on that same bar.
        return selectedMarket+"|"+s.side+"|"+candleStamp;
    }

    private boolean queueAutoSignal(SignalResult s,Candle[] m1,SharedPreferences gp,
                                    double lot,long candleStamp,String signalKey){
        SignalResult orderSignal=createOpenActionOrderSignal(s,gp,m1);
        if(orderSignal==null){
            smartLogOnce(gp,"OPEN_ACTION_LEVELS|"+signalKey,
                "AUTO tidak mengirim: offset LIMIT tidak valid untuk quote/SL; menunggu setup baru");
            return false;
        }
        double target=validAutoTarget(orderSignal);
        if(target<=0)return false;
        String orderSide="BUY".equals(orderSignal.side)?"BUY_LIMIT":"SELL_LIMIT";
        queueOrigin="SMART_AUTO";
        queueOutcomeTarget=orderSignal.tp1;
        boolean queued;
        try { queued=queueLimit(orderSide,lot,orderSignal.entry,orderSignal.sl,target,orderSignal.tp1); }
        finally { queueOrigin="MANUAL"; queueOutcomeTarget=0; }
        if(!queued)return false;
        String orderId=gp.getString("order_status_id","");
        long now=System.currentTimeMillis();
        gp.edit().putString("auto_last_signal_key",signalKey)
            .putLong("auto_last_order_ms",now)
            .putString("smart_phase","QUEUED")
            .putString("smart_active_id",orderId)
            .putString("smart_active_side",orderSignal.side)
            .putString("smart_active_symbol",mt5SymbolForMarket(selectedMarket))
            .putString("smart_active_entry",Double.toString(orderSignal.entry))
            .putString("smart_active_ticket","")
            .putLong("smart_active_start_candle_ms",candleStamp)
            .putLong("smart_active_queue_ms",now)
            .putBoolean("smart_reentry_allowed",false)
            .remove("smart_cancel_command_id")
            .remove("open_action_wait_market")
            .remove("open_action_wait_side")
            .remove("open_action_wait_anchor")
            .remove("open_action_wait_wick_length")
            .remove("open_action_wait_candle_ms")
            .remove("open_action_wait_bounce_confirmed")
            .remove("open_action_wait_bounce_price")
            .apply();
        String queuedMode=gp.getString("open_action_entry_mode",OPEN_ACTION_SWEEP_FIRST);
        String offsetAudit=OPEN_ACTION_WAIT_BOUNCE.equals(queuedMode)
            ?"saved wick-tip edge + adaptive tick offset":"10 MT5 points from live quote";
        AppLog.add(this,"AUTO LIMIT","queued "+signalKey+" mode="+queuedMode
            +" entryPolicy="+offsetAudit+" entry="+price(orderSignal.entry)
            +" SL="+price(orderSignal.sl)+" TP="+price(target)+"; locked pending EA state");
        return true;
    }

    /** Use broker SYMBOL_POINT, not a guessed pip size, for all open-action offsets. */
    private double brokerPoint(SharedPreferences gp){
        String symbol=mt5SymbolForMarket(selectedMarket);
        double point=preferenceDouble(gp,"broker_quote_point_"+symbol,0.0);
        if(point>0.0)return point;
        int digits=gp.getInt("broker_quote_digits_"+symbol,-1);
        return digits>=0&&digits<=10?Math.pow(10.0,-digits):0.0;
    }

    private double normalizeBrokerPrice(double value,SharedPreferences gp){
        int digits=gp.getInt("broker_quote_digits_"+mt5SymbolForMarket(selectedMarket),3);
        digits=Math.max(0,Math.min(10,digits));
        return Double.parseDouble(String.format(Locale.US,"%."+digits+"f",value));
    }

    private void clearOpenActionWait(SharedPreferences gp){
        gp.edit().remove("open_action_wait_market").remove("open_action_wait_side")
            .remove("open_action_wait_anchor").remove("open_action_wait_wick_length")
            .remove("open_action_wait_candle_ms").remove("open_action_wait_bounce_confirmed")
            .remove("open_action_wait_bounce_price").apply();
    }

    /**
     * SWEEP_FIRST waits for a one-third candle excursion after the new M1 opens.
     * WAIT_BOUNCE remembers the real wick extreme and accepts one recoil only.
     */
    private boolean isOpenActionEntryReady(SignalResult s,Candle[] m1,double bid,double ask,
                                           long candleStamp,SharedPreferences gp){
        if(s==null||m1==null||m1.length<2
                ||(!"BUY".equals(s.side)&&!"SELL".equals(s.side))||!s.wickRejection)
            return false;
        String mode=gp.getString("open_action_entry_mode",OPEN_ACTION_SWEEP_FIRST);
        String symbol=selectedMarket;
        String side=s.side;
        double atr=SmartLimitReentry.atr(m1,14);
        Candle live=m1[m1.length-1];

        if(!OPEN_ACTION_WAIT_BOUNCE.equals(mode)){
            if(!s.liquiditySweep)return false;
            boolean travelled=OpenActionEntryPolicy.hasOneThirdCandleTravel(m1,side,atr);
            if(!travelled){
                double threshold=OpenActionEntryPolicy.oneThirdTravelThreshold(m1[m1.length-2],atr);
                double excursion="BUY".equals(side)?live.o-live.l:live.h-live.o;
                String size=atr>0.0?(live.h-live.l)>=atr*1.25?"LONG"
                    :(live.h-live.l)<=atr*0.55?"SHORT":"NORMAL":"ATR_PENDING";
                smartLogOnce(gp,"OPEN_ACTION_SWEEP_WAIT|"+symbol+"|"+side+"|"+candleStamp,
                    "OPEN ACTION SAPUAN WAIT • side="+side+" excursion="+price(Math.max(0.0,excursion))+
                    " threshold="+price(threshold)+" prevRange="+price(m1[m1.length-2].h-m1[m1.length-2].l)+
                    " ATR="+price(atr)+" candleSize="+size+
                    ("BUY".equals(side)?" • BUY menunggu turun 1/3 range":" • SELL menunggu naik 1/3 range"));
                if(status!=null)status.setText("● OPEN ACTION WAIT • "+side+" 1/3 CANDLE");
            }
            return travelled;
        }

        String waitMarket=gp.getString("open_action_wait_market","");
        String waitSide=gp.getString("open_action_wait_side","");
        long started=gp.getLong("open_action_wait_candle_ms",0L);
        boolean sameSetup=symbol.equals(waitMarket)&&side.equals(waitSide)&&started>0L;

        if(!sameSetup){
            if(!s.liquiditySweep)return false;
            double tip="BUY".equals(side)?live.l:live.h;
            double wickLength="BUY".equals(side)
                ?Math.max(0.0,Math.min(live.o,live.c)-live.l)
                :Math.max(0.0,live.h-Math.max(live.o,live.c));
            if(!Double.isFinite(tip)||tip<=0.0)return false;
            clearOpenActionWait(gp);
            gp.edit().putString("open_action_wait_market",symbol)
                .putString("open_action_wait_side",side)
                .putString("open_action_wait_anchor",Double.toString(tip))
                .putString("open_action_wait_wick_length",Double.toString(wickLength))
                .putLong("open_action_wait_candle_ms",candleStamp)
                .putBoolean("open_action_wait_bounce_confirmed",false).apply();
            smartLogOnce(gp,"OPEN_ACTION_BOUNCE_ARMED|"+symbol+"|"+side+"|"+candleStamp,
                "OPEN ACTION PANTULAN ARMED • side="+side+" wickTip="+price(tip)+
                " wickLength="+price(wickLength)+" • menunggu satu pantulan dari ujung wick");
            if(status!=null)status.setText("● OPEN ACTION WAIT • PANTULAN "+side);
            return false;
        }

        int elapsed=SmartLimitReentry.elapsedM1Bars(started,candleStamp);
        if(elapsed>2){
            clearOpenActionWait(gp);
            smartLogOnce(gp,"OPEN_ACTION_BOUNCE_EXPIRED|"+symbol+"|"+side+"|"+candleStamp,
                "OPEN ACTION PANTULAN EXPIRED • tidak ada pantulan valid dalam 2 candle M1; tunggu sweep baru");
            return false;
        }
        if(gp.getBoolean("open_action_wait_bounce_confirmed",false))return true;

        double point=brokerPoint(gp);
        double anchor=preferenceDouble(gp,"open_action_wait_anchor",0.0);
        double wickLength=preferenceDouble(gp,"open_action_wait_wick_length",0.0);
        if(point<=0.0||atr<=0.0||anchor<=0.0||!Double.isFinite(live.c))return false;

        // While waiting, follow a newly extended low/high. Once a bounce is confirmed,
        // the tip is frozen so later oscillations cannot create a second bounce signal.
        double newTip="BUY".equals(side)?live.l:live.h;
        double epsilon=Math.max(point,Math.abs(anchor)*1e-8);
        boolean extended="BUY".equals(side)?newTip<anchor-epsilon:newTip>anchor+epsilon;
        if(extended){
            anchor=newTip;
            wickLength="BUY".equals(side)
                ?Math.max(0.0,Math.min(live.o,live.c)-live.l)
                :Math.max(0.0,live.h-Math.max(live.o,live.c));
            gp.edit().putString("open_action_wait_anchor",Double.toString(anchor))
                .putString("open_action_wait_wick_length",Double.toString(wickLength))
                .putBoolean("open_action_wait_bounce_confirmed",false).apply();
        }

        double threshold=OpenActionEntryPolicy.bounceThreshold(atr,wickLength,point);
        double current=live.c; // Use the same candle-feed price scale as the saved wick tip.
        if(threshold<=0.0)return false;
        boolean bounced="BUY".equals(side)?current>=anchor+threshold:current<=anchor-threshold;
        if(bounced){
            gp.edit().putBoolean("open_action_wait_bounce_confirmed",true)
                .putString("open_action_wait_bounce_price",Double.toString(current)).apply();
            AppLog.add(this,"OPEN ACTION","ONE BOUNCE CONFIRMED side="+side+
                " wickTip="+price(anchor)+" current="+price(current)+
                " movement="+price(Math.abs(current-anchor))+" threshold="+price(threshold)+
                " • wick tip frozen; next LIMIT targets wick edge");
            return true;
        }
        if(status!=null)status.setText("● OPEN ACTION WAIT • PANTULAN "+side+" KE UJUNG WICK");
        return false;
    }

    /**
     * In WAIT_BOUNCE, place the pending LIMIT beside the saved wick tip, with SL
     * beyond that tip. Sweep-first retains the existing near-market LIMIT approach.
     */
    private SignalResult createOpenActionOrderSignal(SignalResult s,SharedPreferences gp,Candle[] m1){
        if(s==null||chart==null||!QuoteSafety.valid(chart.getBid(),chart.getAsk()))return null;
        double point=brokerPoint(gp);
        if(point<=0.0)return null;
        double bid=chart.getBid(),ask=chart.getAsk();
        boolean wickTipMode=OPEN_ACTION_WAIT_BOUNCE.equals(
            gp.getString("open_action_entry_mode",OPEN_ACTION_SWEEP_FIRST));
        double entry,sl;
        double atr=SmartLimitReentry.atr(m1,14);
        String setupSuffix;

        if(wickTipMode){
            if(m1==null||m1.length<2||atr<=0.0)return null;
            Candle live=m1[m1.length-1];
            double savedTip=preferenceDouble(gp,"open_action_wait_anchor",0.0);
            double wickLength=preferenceDouble(gp,"open_action_wait_wick_length",0.0);
            double offset=OpenActionEntryPolicy.wickTipOffset(wickLength,atr,point);
            if(savedTip<=0.0||offset<=0.0)return null;

            // Candle OHLC comes from the chart feed, while current Bid/Ask are from MT5.
            // Translate the saved feed wick edge to the current broker price coordinate.
            double priceShift=((bid+ask)*0.5)-live.c;
            double brokerTip=savedTip+priceShift;
            entry="BUY".equals(s.side)?brokerTip+offset:brokerTip-offset;
            double stopDistance=Math.max(atr*0.12,(ask-bid)*2.0);
            sl="BUY".equals(s.side)?brokerTip-stopDistance:brokerTip+stopDistance;
            setupSuffix=" • OPEN ACTION WICK TIP / ONE BOUNCE";
        }else{
            entry="BUY".equals(s.side)?ask-point*AUTO_ENTRY_OFFSET_POINTS
                :"SELL".equals(s.side)?bid+point*AUTO_ENTRY_OFFSET_POINTS:0.0;
            sl=s.sl;
            setupSuffix=" • OPEN ACTION SAPUAN 1/3";
        }
        if(entry<=0.0||!Double.isFinite(entry)||!Double.isFinite(sl))return null;
        entry=normalizeBrokerPrice(entry,gp);
        sl=normalizeBrokerPrice(sl,gp);
        double tp1,tp2,risk;
        if("BUY".equals(s.side)){
            if(!(entry<ask&&sl<entry))return null;
            risk=entry-sl;
            tp1=entry+2.0*risk;
            tp2=entry+3.5*risk;
        }else if("SELL".equals(s.side)){
            if(!(entry>bid&&sl>entry))return null;
            risk=sl-entry;
            tp1=entry-2.0*risk;
            tp2=entry-3.5*risk;
            if(tp2<=0.0)return null;
        }else return null;
        sl=normalizeBrokerPrice(sl,gp);
        tp1=normalizeBrokerPrice(tp1,gp);
        tp2=normalizeBrokerPrice(tp2,gp);
        if("BUY".equals(s.side)&&!(sl<entry&&tp1>entry&&tp2>tp1))return null;
        if("SELL".equals(s.side)&&!(tp2<tp1&&tp1<entry&&entry<sl))return null;
        return new SignalResult(s.side,s.m5Bias,entry,sl,tp1,tp2,3.5,s.score,s.confidence,
            s.setup+setupSuffix,s.liquiditySweep,s.wickRejection,s.bos,s.emaFilter,s.rsiFilter,
            s.macdFilter,s.atrFilter,s.spreadFilter);
    }

    private void processAutoLimit(SignalResult s,Candle[] m1,Candle[] m5,
                                  double bid,double ask,long candleStamp){
        SharedPreferences gp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        // Keep monitoring/cancelling existing exposure even if AUTO is paused or the
        // chart timeframe changes. New/replacement entries still require AUTO+READY+M1.
        boolean autoEnabled=gp.getBoolean("auto",false)
                &&"READY".equals(gp.getString("operation_mode","REST"))
                &&"M1".equals(selectedTf);
        if(m1==null||m1.length<30||m5==null||m5.length<30){
            autoDiagnostic(gp,"DATA_M1M5_SHORT|"+selectedMarket+"|"+selectedTf,
                "ENTRY BLOCKED • data M1/M5 kurang dari 30 candle • market="+selectedMarket+" TF="+selectedTf);
            return;
        }

        long now=System.currentTimeMillis();
        boolean eaOnline=GatewayService.isEaRecentlyOnline(this);
        long stateAt=gp.getLong("ea_order_state_at",0L);
        String remoteState=gp.getString("ea_order_state","UNKNOWN");
        String remoteId=gp.getString("ea_order_state_id","");
        boolean stateFresh=stateAt>0L&&now>=stateAt&&now-stateAt<=8000L&&eaOnline;
        String signalKey=makeSignalKey(s,candleStamp);
        boolean readyCandidate=isAutoEntryEligible(s,bid,ask,"M1".equals(selectedTf));
        if(readyCandidate){
            autoDiagnostic(gp,"READY_SEEN|"+signalKey,
                "READY TERDETEKSI • "+signalKey+" • score="+s.score+" conf="+s.confidence+
                "/5 wick="+s.wickRejection+" sweep="+s.liquiditySweep+" spreadOK="+s.spreadFilter+
                " M5="+s.m5Bias+" • tahap berikutnya memerlukan gate Smart Auto");
        }
        if(readyCandidate&&autoEnabled){
            autoDiagnostic(gp,"READY_AUTO_ON|"+signalKey,
                "READY + SMART AUTO ON • memeriksa EA heartbeat, state order, Open Action, antrean, lot, cooldown");
        }
        if(!eaOnline){
            autoDiagnostic(gp,"EA_OFFLINE|"+(readyCandidate?signalKey:selectedMarket+"|"+selectedTf),
                "ENTRY BLOCKED • EA MT5 /next offline • ready="+readyCandidate+" • tidak mengirim order");
            if(status!=null&&autoEnabled)status.setText("● SMART AUTO BLOCKED • EA /next OFFLINE");
            return;
        }
        if(!stateFresh){
            autoDiagnostic(gp,"STATE_STALE|"+(readyCandidate?signalKey:selectedMarket+"|"+selectedTf),
                "ENTRY BLOCKED • status order MT5 stale/heartbeat >8s • state="+remoteState+
                " ageMs="+(stateAt>0?Math.max(0L,now-stateAt):-1L)+" • tidak membuat order duplikat");
            if(status!=null&&autoEnabled)status.setText("● SMART AUTO BLOCKED • STATUS ORDER MT5 STALE");
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
        // READY can coexist with an already active order/position; this lifecycle lock is deliberate.
        if(readyCandidate&&("PENDING".equals(remoteState)||"POSITION".equals(remoteState))){
            autoDiagnostic(gp,"ACTIVE_EXPOSURE|"+signalKey+"|"+remoteId+"|"+remoteState,
                "READY TIDAK DIKIRIM • MT5 masih "+remoteState+" • id="+remoteId+
                " • phase="+gp.getString("smart_phase","IDLE")+" • menunggu exposure selesai agar tidak double-entry");
        }else if(readyCandidate&&!"NONE".equals(remoteState)){
            autoDiagnostic(gp,"STATE_NOT_FLAT|"+signalKey+"|"+remoteState,
                "READY DITAHAN • EA state="+remoteState+" bukan NONE • menunggu state MT5 terkonfirmasi");
        }
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
                autoDiagnostic(gp,"MT5_POSITION|"+activeId+"|"+gp.getString("ea_order_state_ticket",""),
                    "STAGE MT5_POSITION • EA melaporkan posisi terbuka • id="+activeId+
                    " ticket="+gp.getString("ea_order_state_ticket","")+" symbol="+activeSymbol+
                    " • verifikasi volume/harga/SL/TP pada tab Trade MT5");
                if(!"POSITION".equals(phase)){
                    gp.edit().putString("smart_phase","POSITION").apply();
                    AppLog.add(this,"SMART LIMIT","position detected; AUTO locked until EA reports no position");
                }
                return;
            }

            autoDiagnostic(gp,"MT5_PENDING|"+activeId+"|"+gp.getString("ea_order_state_ticket",""),
                "STAGE MT5_PENDING • EA melaporkan pending order ada di MT5 • id="+activeId+
                " ticket="+gp.getString("ea_order_state_ticket","")+" symbol="+activeSymbol+
                " • pending belum sama dengan posisi terisi");
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

            // Pending expiry is a safety rule, not a re-entry preference. Even when
            // SMART RE-ENTRY is disabled, a stale LIMIT must still be cancelled.
            boolean reentryEnabled=gp.getBoolean("smart_reentry_enabled",true);
            int elapsed=SmartLimitReentry.elapsedM1Bars(startedAt,candleStamp);
            boolean movedAway=hasFreshEntryQuotes()
                &&SmartLimitReentry.movedAwayInFavor(sideNow,activeEntry,bid,ask,atr);
            boolean continuation=SmartLimitReentry.hasContinuation(m1,sideNow)
                &&m5DoesNotOppose(sideNow,s.m5Bias);
            boolean validFresh=isAutoEntryEligible(s,bid,ask,true)
                &&readyCandleKey.equals(selectedMarket+"|"+selectedTf+"|"+candleStamp)
                &&readyCandleSide.equals(s.side)
                &&isOpenActionEntryReady(s,m1,bid,ask,candleStamp,gp);
            double maxDistanceAtr=smartReentryMaxDistanceAtr(gp);
            boolean withinMaxDistance=SmartLimitReentry.withinMaxDistanceFromOriginalEntry(
                activeEntry,s.entry,atr,maxDistanceAtr);
            boolean sideChanged=validFresh&&!s.side.equals(sideNow);
            boolean entryChanged=validFresh&&SmartLimitReentry.materiallyDifferentEntry(activeEntry,s.entry,atr);
            // A distinct, validated wick setup may replace a pending LIMIT in the same candle.
            // Later-bar same-side replacements still require movement and continuation checks.
            int maxPendingBars=Math.max(2,smartReentryMaxAgeBars(gp));
            // Keep an untouched LIMIT through candle changes; the expected pullback may
            // touch it on the next candle. Expiry uses the configured age (minimum 2 bars).
            boolean maxAge=elapsed>=maxPendingBars;
            // A fresh opposite-side wick is a valid same-candle replacement candidate.
            // Same-side price drift alone cannot repeatedly cancel/requeue the same setup.
            boolean replacement=reentryEnabled&&autoEnabled&&elapsed<maxPendingBars
                &&validFresh&&(sideChanged
                    ||(entryChanged&&withinMaxDistance&&movedAway&&continuation));
            boolean structureInvalid=validFresh&&!s.side.equals(sideNow);
            long retryAfter=gp.getLong("smart_cancel_retry_after_ms",0L);
            boolean shouldCancel=structureInvalid||maxAge||(reentryEnabled&&replacement);
            if(shouldCancel&&now>=retryAfter){
                boolean queued=queueCancelPending(activeId,activeTicket,activeSymbol);
                if(queued){
                    String cancelCommandId=gp.getString("order_status_id","");
                    boolean allowReplacement=reentryEnabled&&replacement;
                    gp.edit().putString("smart_phase","CANCEL_REQUESTED")
                        .putString("smart_cancel_command_id",cancelCommandId)
                        .putBoolean("smart_reentry_allowed",allowReplacement)
                        .putString("smart_cancel_reason",structureInvalid?"fresh opposite-side wick validated"
                            :(replacement?"new validated wick setup":"pending LIMIT reached configured age"))
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
                    boolean allow=autoEnabled&&gp.getBoolean("smart_reentry_allowed",false);
                    boolean replacementSideChanged=!s.side.equals(sideNow);
                    boolean candidate=allow&&hasFreshEntryQuotes()
                        &&isAutoEntryEligible(s,bid,ask,true)
                        &&readyCandleKey.equals(selectedMarket+"|"+selectedTf+"|"+candleStamp)
                        &&readyCandleSide.equals(s.side)
                        &&isOpenActionEntryReady(s,m1,bid,ask,candleStamp,gp)
                        &&SmartLimitReentry.elapsedM1Bars(startedAt,candleStamp)
                            <smartReentryMaxAgeBars(gp)
                        &&SmartLimitReentry.withinMaxDistanceFromOriginalEntry(activeEntry,s.entry,atr,
                            smartReentryMaxDistanceAtr(gp))
                        &&(replacementSideChanged
                            ||SmartLimitReentry.materiallyDifferentEntry(activeEntry,s.entry,atr));
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
        if(!"NONE".equals(remoteState)){
            if(status!=null&&autoEnabled)status.setText("● SMART AUTO BLOCKED • EA STATE "+remoteState);
            return;
        }
        if(!autoEnabled){
            String why=!gp.getBoolean("auto",false)?"AUTO_SWITCH_OFF":
                !"READY".equals(gp.getString("operation_mode","REST"))?"OPERATION_MODE_REST":
                !"M1".equals(selectedTf)?"TIMEFRAME_NOT_M1":"AUTO_GATE_DISABLED";
            if(readyCandidate)autoDiagnostic(gp,"AUTO_GATE|"+signalKey+"|"+why,
                "READY DITAHAN • "+why+" • aktifkan Smart Auto + MODE SIAP TRADING + chart M1");
            return;
        }
        if(!queueIsFree(gp)){
            String command=gp.getString("next","");
            String queuedId="";
            for(String part:command.split(";"))if(part.startsWith("id=")){queuedId=part.substring(3);break;}
            autoDiagnostic(gp,"QUEUE_BUSY|"+signalKey+"|"+queuedId,
                "READY TIDAK DIKIRIM • Gateway antrean masih terisi • id="+queuedId+
                " • order_status="+gp.getString("order_status","UNKNOWN")+
                " • detail="+gp.getString("order_status_detail","")+
                " • antrean tidak ditimpa demi mencegah order ganda");
            if(status!=null)status.setText("● SMART AUTO WAIT • GATEWAY QUEUE BUSY");
            return;
        }

        boolean m1Context="M1".equals(selectedTf)&&m1.length>=30&&m5.length>=30;
        if(!isAutoEntryEligible(s,bid,ask,m1Context)){
            String reason=autoEntryBlockReason(s,bid,ask,m1Context);
            autoDiagnostic(gp,"FILTER|"+signalKey+"|"+reason,
                "FILTER BLOCK • reason="+reason+" • side="+s.side+" score="+s.score+
                " conf="+s.confidence+"/5 wick="+s.wickRejection+" sweep="+s.liquiditySweep+
                " M5="+s.m5Bias+" spreadOK="+s.spreadFilter+" atrOK="+s.atrFilter+
                " entry="+price(s.entry)+" bid="+price(bid)+" ask="+price(ask)+
                " • "+spreadAuditText(m1));
            if(status!=null)status.setText("● SMART AUTO WAIT • "+reason);
            return;
        }
        // Two explicit open-action modes share the existing EA/ACK and anti-duplicate guards.
        // Both require a true sweep; the second waits for a favourable bounce first.
        if(!isOpenActionEntryReady(s,m1,bid,ask,candleStamp,gp)){
            String mode=gp.getString("open_action_entry_mode",OPEN_ACTION_SWEEP_FIRST);
            String waitSide=gp.getString("open_action_wait_side","");
            String waitMarket=gp.getString("open_action_wait_market","");
            long waitStart=gp.getLong("open_action_wait_candle_ms",0L);
            boolean bounceDone=gp.getBoolean("open_action_wait_bounce_confirmed",false);
            int waitBars=waitStart>0?SmartLimitReentry.elapsedM1Bars(waitStart,candleStamp):-1;
            String gate=!s.wickRejection?"WICK_REJECTION_FALSE":
                !s.liquiditySweep?"LIQUIDITY_SWEEP_REQUIRED":
                OPEN_ACTION_WAIT_BOUNCE.equals(mode)
                    ?(bounceDone?"BOUNCE_CONFIRMED_BUT_ORDER_GATES_RECHECK":"WAITING_FOR_PRICE_BOUNCE")
                    :"SWEEP_FIRST_GATE_NOT_CONFIRMED";
            autoDiagnostic(gp,"OPEN_ACTION|"+signalKey+"|"+mode+"|"+gate+"|"+waitBars,
                "OPEN ACTION BLOCK • reason="+gate+" mode="+mode+" side="+s.side+
                " wick="+s.wickRejection+" sweep="+s.liquiditySweep+
                " waitMarket="+waitMarket+" waitSide="+waitSide+" bounceConfirmed="+bounceDone+
                " waitM1Bars="+waitBars+" • READY visual tidak otomatis berarti Open Action terpenuhi");
            if(status!=null)status.setText(OPEN_ACTION_WAIT_BOUNCE.equals(mode)
                ?"● SMART AUTO WAIT • MENUNGGU PANTULAN":"● SMART AUTO WAIT • MENUNGGU SWEEP VALID");
            return;
        }
        // AUTO LIMIT must use the same per-candle direction accepted by READY UI.
        // updateEntryReadyUi runs immediately before processAutoLimit in updateSignal.
        long liveStamp=m1[m1.length-1].t;
        String liveCandleKey=selectedMarket+"|"+selectedTf+"|"+liveStamp;
        if(!liveCandleKey.equals(readyCandleKey)
                ||"WAIT".equals(readyCandleSide)
                ||!readyCandleSide.equals(s.side)){
            autoDiagnostic(gp,"READY_DIRECTION_MISMATCH|"+signalKey+"|"+liveCandleKey,
                "READY DITAHAN • arah signal="+s.side+" tidak sama dengan arah READY terkunci="+readyCandleSide+
                " • signalKey="+liveCandleKey+" • menunggu READY/candle sinkron");
            return;
        }
        double lot=lotForMarket();
        double minLot=minLotForSymbol(mt5SymbolForMarket(selectedMarket));
        if(!Double.isFinite(lot)||lot<=0||lot+1e-8<minLot){
            autoDiagnostic(gp,"LOT_INVALID|"+signalKey+"|"+lotText(lot)+"|"+lotText(minLot),
                "READY DITAHAN • lot invalid/di bawah minimum • lot="+lotText(lot)+
                " minimum="+lotText(minLot)+" symbol="+mt5SymbolForMarket(selectedMarket));
            return;
        }
        if(signalKey.equals(gp.getString("auto_last_signal_key",""))
                ||signalKey.equals(gp.getString("smart_blocked_signal_key",""))){
            String duplicateOf=signalKey.equals(gp.getString("auto_last_signal_key",""))
                ?"auto_last_signal_key":"smart_blocked_signal_key";
            autoDiagnostic(gp,"DUPLICATE_SIGNAL|"+signalKey+"|"+duplicateOf,
                "READY DITAHAN • setup ini sudah diproses pada candle yang sama • gate="+duplicateOf+
                " • menunggu setup/candle baru untuk mencegah duplicate entry");
            return;
        }
        long lastAuto=gp.getLong("auto_last_order_ms",0L);
        if(now-lastAuto<60000L){
            long remainingMs=60000L-(now-lastAuto);
            autoDiagnostic(gp,"COOLDOWN|"+signalKey+"|"+(lastAuto/1000L),
                "READY DITAHAN • cooldown global aktif • tersisa sekitar "+((remainingMs+999L)/1000L)+
                " detik dari 60 detik • waktu perintah terakhir="+lastAuto+
                " • gate ini sedang dipantau pada Tahap 1");
            return;
        }
        double target=validAutoTarget(s);
        if(target<=0){
            autoDiagnostic(gp,"TARGET_INVALID|"+signalKey,
                "READY DITAHAN • target TP tidak valid • entry="+price(s.entry)+
                " TP1="+price(s.tp1)+" TP2="+price(s.tp2)+" side="+s.side);
            return;
        }
        if(!queueAutoSignal(s,m1,gp,lot,candleStamp,signalKey)){
            autoDiagnostic(gp,"QUEUE_REJECTED|"+signalKey+"|"+gp.getString("order_status","UNKNOWN"),
                "READY LOLOS FILTER, TETAPI APK GAGAL MEMASUKKAN ANTREAN • order_status="+
                gp.getString("order_status","UNKNOWN")+" detail="+gp.getString("order_status_detail","")+
                " • cek log QUEUE_REJECTED untuk validasi harga/lot/antrean");
        }else{
            autoDiagnostic(gp,"APK_QUEUE_ACCEPTED|"+gp.getString("order_status_id",""),
                "STAGE APK_QUEUE_ACCEPTED • perintah disimpan di antrean APK; ini BELUM berarti order diterima/terisi MT5");
            gp.edit().remove("smart_last_log_key").apply();
        }
    }

    private boolean queueIsFree(SharedPreferences p){
        String queue=p.getString("next","");
        return queue==null||queue.trim().isEmpty();
    }

    void updateSignal(Candle[] m1,Candle[] m5){
        syncBrokerEntryLine();
        if(m1==null||m1.length<30||m5==null||m5.length<30){
            if(scoreView!=null)scoreView.setText("SCORE 0%");
            resetEntryReadyUi("menunggu data M1/M5");
            return;
        }
        double bid=chart==null?0:chart.getBid(),ask=chart==null?0:chart.getAsk();
        double[] brokerForSignal=freshBrokerQuote(selectedMarket);
        double spreadBid=brokerForSignal==null?0.0:brokerForSignal[0];
        double spreadAsk=brokerForSignal==null?0.0:brokerForSignal[1];
        SignalResult previous=lastSignal;
        lastSignal=SignalEngine.evaluate(m1,m5,bid,ask,spreadBid,spreadAsk);
        SignalResult s=lastSignal;

        int displayScore=Math.max(0,Math.min(100,s.score));
        scoreView.setText("SCORE "+displayScore+"%");
        scoreView.setTextSize(11);
        scoreView.setSingleLine(true);
        scoreView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        scoreView.setIncludeFontPadding(false);
        scoreView.setGravity(Gravity.CENTER);
        scoreView.setTextColor(displayScore>=75?Color.YELLOW:displayScore>=60?Color.CYAN:Color.WHITE);
        boolean directional="BUY".equals(s.side)||"SELL".equals(s.side);
        signalSummary.setText((directional?("M1 "+s.side+" • M5 "+s.m5Bias):"WAIT • "+s.m5Bias)+"\n"+s.setup+" • CONF "+s.confidence+"/5");
        entryView.setText("Entry\n"+price(directional?s.entry:0));slView.setText("SL\n"+price(directional?s.sl:0));
        tp1View.setText("TP1\n"+price(directional?s.tp1:0));tp2View.setText("TP2\n"+price(directional?s.tp2:0));
        // Use the same M1 bar timestamp as Smart Auto Entry and order lifecycle.
        updateEntryReadyUi(s,bid,ask,m1,m1[m1.length-1].t);

        View v=((ViewGroup)scoreView.getParent()).getChildAt(0);
        if(v instanceof Button){
            Button b=(Button)v;
            b.setText("");
            b.setTextSize(1);
            b.setBackground(roundBg("BUY".equals(s.side)?Color.rgb(0,225,135):"SELL".equals(s.side)?Color.rgb(255,65,82):Color.rgb(110,115,125),1000));
        }
        boolean signalDirectional="BUY".equals(s.side)||"SELL".equals(s.side);
        chart.setSignalLevels(signalDirectional?s.entry:0,signalDirectional?s.sl:0,
            signalDirectional?s.tp1:0,signalDirectional?s.tp2:0,signalDirectional);

        long candleStamp=m1[m1.length-1].t;
        WinRateTracker.evaluate(this,selectedMarket,m1);
        if(entryReady && ("BUY".equals(s.side)||"SELL".equals(s.side))){
            String readyTrackKey=selectedMarket+"|"+readyCandleKey+"|"+readyCandleSide;
            SharedPreferences trackPrefs=getSharedPreferences("rayyan4_winrate",MODE_PRIVATE);
            if(!readyTrackKey.equals(trackPrefs.getString("last_ready_key",""))){
                WinRateTracker.record(this,"READY",selectedMarket,s.side,s.entry,s.sl,s.tp1,candleStamp);
                trackPrefs.edit().putString("last_ready_key",readyTrackKey).apply();
            }
        }

        boolean changed=previous==null||!s.side.equals(previous.side);
        String candleKey=selectedMarket+"|"+selectedTf+"|"+candleStamp;
        String noticeKey=candleKey+"|"+s.side;
        String noticePrefKey="signal_notified_candle_"+s.side.toLowerCase(Locale.US);
        SharedPreferences noticePrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        boolean alreadyNotified=noticeKey.equals(noticePrefs.getString(noticePrefKey,""));
        // The lifecycle manager owns one Rayyan LIMIT/position at a time. It
        // queues, cancels, and re-enters only after fresh EA state plus matching ACK.
        processAutoLimit(s,m1,m5,bid,ask,candleStamp);
        if(changed&&("BUY".equals(s.side)||"SELL".equals(s.side))){
            String msg=selectedMarket+" SCALPING\n\n"+s.side+"\nEntry: "+price(s.entry)+"\nSL: "+price(s.sl)+"\nTP1: "+price(s.tp1)+"\nTP2: "+price(s.tp2)+"\nTF: M1\nBias: M5 "+s.m5Bias+"\nSetup: "+s.setup+"\nConfidence: "+s.confidence+"/5";
            AppLog.add(this,"SIGNAL",s.side+" "+price(s.entry)+" score="+s.score);
            if(!alreadyNotified){
                noticePrefs.edit().putString(noticePrefKey,noticeKey).apply();
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
        for(int value:new int[]{60,70,80,90}){ Button option=btn((current==value?"✓  ":"")+"CONFIDENCE "+value+"%"); option.setTextSize(14); option.setBackground(roundBg(current==value?Color.rgb(0,105,83):Color.rgb(7,34,60),12)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,58);p.setMargins(0,6,0,6);choices.addView(option,p);option.setOnClickListener(v->{getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putInt("min_entry_score",value).apply();Toast.makeText(this,"Confidence minimum "+value+"%",Toast.LENGTH_SHORT).show();if(lastSignal!=null&&m1Data!=null&&m1Data.length>0)updateEntryReadyUi(lastSignal,chart.getBid(),chart.getAsk(),m1Data,m1Data[m1Data.length-1].t);((Dialog)box.getTag()).dismiss();}); }
        box.addView(choices); AlertDialog d=new AlertDialog.Builder(this).setView(box).setNegativeButton("Tutup",null).create();box.setTag(d);d.setOnShowListener(x->{if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-40,420),-2);}});d.show();if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-40,420),-2);}
    }

    private void showSettingsPanel(View anchor){
        RayyanMenuLayout.showSettings(this,selectedMarket,selectedTf,this::handleMenuAction);
    }

    private void showThemeDialog(){
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(uiDp(16),uiDp(14),uiDp(16),uiDp(10));
        panel.setBackground(roundBg(Color.rgb(3,18,30),16));

        TextView heading = tv("TEMA APLIKASI",18);
        heading.setTextColor(Color.CYAN);
        heading.setTypeface(null,Typeface.BOLD);
        panel.addView(heading,new LinearLayout.LayoutParams(-1,uiDp(32)));
        TextView note = tv("Pilih salah satu dari tiga tema. Layout utama tetap sama; Light Professional memakai putih modern dan teks lembut yang tetap jelas dibaca.",12);
        note.setTextColor(Color.LTGRAY);
        note.setGravity(Gravity.TOP|Gravity.START);
        note.setPadding(0,uiDp(2),0,uiDp(8));
        panel.addView(note,new LinearLayout.LayoutParams(-1,uiDp(54)));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        final String[][] choices = {
            {RayyanTheme.DEFAULT,"Default Theme","Tampilan asli APK • layout tetap dipertahankan"},
            {RayyanTheme.DARK,"Dark Professional","Gelap nyaman • aksen biru"},
            {RayyanTheme.LIGHT,"Light Professional","Putih modern • teks abu-biru lembut"}
        };
        final int[][] swatches = {
            {Color.rgb(3,10,18),Color.rgb(0,210,220),Color.rgb(0,225,135)},
            {Color.rgb(8,13,23),Color.rgb(71,157,255),Color.rgb(0,225,135)},
            {Color.rgb(249,251,253),Color.rgb(91,139,174),Color.rgb(89,164,132)}
        };
        for(int i=0;i<choices.length;i++){
            final String code=choices[i][0];
            final boolean active=code.equals(appTheme);
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(uiDp(10),uiDp(8),uiDp(10),uiDp(8));
            row.setBackground(roundBg(active?Color.rgb(8,55,76):Color.rgb(7,30,47),12));

            LinearLayout labels=new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=tv(choices[i][1],14);
            name.setTypeface(null,Typeface.BOLD);
            name.setTextColor(active?Color.CYAN:Color.WHITE);
            labels.addView(name,new LinearLayout.LayoutParams(-1,uiDp(24)));
            TextView description=tv(choices[i][2],10);
            description.setTextColor(Color.LTGRAY);
            description.setMaxLines(2);
            labels.addView(description,new LinearLayout.LayoutParams(-1,uiDp(30)));
            row.addView(labels,new LinearLayout.LayoutParams(0,uiDp(56),1f));

            LinearLayout preview=new LinearLayout(this);
            preview.setOrientation(LinearLayout.HORIZONTAL);
            preview.setGravity(Gravity.CENTER);
            for(int color:swatches[i]){
                View swatch=new View(this);
                GradientDrawable chip=new GradientDrawable();
                chip.setColor(color);
                chip.setCornerRadius(uiDp(5));
                chip.setStroke(uiDp(1),Color.rgb(110,145,175));
                swatch.setBackground(chip);
                LinearLayout.LayoutParams chipParams=new LinearLayout.LayoutParams(uiDp(15),uiDp(26));
                chipParams.setMargins(uiDp(2),0,uiDp(2),0);
                preview.addView(swatch,chipParams);
            }
            row.addView(preview,new LinearLayout.LayoutParams(uiDp(58),uiDp(34)));
            TextView marker=tv(active?"✓":"›",18);
            marker.setTextColor(active?Color.rgb(0,225,135):Color.LTGRAY);
            marker.setGravity(Gravity.CENTER);
            row.addView(marker,new LinearLayout.LayoutParams(uiDp(25),uiDp(44)));

            LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,uiDp(72));
            rowParams.setMargins(0,0,0,uiDp(7));
            panel.addView(row,rowParams);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v->{
                if(dialogRef[0]!=null&&dialogRef[0].isShowing())dialogRef[0].dismiss();
                setAppTheme(code);
            });
        }

        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("TUTUP",null).create();
        dialogRef[0]=dialog;
        showSizedDialog(dialog,0.62f,430);
    }

    private void setAppTheme(String theme){
        appTheme=RayyanTheme.normalize(theme);
        getSharedPreferences("rayyan4_ui",MODE_PRIVATE).edit().putString("app_theme",appTheme).apply();
        applyCurrentAppTheme();
        String label=RayyanTheme.DARK.equals(appTheme)?"Dark Professional"
            :RayyanTheme.LIGHT.equals(appTheme)?"Light Professional":"Default Theme";
        Toast.makeText(this,"Tema aktif: "+label,Toast.LENGTH_SHORT).show();
    }

    private void applyCurrentAppTheme(){
        View decor=getWindow().getDecorView();
        if(RayyanTheme.DEFAULT.equals(appTheme)){
            // Restore the app's original system-bar setup for a genuinely unchanged default theme.
            getWindow().setStatusBarColor(Color.rgb(1,7,13));
            if(android.os.Build.VERSION.SDK_INT>=21)getWindow().setNavigationBarColor(defaultNavigationBarColor);
            decor.setSystemUiVisibility(defaultSystemUiVisibility);
        }else{
            boolean light=RayyanTheme.LIGHT.equals(appTheme);
            int barColor=light?Color.rgb(249,251,253):Color.rgb(5,9,17);
            getWindow().setStatusBarColor(barColor);
            if(android.os.Build.VERSION.SDK_INT>=21)getWindow().setNavigationBarColor(barColor);
            int flags=defaultSystemUiVisibility;
            if(light){
                flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if(android.os.Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }else{
                flags&=~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if(android.os.Build.VERSION.SDK_INT>=26)flags&=~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        if(chart!=null)chart.setVisualTheme(appTheme);
        if(mainRoot!=null)RayyanTheme.apply(this,mainRoot,appTheme);
    }

    private void showNotificationSettingsDialog(){
        SharedPreferences notice=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(18),uiDp(16),uiDp(18),uiDp(14));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("NOTIFIKASI APLIKASI",18);
        title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(42)));
        TextView note=tv("Aktif/nonaktifkan notifikasi sinyal dan antrean di HP. Pengaturan Telegram tetap terpisah.",12);
        note.setTextColor(Color.LTGRAY);note.setGravity(Gravity.TOP|Gravity.START);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(54)));
        Switch enabled=new Switch(this);
        enabled.setText("Notifikasi aplikasi");
        enabled.setTextColor(Color.WHITE);
        enabled.setTextSize(15);
        enabled.setPadding(0,uiDp(8),0,uiDp(8));
        enabled.setChecked(notice.getBoolean("notifications_enabled",true));
        box.addView(enabled,new LinearLayout.LayoutParams(-1,uiDp(58)));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(box)
            .setPositiveButton("SIMPAN",(d,w)->{
                boolean on=enabled.isChecked();
                notice.edit().putBoolean("notifications_enabled",on).apply();
                AppLog.add(this,"NOTIFICATIONS",on?"enabled":"disabled");
                Toast.makeText(this,on?"Notifikasi aplikasi AKTIF":"Notifikasi aplikasi NONAKTIF",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("BATAL",null).create();
        showSizedDialog(dialog,0.46f,400);
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
        else if("notifications".equals(action)||"settings_notifications".equals(action))showNotificationSettingsDialog();
        else if("settings".equals(action))showSettingsPanel(null);
        else if("settings_theme".equals(action))showThemeDialog();
        else if("live_chart".equals(action))showChartDisplayDialog();
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
                RayyanTheme.apply(this,dialog.getWindow().getDecorView(),appTheme);
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
            RayyanTheme.apply(this,dialog.getWindow().getDecorView(),appTheme);
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
            row.setOnClickListener(v->{if(market.equals(selectedMarket)){d.dismiss();return;}selectedMarket=market;lastHeaderPrice=Double.NaN;lastSpreadLabel="";lastFeedQuoteAt=0L;m1Data=new Candle[0];m5Data=new Candle[0];if(priceView!=null){priceView.setText("--");priceView.setTextColor(Color.CYAN);}if(spreadView!=null)spreadView.setText("SPREAD --");prefs.edit().putString("market",selectedMarket).apply();queueNativeIndicatorState();if(marketTitle!=null)marketTitle.setText(selectedMarket);if(marketSubtitle!=null)marketSubtitle.setText(marketDescription(selectedMarket));if(chart!=null){chart.setMarket(selectedMarket);chart.setData(new Candle[0]);chart.clearQuote();chart.setPositionEntryLevel(0);}if(signalSummary!=null)signalSummary.setText(selectedMarket+" • "+selectedTf+"\nMenunggu data market...");if(status!=null)status.setText("● MENGHUBUNGKAN • "+selectedMarket);lastSignal=null;resetEntryReadyUi("menunggu data market");d.dismiss();load(interval(selectedTf),selectedTf);AppLog.add(this,"MARKET","selected "+selectedMarket+" • broker/chart quotes separated");});
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
        queueNativeIndicatorState();
    }

    private void queueNativeIndicatorState(){
        long id=System.currentTimeMillis();
        String command="id="+id+
            ";symbol="+mt5SymbolForMarket(selectedMarket)+
            ";tf="+selectedTf+
            ";ema9="+(ind("ema9",true)?"1":"0")+
            ";ema21="+(ind("ema21",true)?"1":"0")+
            ";ema50="+(ind("ema50",true)?"1":"0")+
            ";sr="+(ind("sr",true)?"1":"0")+
            ";fibo="+(ind("fibo",true)?"1":"0");
        getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putString("indicator_next",command).apply();
        AppLog.add(this,"MT5 INDICATORS","queued native chart state id="+id);
    }

    void setInd(String key,boolean enabled){
        prefs.edit().putBoolean(key,enabled).apply();
        if("ema9".equals(key)||"ema21".equals(key)||"ema50".equals(key)||"sr".equals(key)||"fibo".equals(key))
            queueNativeIndicatorState();
        switch(key){
            case "ema9":chart.setShowEMA9(enabled);break;case "ema21":chart.setShowEMA21(enabled);break;case "ema50":chart.setShowEMA50(enabled);break;
            case "sr":chart.setShowSR(enabled);break;case "fibo":chart.setShowFibo(enabled);break;case "rsi":chart.setShowRSI(enabled);break;case "macd":chart.setShowMACD(enabled);break;case "atr":chart.setShowATR(enabled);break;
            case "signals":chart.setShowSignals(enabled);break;case "wickbos":chart.setShowWickBos(enabled);break;case "orders":chart.setShowTradeLevels(enabled);break;case "position":chart.setShowPosition(enabled);break;case "bidask":chart.setShowBidAsk(enabled);break;
        }
    }

    private void showWinRateDialog(){
        WinRateTracker.Stats ready=WinRateTracker.stats(this,"READY");
        WinRateTracker.Stats manual=WinRateTracker.stats(this,"MANUAL");
        WinRateTracker.Stats smart=WinRateTracker.stats(this,"SMART_AUTO");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(uiDp(16),uiDp(14),uiDp(16),uiDp(12));
        box.setBackground(roundBg(Color.rgb(3,18,30),16));
        TextView title=tv("HASIL UJI • WIN RATE",18);title.setTextColor(Color.CYAN);title.setTypeface(null,Typeface.BOLD);
        box.addView(title,new LinearLayout.LayoutParams(-1,uiDp(38)));
        TextView note=tv("READY = sinyal simulasi • MANUAL/SMART AUTO = perintah berhasil masuk antrean APK",11);
        note.setTextColor(Color.LTGRAY);note.setGravity(Gravity.TOP|Gravity.START);
        box.addView(note,new LinearLayout.LayoutParams(-1,uiDp(42)));
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        addWinRateRow(rows,"1. SINYAL ENTRY READY",ready);
        addWinRateRow(rows,"2. ORDER MANUAL",manual);
        addWinRateRow(rows,"3. SMART AUTO ENTRY",smart);
        TextView rule=tv("WIN: TP1 tersentuh dahulu • LOSS: SL tersentuh dahulu • OPEN: belum selesai • AMBIGU: TP1 dan SL tersentuh dalam candle sama. Garis ENTRY tetap pada harga yang dicatat terakhir.",11);
        rule.setTextColor(Color.rgb(255,205,95));rule.setGravity(Gravity.TOP|Gravity.START);
        rule.setPadding(uiDp(10),uiDp(8),uiDp(10),uiDp(8));rule.setBackground(roundBg(Color.rgb(7,30,47),10));
        rows.addView(rule,new LinearLayout.LayoutParams(-1,-2));
        TextView disclaimer=tv("Catatan: untuk order MANUAL/SMART AUTO, status OPEN dimulai saat APK menerima perintah ke antrean; ini belum membuktikan order terisi di MT5. Hasil memakai level Entry/SL/TP1 dan candle berikutnya sebagai simulasi.",10);
        disclaimer.setTextColor(Color.LTGRAY);disclaimer.setGravity(Gravity.TOP|Gravity.START);
        disclaimer.setPadding(uiDp(10),uiDp(8),uiDp(10),uiDp(8));rows.addView(disclaimer);
        TextView detailTitle=tv("RIWAYAT TERAKHIR",13);detailTitle.setTextColor(Color.CYAN);detailTitle.setTypeface(null,Typeface.BOLD);
        rows.addView(detailTitle,new LinearLayout.LayoutParams(-1,uiDp(32)));
        TextView details=tv("READY\n"+WinRateTracker.details(this,"READY")+"\n\nMANUAL\n"+WinRateTracker.details(this,"MANUAL")+"\n\nSMART AUTO\n"+WinRateTracker.details(this,"SMART_AUTO"),11);
        details.setTextIsSelectable(true);details.setGravity(Gravity.TOP|Gravity.START);
        details.setPadding(uiDp(10),uiDp(8),uiDp(10),uiDp(8));details.setBackground(roundBg(Color.rgb(6,25,40),10));
        rows.addView(details,new LinearLayout.LayoutParams(-1,-2));
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);sc.addView(rows);
        box.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        AlertDialog d=new AlertDialog.Builder(this).setView(box).setPositiveButton("PERBARUI",null).setNegativeButton("TUTUP",null).create();
        showSizedDialog(d,0.84f,450);
    }

    private void addWinRateRow(LinearLayout parent,String title,WinRateTracker.Stats s){
        String value="WIN "+s.win+" ("+String.format(Locale.US,"%.1f",s.winRate())+"%)  •  LOSS "+s.loss+
            "\nOPEN "+s.open+"  •  AMBIGU "+s.ambiguous+"  •  TOTAL "+s.total;
        addPanelRow(parent,title,value,Color.WHITE);
    }

    void bindQuickLayer(Button b,String label){
        b.setOnClickListener(v->{if(label.contains("S/R"))setInd("sr",!ind("sr",true));else if(label.contains("Fibo"))setInd("fibo",!ind("fibo",true));
            else if(label.contains("EMA")){boolean next=!(ind("ema9",true)&&ind("ema21",true)&&ind("ema50",true));setInd("ema9",next);setInd("ema21",next);setInd("ema50",next);}
            else if(label.contains("Buy/Sell"))setInd("signals",!ind("signals",true));else if(label.contains("Order"))setInd("orders",!ind("orders",true));else if(label.contains("Posisi")){setInd("position",true);if(chart!=null)chart.setShowPosition(true);showWinRateDialog();}updateQuickLayerButton(b,label);});
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
        SharedPreferences appNotice=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        if(!appNotice.getBoolean("notifications_enabled",true))return;
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
    private boolean saveAutoEntrySettings(Switch auto,Switch smartReentry,Spinner mode,
                                          Spinner entryActionMode,EditText volume,
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
        String entryAction=entryActionMode!=null&&entryActionMode.getSelectedItemPosition()==1
            ?OPEN_ACTION_WAIT_BOUNCE:OPEN_ACTION_SWEEP_FIRST;
        gatewayPrefs.edit().putFloat("lot_"+symbol,(float)lot)
            .putBoolean("auto",auto.isChecked())
            .putBoolean("smart_reentry_enabled",smartReentry.isChecked())
            .putString("open_action_entry_mode",entryAction)
            .putString("operation_mode",operation).apply();
        AppLog.add(this,"MODE",operation.equals("READY")?"SIAP TRADING":"ISTIRAHAT");
        AppLog.add(this,"AUTO LIMIT","settings saved symbol="+symbol+" lot="+lotText(lot)
            +" auto="+auto.isChecked()+" mode="+operation);
        AppLog.add(this,"OPEN ACTION ENTRY","mode="+entryAction+" offset="+AUTO_ENTRY_OFFSET_POINTS+" MT5 points");
        return true;
    }

    String lotText(double lot){return String.format(Locale.US,"%.2f",lot);}

    private double smartReentryMaxDistanceAtr(SharedPreferences gp){
        double value=gp.getFloat("smart_reentry_max_distance_atr",1.5f);
        return Double.isFinite(value)&&value>=0.5&&value<=5.0?value:1.5;
    }

    private int smartReentryMaxAgeBars(SharedPreferences gp){
        int value=gp.getInt("smart_reentry_max_age_m1_bars",3);
        return value>=3&&value<=3?value:3;
    }

    private boolean validSmartReentryLimits(EditText distance,EditText age){
        try{
            double d=Double.parseDouble(distance.getText().toString().trim());
            int a=Integer.parseInt(age.getText().toString().trim());
            if(!Double.isFinite(d)||d<0.5||d>5.0||a!=3)throw new NumberFormatException();
            return true;
        }catch(Exception e){
            Toast.makeText(this,"Jarak maksimum 0,5–5,0 ATR dan usia LIMIT harus 3 candle M1.",
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
        return queueLimit(side,volume,entry,sl,tp,0.0);
    }

    /** Optional TP1 is passed only when the caller has a validated first target. */
    private boolean queueLimit(String side,double volume,double entry,double sl,double tp,double tp1){
        try {
        if(chart==null||!hasFreshEntryQuotes()){
            AppLog.add(this,"ORDER_BLOCKED","queue LIMIT diblokir: kuotasi MT5/chart tidak segar");
            queueBlockDiagnostic("quote/chart stale");
            Toast.makeText(this,"Kuotasi MT5/chart belum segar • order tidak diantrekan",Toast.LENGTH_LONG).show();
            return false;
        }
        side=side==null?"":side.trim().toUpperCase(Locale.US);
        if("BUY".equals(side))side="BUY_LIMIT";
        if("SELL".equals(side))side="SELL_LIMIT";
        if(!"BUY_LIMIT".equals(side)&&!"SELL_LIMIT".equals(side)){
            queueBlockDiagnostic("invalid side="+side);
            Toast.makeText(this,"Side harus BUY_LIMIT atau SELL_LIMIT",Toast.LENGTH_SHORT).show();return false;
        }
        if(volume<=0||entry<=0||sl<=0||tp<=0){
            queueBlockDiagnostic("invalid order geometry/values volume="+volume+" entry="+entry+" sl="+sl+" tp="+tp);
            Toast.makeText(this,"Nilai order tidak valid",Toast.LENGTH_SHORT).show();return false;
        }
        String mt5Symbol=mt5SymbolForMarket(selectedMarket);
        double minLot=minLotForSymbol(mt5Symbol);
        if(volume+1e-8<minLot){
            AppLog.add(this,"ORDER","blocked BAD_VOLUME symbol="+mt5Symbol+" volume="+lotText(volume)+" min="+lotText(minLot));
            Toast.makeText(this,"Lot minimum "+mt5Symbol+" = "+lotText(minLot)+" • order tidak dikirim",Toast.LENGTH_LONG).show();
            return false;
        }
        if("BUY_LIMIT".equals(side)){
            if(entry>=chart.getAsk()){
                queueBlockDiagnostic("BUY LIMIT entry="+price(entry)+" >= ask="+price(chart.getAsk()));
                Toast.makeText(this,"BUY LIMIT harus di bawah ASK",Toast.LENGTH_SHORT).show();return false;
            }
            if(!(sl<entry&&tp>entry)){
                queueBlockDiagnostic("BUY geometry invalid SL < Entry < TP • SL="+price(sl)+" Entry="+price(entry)+" TP="+price(tp));
                Toast.makeText(this,"BUY LIMIT: SL < Entry < TP",Toast.LENGTH_SHORT).show();return false;
            }
        }
        if("SELL_LIMIT".equals(side)){
            if(entry<=chart.getBid()){
                queueBlockDiagnostic("SELL LIMIT entry="+price(entry)+" <= bid="+price(chart.getBid()));
                Toast.makeText(this,"SELL LIMIT harus di atas BID",Toast.LENGTH_SHORT).show();return false;
            }
            if(!(tp<entry&&sl>entry)){
                queueBlockDiagnostic("SELL geometry invalid TP < Entry < SL • TP="+price(tp)+" Entry="+price(entry)+" SL="+price(sl));
                Toast.makeText(this,"SELL LIMIT: TP < Entry < SL",Toast.LENGTH_SHORT).show();return false;
            }
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
                queueBlockDiagnostic("queue stale >15s id="+existingId+"; cleared but MT5 must be checked before resend");
                Toast.makeText(this,"Perintah kedaluwarsa >15 detik. Cek MT5 Trade/Experts sebelum entry baru.",Toast.LENGTH_LONG).show();
                return false;
            }
            AppLog.add(this,"ORDER","queue busy; refusing to overwrite unacknowledged command");
            queueBlockDiagnostic("gateway queue busy; refusing to overwrite unacknowledged command");
            Toast.makeText(this,"Antrean masih berisi order, tunggu ACK MT5",Toast.LENGTH_SHORT).show();
            return false;
        }
        String id=Long.toString(System.currentTimeMillis());
        String tp1Field=tp1>0.0?";tp1="+Double.toString(tp1):"";
        String cmd="id="+id+";type=LIMIT;symbol="+mt5Symbol+";side="+side+";volume="+volume+";entry="+entry+";sl="+sl+";tp="+tp+tp1Field+";refBid="+chart.getBid()+";refAsk="+chart.getAsk();
        gatewayPrefs.edit().putString("next",cmd).putFloat("lot_"+mt5Symbol,(float)volume)
            .putString("order_status_id",id).putString("order_status","QUEUED")
            .putString("order_status_detail","Menunggu EA MT5 mengambil perintah")
            .putLong("order_status_at",System.currentTimeMillis()).apply();
        HistoryStore.add(this,"PENDING",side,entry,sl,tp,tp,lastSignal==null?0:lastSignal.confidence);
        String trackedSide=side.startsWith("BUY")?"BUY":"SELL";
        long trackedCandle=chart!=null&&!chart.isEmpty()?chart.getDataSnapshot()[chart.getDataSnapshot().length-1].t:System.currentTimeMillis();
        WinRateTracker.record(this,queueOrigin,selectedMarket,trackedSide,entry,sl,queueOutcomeTarget>0?queueOutcomeTarget:tp,trackedCandle);
        // A queued request is not yet a live broker order. The EA state heartbeat
        // owns OPEN ENTRY display and removes it after cancel/close/rejection.
        AppLog.add(this,"ORDER","queued "+cmd);
        AppLog.add(this,"ORDER AUDIT","STAGE APK_QUEUE_ACCEPTED id="+id+" origin="+queueOrigin+
            " symbol="+mt5Symbol+" side="+side+" volume="+lotText(volume)+
            " entry="+price(entry)+" SL="+price(sl)+" TP="+price(tp)+
            " • QUEUED means APK accepted only; broker/MT5 has NOT confirmed"+
            " • "+spreadAuditText(m1Data));
        if("SMART_AUTO".equals(queueOrigin))
            AppLog.add(this,"SMART AUTO DIAG","STAGE APK_QUEUE_ACCEPTED • id="+id+" • queue=QUEUED • broker has NOT confirmed the order yet");
        notifyLocal("MT5 "+side,"Entry queued for demo EA");
        Toast.makeText(this,"Entry masuk antrean • EA MT5 akan memproses otomatis",Toast.LENGTH_SHORT).show();
        return true;
        } catch(Exception e) {
            queueBlockDiagnostic("exception="+e.getClass().getSimpleName()+" "+e.getMessage());
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
        TextView panelNote=tv("M1 + bias M5 • proteksi exit TP2→TP1 dikelola EA Rayyan",12);
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

        Spinner entryActionMode=new Spinner(this);
        String[] entryActions={
            "OPEN ACTION 1 • sapuan pertama, LIMIT 10 MT5 points",
            "OPEN ACTION 2 • tunggu pantulan, lalu pasang LIMIT"
        };
        ArrayAdapter<String> entryActionAdapter=new ArrayAdapter<>(
            this,android.R.layout.simple_spinner_dropdown_item,entryActions);
        entryActionMode.setAdapter(entryActionAdapter);
        entryActionMode.setMinimumHeight(uiDp(50));
        entryActionMode.setBackground(roundBg(Color.rgb(7,30,47),12));
        entryActionMode.setSelection(OPEN_ACTION_WAIT_BOUNCE.equals(
            gatewayPrefs.getString("open_action_entry_mode",OPEN_ACTION_SWEEP_FIRST))?1:0);

        TextView entryActionHint=tv("Mode 1: pasang LIMIT dekat harga setelah wick + liquidity sweep valid. Mode 2: tunggu harga bergerak searah minimal 0,15 ATR atau 10 points, baru pasang LIMIT. SL tetap pada struktur wick; TP dihitung ulang 2R/3,5R.",11);
        entryActionHint.setTextColor(Color.LTGRAY);
        entryActionHint.setGravity(Gravity.TOP|Gravity.START);
        entryActionHint.setPadding(uiDp(8),uiDp(5),uiDp(8),uiDp(5));

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
        maxAge.setHint("Usia maksimum LIMIT (candle M1), wajib 3");
        maxAge.setInputType(2);styleDialogInput(maxAge);
        maxAge.setText(Integer.toString(gatewayPrefs.getInt("smart_reentry_max_age_m1_bars",3)));

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
        fields.addView(entryActionMode,new LinearLayout.LayoutParams(-1,uiDp(52)));
        LinearLayout.LayoutParams actionHintParams=new LinearLayout.LayoutParams(-1,-2);
        actionHintParams.setMargins(0,uiDp(2),0,uiDp(4));
        fields.addView(entryActionHint,actionHintParams);
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
            if(!saveAutoEntrySettings(auto,smartReentry,mode,entryActionMode,vol,gatewayPrefs,selectedMt5Symbol))return;
            persistSmartReentryLimits(maxDistance,maxAge,gatewayPrefs);
            Toast.makeText(this,"Pengaturan AUTO ENTRY dan batas Smart LIMIT tersimpan untuk "+selectedMt5Symbol,Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        close.setOnClickListener(v->dialog.dismiss());
        send.setOnClickListener(v->{
            try{
                if(!validSmartReentryLimits(maxDistance,maxAge))return;
                if(!saveAutoEntrySettings(auto,smartReentry,mode,entryActionMode,vol,gatewayPrefs,selectedMt5Symbol))return;
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

    @Override protected void onDestroy(){
        chartMirrorHandler.removeCallbacks(chartMirrorRefresh);signalUiHandler.removeCallbacks(entryBlinkRunnable);if(ex!=null)ex.shutdownNow();if(candleRefresh!=null)candleRefresh.shutdownNow();super.onDestroy();}
}
