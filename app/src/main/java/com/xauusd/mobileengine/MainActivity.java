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
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static volatile boolean visible = false;
    public static boolean isAppVisible(){ return visible; }
    private CandleChartView chart;
    private TextView bidView, askView, spreadView, status, signalSummary, scoreView, entryView, slView, tp1View, tp2View;
    private Button signalSideButton;
    private ScheduledExecutorService ex;
    private SharedPreferences prefs, tgPrefs;
    private boolean telegramEnabled;
    private String selectedTf="M1";
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
        b.setAllCaps(false);b.setMinHeight(44);b.setPadding(4,0,4,0);
        b.setBackground(roundBg(Color.rgb(7,34,60),12));return b;
    }

    GradientDrawable roundBg(int c,int r){
        GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(r);g.setStroke(1,Color.rgb(22,104,157));return g;
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(1,7,13));
        prefs=getSharedPreferences("rayyan4_indicators",MODE_PRIVATE);
        tgPrefs=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE);
        telegramEnabled=tgPrefs.getBoolean("enabled",false);
        buildUI();
    }

    void buildUI(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);

        // HEADER
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(7,3,7,3);
        head.setBackgroundColor(Color.rgb(2,15,29));

        TextView gold=tv("🪙",30);head.addView(gold,new LinearLayout.LayoutParams(48,70));
        LinearLayout title=new LinearLayout(this);title.setOrientation(LinearLayout.VERTICAL);
        TextView x=tv("XAUUSD",21);x.setTypeface(null,Typeface.BOLD);title.addView(x,new LinearLayout.LayoutParams(-1,30));
        title.addView(tv("Gold Spot / US Dollar",11),new LinearLayout.LayoutParams(-1,22));
        TextView rt=tv("● REALTIME",10);rt.setTextColor(green);title.addView(rt,new LinearLayout.LayoutParams(-1,18));
        head.addView(title,new LinearLayout.LayoutParams(0,70,1));

        LinearLayout quote=new LinearLayout(this);quote.setOrientation(LinearLayout.HORIZONTAL);
        quote.addView(quoteBox("BID",green),new LinearLayout.LayoutParams(0,70,1));
        quote.addView(quoteBox("ASK",red),new LinearLayout.LayoutParams(0,70,1));
        head.addView(quote,new LinearLayout.LayoutParams(215,70));

        Button menu=btn("☰\nMENU");menu.setTextSize(12);menu.setBackground(roundBg(Color.rgb(6,45,74),12));
        menu.setOnClickListener(v->showMenu(menu));head.addView(menu,new LinearLayout.LayoutParams(70,70));
        root.addView(head);

        // SPREAD
        LinearLayout spread=new LinearLayout(this);spread.setGravity(Gravity.CENTER);spread.setBackgroundColor(Color.rgb(6,27,45));
        spreadView=tv("SPREAD  --",11);spreadView.setGravity(Gravity.CENTER);
        spread.addView(spreadView,new LinearLayout.LayoutParams(-1,28));root.addView(spread);

        // TIMEFRAME ROW - reference structure: M1 M5 M15 H1 H4 D1 LIVE
        LinearLayout tf=new LinearLayout(this);tf.setPadding(5,4,5,4);tf.setBackgroundColor(Color.rgb(2,14,26));
        addTfButton(tf,"M1");addTfButton(tf,"M5");addTfButton(tf,"M15");addTfButton(tf,"H1");addTfButton(tf,"H4");addTfButton(tf,"D1");
        Button live=btn("◉ LIVE");live.setTextSize(12);live.setTextColor(green);live.setBackground(roundBg(Color.rgb(7,66,49),12));
        tf.addView(live,new LinearLayout.LayoutParams(0,48,1));
        live.setOnClickListener(v->{chart.goLive();selectTimeframe(selectedTf,false);});
        root.addView(tf);

        chart=new CandleChartView(this);chart.setTimeframe(selectedTf);loadIndicatorSettings();
        root.addView(chart,new LinearLayout.LayoutParams(-1,0,1));

        // CHART TOOLS
        LinearLayout layers=new LinearLayout(this);layers.setPadding(4,3,4,3);layers.setBackgroundColor(Color.rgb(3,14,24));
        String[] ls={"▥ S/R","▤ Fibo","〽 EMA","↕ Buy/Sell","▣ Order","◎ Posisi"};
        for(String s:ls){
            Button b=btn(s);b.setTextSize(10);layers.addView(b,new LinearLayout.LayoutParams(0,46,1));bindQuickLayer(b,s);
        }
        root.addView(layers);

        // SIGNAL CARD
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(8,5,8,5);
        card.setBackground(roundBg(Color.rgb(5,43,53),14));

        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        Button side=btn("WAIT");side.setTextSize(17);side.setTypeface(null,Typeface.BOLD);
        signalSideButton=side;
        side.setBackground(roundBg(Color.rgb(75,78,86),12));
        side.setOnClickListener(v->executeCurrentSignal());
        top.addView(side,new LinearLayout.LayoutParams(78,50));
        signalSummary=tv("XAUUSD • "+selectedTf+"\nWick Rejection + Liquidity Sweep + BOS",10);
        top.addView(signalSummary,new LinearLayout.LayoutParams(0,50,1));
        scoreView=tv("CONFIDENCE\n--/5",11);scoreView.setGravity(Gravity.CENTER);top.addView(scoreView,new LinearLayout.LayoutParams(100,50));
        card.addView(top);

        LinearLayout levels=new LinearLayout(this);levels.setPadding(2,2,2,0);
        String[] names={"Entry","SL","TP1","TP2"};
        for(String n:names){
            TextView v=tv(n+"\n--",11);v.setGravity(Gravity.CENTER);
            if(n.equals("Entry"))entryView=v;else if(n.equals("SL"))slView=v;else if(n.equals("TP1"))tp1View=v;else tp2View=v;
            levels.addView(v,new LinearLayout.LayoutParams(0,48,1));
        }
        card.addView(levels);root.addView(card,new LinearLayout.LayoutParams(-1,108));

        status=tv("● MT5 DEMO GATEWAY • M1",10);status.setBackgroundColor(Color.rgb(3,17,29));
        root.addView(status,new LinearLayout.LayoutParams(-1,28));

        // BOTTOM NAV - 5 fixed tabs
        LinearLayout nav=new LinearLayout(this);nav.setPadding(4,3,4,3);nav.setBackgroundColor(Color.rgb(2,12,22));
        String[] navs={"▥ MARKET","▥ CHART","♧ SIGNAL","▣ ORDERS","⚙ SETTINGS"};
        for(String s:navs){
            Button q=btn(s);q.setTextSize(11);nav.addView(q,new LinearLayout.LayoutParams(0,55,1));
            if(s.contains("SIGNAL"))q.setOnClickListener(v->showSignalDialog());
            else if(s.contains("ORDERS"))q.setOnClickListener(v->showLogDialog("ORDERS / HISTORY"));
            else if(s.contains("SETTINGS"))q.setOnClickListener(v->showMenu(q));
            else if(s.contains("CHART"))q.setOnClickListener(v->chart.goLive());
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,61));

        setContentView(root);
        requestNotificationPermission();
        startGateway();
        selectTimeframe("M1",false);load("1m","M1");
    }

    LinearLayout quoteBox(String name,int color){
        LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(4,2,4,2);
        TextView n=tv(name,9);n.setTextColor(color);TextView v=tv("--",18);v.setTextColor(color);
        if(name.equals("BID"))bidView=v;else askView=v;
        b.addView(n,new LinearLayout.LayoutParams(-1,22));b.addView(v,new LinearLayout.LayoutParams(-1,44));return b;
    }

    void addTfButton(LinearLayout parent,String tf){
        Button q=btn(tf);q.setTextSize(13);tfButtons.put(tf,q);parent.addView(q,new LinearLayout.LayoutParams(0,48,1));
        q.setOnClickListener(v->selectTimeframe(tf,true));
    }

    void selectTimeframe(String tf,boolean reload){
        selectedTf=tf;
        for(Map.Entry<String,Button> e:tfButtons.entrySet()){
            boolean active=e.getKey().equals(tf);e.getValue().setTextColor(active?Color.BLACK:Color.WHITE);
            e.getValue().setBackground(roundBg(active?Color.rgb(15,150,235):Color.rgb(7,34,60),12));
        }
        if(chart!=null)chart.setTimeframe(tf);
        if(signalSummary!=null)signalSummary.setText("XAUUSD • "+tf+"\nWick Rejection + Liquidity Sweep + BOS");
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
                Candle[] c=BiquoteClient.candles(interval);
                Candle[] m5="M5".equals(tf)?c:BiquoteClient.candles("5m");
                runOnUiThread(()->{
                    if(!tf.equals(selectedTf))return;
                    chart.setData(c);chart.goLive();m5Data=m5;updateSignal(c,m5Data);
                    status.setText("● LIVE • RAYYAN4 • "+tf);
                });
            }catch(Exception e){runOnUiThread(()->status.setText("● OFFLINE • "+tf+" • RETRYING"));}
        }).start();

        if(ex==null){
            ex=Executors.newSingleThreadScheduledExecutor();
            ex.scheduleAtFixedRate(()->{
                try{
                    double[] t=BiquoteClient.tick();
                    runOnUiThread(()->{
                        chart.setTick(t[0],t[1],selectedTf);
                        if(!chart.isEmpty())updateSignal(chart.getDataSnapshot(),m5Data);
                        bidView.setText(String.format(Locale.US,"%.2f",t[0]));
                        askView.setText(String.format(Locale.US,"%.2f",t[1]));
                        spreadView.setText(String.format(Locale.US,"SPREAD  %.2f",t[1]-t[0]));
                        status.setText("● LIVE • RAYYAN4 • "+selectedTf);
                    });
                }catch(Exception e){runOnUiThread(()->status.setText("● OFFLINE • RETRYING • "+selectedTf));}
            },1,1,TimeUnit.SECONDS);
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
        double lot=getSharedPreferences("rayyan4_gateway",0).getFloat("lot",0.01f);
        queueLimit(side,lot,s.entry,s.sl,s.tp2>0?s.tp2:s.tp1);
    }

    void updateSignal(Candle[] m1,Candle[] m5){
        if(m1==null||m1.length<30||m5==null||m5.length<30)return;
        double bid=chart==null?0:chart.getBid(),ask=chart==null?0:chart.getAsk();
        SignalResult previous=lastSignal;lastSignal=SignalEngine.evaluate(m1,m5,bid,ask);SignalResult s=lastSignal;

        scoreView.setText("CONFIDENCE\n"+s.confidence+"/5  "+stars(s.confidence));
        scoreView.setTextColor(s.confidence>=4?Color.YELLOW:Color.WHITE);
        signalSummary.setText("M1 "+s.side+" • M5 "+s.m5Bias+"\n"+s.setup+" • "+s.confidence+"/5");
        entryView.setText("Entry\n"+price(s.entry));slView.setText("SL\n"+price(s.sl));tp1View.setText("TP1\n"+price(s.tp1));tp2View.setText("TP2\n"+price(s.tp2));

        View v=((ViewGroup)scoreView.getParent()).getChildAt(0);
        if(v instanceof Button){
            Button b=(Button)v;b.setText(s.side);
            b.setBackground(roundBg("BUY".equals(s.side)?Color.rgb(0,190,105):"SELL".equals(s.side)?Color.rgb(220,45,60):Color.rgb(75,78,86),12));
        }
        chart.setSignalLevels(s.entry,s.sl,s.tp1,s.tp2,"BUY".equals(s.side)||"SELL".equals(s.side));

        boolean changed=previous==null||!s.side.equals(previous.side);
        if(changed&&("BUY".equals(s.side)||"SELL".equals(s.side))){
            String msg="XAUUSD SCALPING\n\n"+s.side+"\nEntry: "+price(s.entry)+"\nSL: "+price(s.sl)+"\nTP1: "+price(s.tp1)+"\nTP2: "+price(s.tp2)+"\nTF: M1\nBias: M5 "+s.m5Bias+"\nSetup: "+s.setup+"\nConfidence: "+s.confidence+"/5";
            AppLog.add(this,"SIGNAL",s.side+" "+price(s.entry)+" score="+s.score);
            if(telegramEnabled)sendTelegram(msg);
            notifyLocal("XAUUSD "+s.side,"Entry "+price(s.entry)+" | SL "+price(s.sl)+" | TP1 "+price(s.tp1));
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
        String[] items={"Market","Chart","Indicators","Signal","Orders","Strategy","Risk Management","Telegram","MT5 / EA","Settings"};
        PopupWindow pop=new PopupWindow(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(8,8,8,8);box.setBackgroundColor(Color.rgb(5,24,42));
        for(String s:items){
            Button b=btn(s);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setTextSize(14);box.addView(b,new LinearLayout.LayoutParams(270,54));
            b.setOnClickListener(v->{pop.dismiss();
                if("Indicators".equals(s))showIndicatorDialog();else if("Signal".equals(s))showSignalDialog();else if("Telegram".equals(s))showTelegramDialog();else if("MT5 / EA".equals(s))showMt5Dialog();
                else if("Orders".equals(s)||"Settings".equals(s))showLogDialog(s);else Toast.makeText(this,s,Toast.LENGTH_SHORT).show();
            });
        }
        pop.setContentView(box);pop.setWidth(278);pop.setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));pop.setOutsideTouchable(true);pop.setFocusable(true);pop.setElevation(16);pop.showAsDropDown(anchor,-204,4);
    }

    void showSignalDialog(){
        if(lastSignal==null){Toast.makeText(this,"Menunggu data Signal Engine...",Toast.LENGTH_SHORT).show();return;}
        SignalResult s=lastSignal;
        String msg="SIDE        : "+s.side+"\nM5 BIAS     : "+s.m5Bias+"\nM1 ENTRY    : "+s.entry+"\nSL          : "+s.sl+"\nTP1         : "+s.tp1+"\nTP2         : "+s.tp2+"\nR:R         : "+String.format(Locale.US,"%.2f",s.rr)+"\nCONFIDENCE  : "+s.confidence+"/5\nENTRY SCORE : "+s.score+"%\n\nLiquidity Sweep : "+onoff(s.liquiditySweep)+"\nWick Rejection  : "+onoff(s.wickRejection)+"\nBOS             : "+onoff(s.bos)+"\nEMA Filter      : "+onoff(s.emaFilter)+"\nRSI Filter      : "+onoff(s.rsiFilter)+"\nMACD Filter     : "+onoff(s.macdFilter)+"\nATR Filter      : "+onoff(s.atrFilter)+"\nSpread Filter   : "+onoff(s.spreadFilter);
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
        boolean[] checked={ind("ema9",true),ind("ema21",true),ind("ema50",true),ind("sr",true),ind("fibo",true),ind("rsi",true),ind("macd",true),ind("atr",true),ind("signals",true),ind("wickbos",true),ind("bidask",true),ind("orders",true)};
        new AlertDialog.Builder(this).setTitle("INDICATORS • ON / OFF").setMultiChoiceItems(names,checked,(d,w,c)->setInd(keys[w],c)).setPositiveButton("TUTUP",null).show();
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
        try{startForegroundService(new Intent(this,GatewayService.class));}catch(Exception e){startService(new Intent(this,GatewayService.class));}
        AppLog.add(this,"MT5","gateway started");notifyLocal("MT5 Gateway","LAN gateway aktif di port 8787");
    }

    void queueLimit(String side,double volume,double entry,double sl,double tp){
        side=side==null?"":side.trim().toUpperCase(Locale.US);
        if("BUY".equals(side))side="BUY_LIMIT";
        if("SELL".equals(side))side="SELL_LIMIT";
        if(!"BUY_LIMIT".equals(side)&&!"SELL_LIMIT".equals(side)){
            Toast.makeText(this,"Side harus BUY_LIMIT atau SELL_LIMIT",Toast.LENGTH_SHORT).show();return;
        }
        if(volume<=0||entry<=0||sl<=0||tp<=0){Toast.makeText(this,"Nilai order tidak valid",Toast.LENGTH_SHORT).show();return;}
        if("BUY_LIMIT".equals(side)){
            if(entry>=chart.getAsk()){Toast.makeText(this,"BUY LIMIT harus di bawah ASK",Toast.LENGTH_SHORT).show();return;}
            if(!(sl<entry&&tp>entry)){Toast.makeText(this,"BUY LIMIT: SL < Entry < TP",Toast.LENGTH_SHORT).show();return;}
        }
        if("SELL_LIMIT".equals(side)){
            if(entry<=chart.getBid()){Toast.makeText(this,"SELL LIMIT harus di atas BID",Toast.LENGTH_SHORT).show();return;}
            if(!(tp<entry&&sl>entry)){Toast.makeText(this,"SELL LIMIT: TP < Entry < SL",Toast.LENGTH_SHORT).show();return;}
        }
        String id=Long.toString(System.currentTimeMillis());
        String cmd="id="+id+";type=LIMIT;side="+side+";volume="+volume+";entry="+entry+";sl="+sl+";tp="+tp;
        getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putString("next",cmd).apply();
        HistoryStore.add(this,"PENDING",side,entry,sl,tp,tp,lastSignal==null?0:lastSignal.confidence);
        AppLog.add(this,"ORDER","queued "+cmd);notifyLocal("MT5 "+side,"Pending order queued");
    }

    void showMt5Dialog(){
        startGateway();
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        Switch auto=new Switch(this);auto.setText("AUTO PENDING");auto.setChecked(getSharedPreferences("rayyan4_gateway",0).getBoolean("auto",false));
        EditText side=new EditText(this);side.setHint("BUY_LIMIT / SELL_LIMIT");
        EditText vol=new EditText(this);vol.setHint("Lot, contoh 0.01");vol.setInputType(8194);vol.setText("0.01");
        EditText en=new EditText(this);en.setHint("Entry");en.setInputType(8194);
        EditText sl=new EditText(this);sl.setHint("SL");sl.setInputType(8194);
        EditText tp=new EditText(this);tp.setHint("TP");tp.setInputType(8194);
        // Pre-fill the MT5 dialog from the latest calculated signal when available.
        SignalResult current=lastSignal;
        if(current!=null && ("BUY".equals(current.side)||"SELL".equals(current.side))){
            side.setText("BUY".equals(current.side)?"BUY_LIMIT":"SELL_LIMIT");
            en.setText(String.format(Locale.US,"%.2f",current.entry));
            sl.setText(String.format(Locale.US,"%.2f",current.sl));
            tp.setText(String.format(Locale.US,"%.2f",current.tp2>0?current.tp2:current.tp1));
        } else {
            side.setText("");
            Toast.makeText(this,"Menunggu sinyal BUY/SELL untuk mengisi harga otomatis",Toast.LENGTH_LONG).show();
        }
        box.addView(auto);box.addView(side);box.addView(vol);box.addView(en);box.addView(sl);box.addView(tp);
        new AlertDialog.Builder(this).setTitle("MT5 / EA • DEMO").setView(box).setNegativeButton("TUTUP",null)
            .setPositiveButton("KIRIM LIMIT DEMO",(d,w)->{try{getSharedPreferences("rayyan4_gateway",0).edit().putBoolean("auto",auto.isChecked()).apply();
                queueLimit(side.getText().toString().trim().toUpperCase(Locale.US),Double.parseDouble(vol.getText().toString()),Double.parseDouble(en.getText().toString()),Double.parseDouble(sl.getText().toString()),Double.parseDouble(tp.getText().toString()));
            }catch(Exception e){Toast.makeText(this,"Isi semua nilai order dengan benar",Toast.LENGTH_SHORT).show();}}).show();
    }

    @Override protected void onDestroy(){if(ex!=null)ex.shutdownNow();super.onDestroy();}
}