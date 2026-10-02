package com.xauusd.mobileengine;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    static final String BASE="https://biquote.io/api/XAUUSD";
    static final int REQ_NOTIF=9001;

    LinearLayout root, content;
    TextView price, priceChange, connection, signal, signalDetail, confidence, m5Bias, m1State, ticker, metrics, account, botState, log, spreadLine, highLow, resultsBar, boxEntry, boxSl, boxTp1, boxTp2;
    CandleChartView chart;
    SecurityStore store;
    ExnessClient exness;
    Handler handler = new Handler(Looper.getMainLooper());
    ArrayList<StrategyEngine.Candle> m1 = new ArrayList<>(), m5 = new ArrayList<>(), m15 = new ArrayList<>();
    StrategyEngine.Decision lastReady = new StrategyEngine.Decision();
    long lastReadyAt = 0, lastAlertAt = 0;
    String newsTicker = "NEWS: loading...";
    StrategyEngine.Decision decision = new StrategyEngine.Decision();
    double mid=0, spread=0;
    String chartTf="M1";
    double lastPrice=0;
    long lastLoad=0, lastBarsLoad=0;
    static final String FEED="XAUUSD";

    Runnable marketPoll = new Runnable() {
        @Override public void run() {
            loadMarket();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store=new SecurityStore(this);
        exness=new ExnessClient(store);
        buildUi();
        requestNotificationPermission();
        createNotificationChannel();
        loadMarket();
        loadNewsTicker();
        handler.postDelayed(marketPoll,2500);
        handler.postDelayed(newsPoll,300000);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIF);
    }

    TextView tv(String s,int sp){
        TextView t=new TextView(this); t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(sp);
        t.setPadding(14,8,14,8); return t;
    }
    GradientDrawable bg(String c,float r){GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(c));g.setCornerRadius(r);return g;}
    Button btn(String s){
        Button b=new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackground(bg("#151B23",16)); b.setPadding(8,2,8,2); return b;
    }
    EditText input(String hint,String value){
        EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true); e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(14,8,14,8); e.setBackground(bg("#111720",14)); return e;
    }

    void buildUi(){
        setContentView(R.layout.activity_main);
        price=findViewById(R.id.price); priceChange=findViewById(R.id.priceChange);
        connection=findViewById(R.id.connection); ticker=findViewById(R.id.ticker);
        signal=findViewById(R.id.signalState); signalDetail=findViewById(R.id.signalDetail);
        confidence=findViewById(R.id.confidence); m5Bias=findViewById(R.id.m5Bias); m1State=findViewById(R.id.m1State);
        spreadLine=findViewById(R.id.spreadLine); highLow=findViewById(R.id.highLow); resultsBar=findViewById(R.id.resultsBar);
        boxEntry=findViewById(R.id.boxEntry); boxSl=findViewById(R.id.boxSl); boxTp1=findViewById(R.id.boxTp1); boxTp2=findViewById(R.id.boxTp2);
        FrameLayout chartHost=findViewById(R.id.chartHost);
        chart=new CandleChartView(this);
        chartHost.addView(chart,new FrameLayout.LayoutParams(-1,-1));
        chart.setLayers(true,true,true);
        account=findViewById(R.id.account); botState=findViewById(R.id.botState); log=findViewById(R.id.log);
        ticker.setSelected(true);

        findViewById(R.id.tfM1).setOnClickListener(v->{chartTf="M1";chart.resetView();renderChart();});
        findViewById(R.id.tfM5).setOnClickListener(v->{chartTf="M5";chart.resetView();renderChart();});
        findViewById(R.id.tfM15).setOnClickListener(v->{chartTf="M15";chart.resetView();renderChart();});
        findViewById(R.id.btnStart).setOnClickListener(v->startAuto());
        findViewById(R.id.btnStop).setOnClickListener(v->stopAuto());
        findViewById(R.id.btnMenu).setOnClickListener(v->showProfessionalMenu());
        findViewById(R.id.btnExness).setOnClickListener(v->showExnessDialog());
        findViewById(R.id.btnManual).setOnClickListener(v->showManualOrderDialog());
        findViewById(R.id.btnCancelAuto).setOnClickListener(v->showCancelAutoDialog());
        findViewById(R.id.btnLive).setOnClickListener(v->{chart.resetView();});
        findViewById(R.id.btnCopyEntry).setOnClickListener(v->copyPrice("ENTRY",decision.entry));
        findViewById(R.id.btnCopySl).setOnClickListener(v->copyPrice("SL",decision.sl));
        findViewById(R.id.btnCopyTp).setOnClickListener(v->copyPrice("TP1",decision.tp1));
        renderDecision();
    }

    void showProfessionalMenu(){
        String auto=store.rawPrefs().getBoolean("auto",false)?"ON":"OFF";
        String[] items={
            "🔐 Exness API / Instrument",
            "🎯 Manual BUY/SELL LIMIT",
            "🛡 Position Manager",
            "💰 Risk & Strategy",
            "📋 Orders / Account",
            "🔗 MT5 DIRECT • HP ONLY",
            "📲 Telegram",
            "🛑 Cancel Auto BUY",
            "🛑 Cancel Auto SELL",
            "🧹 Cancel ALL Auto LIMIT",
            "🔔 Notification",
            "📜 Log / Status",
            "AUTO ENGINE: "+auto
        };
        new AlertDialog.Builder(this).setTitle("XAUUSD ENGINE • CONTROL").setItems(items,(d,w)->{
            switch(w){
                case 0: showExnessDialog();break;
                case 1: showManualOrderDialog();break;
                case 2: showManagerDialog();break;
                case 3: showRiskDialog();break;
                case 4: showAccountDialog();break;
                case 5: showMt5Dialog();break;
                case 6: showTelegramDialog();break;
                case 7: cancelAutoOrders("buy");break;
                case 8: cancelAutoOrders("sell");break;
                case 9: cancelAutoOrders("all");break;
                case 10: requestNotificationPermission();toast("Notification permission diperiksa.");break;
                case 11: new AlertDialog.Builder(this).setTitle("ENGINE LOG").setMessage(log.getText()).setPositiveButton("OK",null).show();break;
                case 12: if(store.rawPrefs().getBoolean("auto",false))stopAuto(); else startAuto();break;
            }
        }).setNegativeButton("Tutup",null).show();
    }

    void addCard(View v,int h){v.setBackground(bg("#0E131A",18));content.addView(v,new LinearLayout.LayoutParams(-1,h));}

    void showExnessDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,6,18,6);
        ExnessClient.Credentials c=exness.loadCredentials();
        EditText id=input("Trading Account ID",c.accountId);id.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText key=input("EXN API Key",c.apiKey);key.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText secret=input("Secret / Ed25519 Private Key",c.secret);secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText host=input("API Host",c.host);host.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText symbol=input("Instrument (MT5)",store.get("symbol","XAUUSD"));symbol.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        CheckBox direct=new CheckBox(this);direct.setText("Direct Exness API (eligible accounts only)");direct.setTextColor(Color.WHITE);direct.setChecked(true);
        box.addView(id,new LinearLayout.LayoutParams(-1,55));box.addView(key,new LinearLayout.LayoutParams(-1,55));box.addView(secret,new LinearLayout.LayoutParams(-1,55));box.addView(host,new LinearLayout.LayoutParams(-1,55));box.addView(symbol,new LinearLayout.LayoutParams(-1,55));box.addView(direct);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Exness API Connection").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            exness.saveCredentials(id.getText().toString(),key.getText().toString(),secret.getText().toString(),host.getText().toString());
            store.put("symbol",symbol.getText().toString().trim().isEmpty()?"XAUUSD":symbol.getText().toString().trim());
            d.dismiss();connectExness();
        }));
        d.show();
    }

    void loadAccount(){
        new Thread(()->{
            try{
                ExnessClient.Response r=exness.accountInfo();
                runOnUiThread(()->{
                    if(r.ok()) account.setText("Account: "+trim(r.body));
                    else account.setText("Account API: "+r.code);
                });
            }catch(Exception e){ runOnUiThread(()->account.setText("Account: "+e.getMessage())); }
        }).start();
    }

    void connectExness(){
        connection.setText("● CONNECTING EXNESS...");
        new Thread(()->{
            try{
                ExnessClient.Response r=exness.connectAndResolve();
                final String out=r.ok()?"● EXNESS CONNECTED":"● EXNESS ERROR "+r.code;
                runOnUiThread(()->{connection.setText(out);log.setText(r.ok()?"Exness API handshake OK.":"Exness error: "+trim(r.body));if(r.ok())loadAccount();});
            }catch(Exception e){runOnUiThread(()->{connection.setText("● EXNESS ERROR");log.setText(e.getMessage());});}
        }).start();
    }

    void showManualOrderDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        Spinner side=new Spinner(this);side.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"BUY LIMIT","SELL LIMIT"}));
        EditText entry=input("Entry",fmt(decision.entry>0?decision.entry:mid));EditText sl=input("Stop Loss",fmt(decision.sl));
        EditText tp=input("Take Profit",fmt(decision.tp2));EditText lot=input("Lot",store.get("lot","0.01"));
        box.addView(side,new LinearLayout.LayoutParams(-1,55));box.addView(entry,new LinearLayout.LayoutParams(-1,55));box.addView(sl,new LinearLayout.LayoutParams(-1,55));box.addView(tp,new LinearLayout.LayoutParams(-1,55));box.addView(lot,new LinearLayout.LayoutParams(-1,55));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Manual BUY/SELL LIMIT").setView(box).setNegativeButton("Cancel",null).setPositiveButton("PLACE",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String s=side.getSelectedItemPosition()==0?"buy":"sell";
            placeLimit(s,entry.getText().toString(),sl.getText().toString(),tp.getText().toString(),lot.getText().toString(),"MANUAL");
            d.dismiss();
        }));d.show();
    }

    void placeLimit(String side,String entry,String sl,String tp,String lot,String tag){
        try{
            double e=Double.parseDouble(entry), s=Double.parseDouble(sl), t=Double.parseDouble(tp), l=Double.parseDouble(lot);
            if(e<=0||s<=0||t<=0||l<=0)throw new IllegalArgumentException("Nilai order tidak valid");
            if(mid<=0)throw new IllegalStateException("Harga belum tersedia");
            if("buy".equals(side) && !(e<mid && s<e && t>e))throw new IllegalArgumentException("BUY LIMIT harus Entry<Mid, SL<Entry, TP>Entry");
            if("sell".equals(side) && !(e>mid && s>e && t<e))throw new IllegalArgumentException("SELL LIMIT harus Entry>Mid, SL>Entry, TP<Entry");
        }catch(Exception e){toast(e.getMessage());return;}
        new Thread(()->{
            try{
                ExnessClient.Response r=exness.placeLimit(orderSymbol(),side,lot,entry,sl,tp,"XAUUSD-V5.2-"+tag);
                runOnUiThread(()->{
                    if(r.ok()){
                        String op=exness.operationId(r);
                        log.setText("ORDER ACK • "+("buy".equals(side)?"BUY LIMIT":"sell".equals(side)?"SELL LIMIT":"LIMIT")+" • operation "+(op.isEmpty()?"--":op));
                        toast("ACK diterima. Memastikan order masuk akun MT5...");
                        waitForManualOperation(op, side, entry);
                    } else {log.setText("ORDER REJECT "+r.code+": "+trim(r.body));toast("Order ditolak: "+r.code);}
                });
            }catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
        }).start();
    }

    void waitForManualOperation(String operationId,String side,String entry){
        if(operationId==null || operationId.isEmpty()) return;
        new Thread(()->{
            try{
                for(int i=0;i<10;i++){
                    ExnessClient.Response x=exness.operationStatus(operationId);
                    if(x.ok()){
                        JSONObject j=new JSONObject(x.body);
                        String st=j.optString("status","pending");
                        if("confirmed".equalsIgnoreCase(st)){
                            runOnUiThread(()->{
                                log.setText("ORDER CONFIRMED • "+("buy".equals(side)?"BUY LIMIT":"SELL LIMIT")+" @ "+entry+" • MT5 account state updated");
                                toast("ORDER CONFIRMED • cek MT5: Pending Orders");
                            });
                            return;
                        }
                        if("rejected".equalsIgnoreCase(st)||"failed".equalsIgnoreCase(st)){
                            runOnUiThread(()->{
                                log.setText("ORDER FAILED: "+trim(x.body));
                                toast("Order gagal: "+trim(x.body));
                            });
                            return;
                        }
                    }
                    Thread.sleep(1000);
                }
                runOnUiThread(()->log.setText("ORDER ACK masih diproses. Buka Orders / Account untuk melihat status."));
            }catch(Exception e){runOnUiThread(()->log.setText("ORDER STATUS ERROR: "+e.getMessage()));}
        }).start();
    }

    void startAuto(){
        if(!hasCredentials()){toast("Isi Exness API dulu.");showExnessDialog();return;}
        store.rawPrefs().edit().putBoolean("auto",true).apply();
        botState.setText("AUTO: ON  •  guarded execution");
        botState.setTextColor(Color.rgb(0,230,118));
        Intent i=new Intent(this,TradingService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        log.setText("Auto-engine started. It can submit only high-score LIMIT setups within risk guards.");
    }

    void stopAuto(){
        store.rawPrefs().edit().putBoolean("auto",false).apply();
        stopService(new Intent(this,TradingService.class));
        botState.setText("AUTO: OFF");botState.setTextColor(Color.WHITE);
        log.setText("Auto-engine stopped. No new orders will be submitted.");
    }

    boolean hasCredentials(){
        ExnessClient.Credentials c=exness.loadCredentials();
        return !c.accountId.isEmpty()&&!c.apiKey.isEmpty()&&!c.secret.isEmpty();
    }

    void showManagerDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        CheckBox enabled=new CheckBox(this);enabled.setText("Enable TP1 partial-close + Break-even");enabled.setTextColor(Color.WHITE);
        enabled.setChecked(store.rawPrefs().getBoolean("manager_enabled",false));
        EditText pct=input("TP1 close %",store.get("tp1_percent","50"));
        EditText rr=input("TP1 at R",store.get("tp1_r","1.0"));
        CheckBox be=new CheckBox(this);be.setText("Move SL to entry after TP1");be.setTextColor(Color.WHITE);
        be.setChecked(store.rawPrefs().getBoolean("be_on_tp1",true));
        box.addView(enabled);box.addView(pct,new LinearLayout.LayoutParams(-1,52));box.addView(rr,new LinearLayout.LayoutParams(-1,52));box.addView(be);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Position Manager").setView(box).setNegativeButton("Cancel",null).setPositiveButton("SAVE",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            store.rawPrefs().edit().putBoolean("manager_enabled",enabled.isChecked()).putBoolean("be_on_tp1",be.isChecked()).apply();
            store.put("tp1_percent",pct.getText().toString());store.put("tp1_r",rr.getText().toString());
            d.dismiss();toast("Position Manager tersimpan.");
        }));d.show();
    }

    void showRiskDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        EditText lot=input("Fixed lot",store.get("lot","0.01"));
        EditText maxSpread=input("Max spread (price)",store.get("max_spread","0.50"));
        EditText minScore=input("Auto minimum score (0-100)",store.get("min_score","75"));
        EditText maxTrades=input("Max auto orders/day",store.get("max_trades","3"));
        EditText maxLoss=input("Max daily drawdown %",store.get("max_loss","2.0"));
        EditText cooldown=input("Cooldown minutes",store.get("cooldown","10"));
        EditText rr=input("TP2 risk multiple",store.get("rr","2.0"));
        CheckBox autoBuy=new CheckBox(this);autoBuy.setText("Auto BUY LIMIT");autoBuy.setTextColor(Color.WHITE);autoBuy.setChecked(store.rawPrefs().getBoolean("auto_buy",true));
        CheckBox autoSell=new CheckBox(this);autoSell.setText("Auto SELL LIMIT");autoSell.setTextColor(Color.WHITE);autoSell.setChecked(store.rawPrefs().getBoolean("auto_sell",true));
        for(EditText e:new EditText[]{lot,maxSpread,minScore,maxTrades,maxLoss,cooldown,rr})box.addView(e,new LinearLayout.LayoutParams(-1,52));
        box.addView(autoBuy);box.addView(autoSell);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Risk / Strategy Guards").setView(box).setNegativeButton("Cancel",null).setPositiveButton("SAVE",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            store.rawPrefs().edit().putString("lot",lot.getText().toString()).putString("max_spread",maxSpread.getText().toString()).putString("min_score",minScore.getText().toString()).putString("max_trades",maxTrades.getText().toString()).putString("max_loss",maxLoss.getText().toString()).putString("cooldown",cooldown.getText().toString()).putString("rr",rr.getText().toString()).putBoolean("auto_buy",autoBuy.isChecked()).putBoolean("auto_sell",autoSell.isChecked()).apply();
            d.dismiss();toast("Risk settings disimpan.");
        }));d.show();
    }

    void showAccountDialog(){
        new Thread(()->{
            try{
                ExnessClient.Response r=exness.snapshot();
                runOnUiThread(()->{
                    String s=r.ok()?r.body:"Error "+r.code+" "+r.body;
                    new AlertDialog.Builder(this).setTitle("Trading Snapshot").setMessage(s).setPositiveButton("OK",null).show();
                });
            }catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
        }).start();
    }

    void showMt5Dialog(){
        new AlertDialog.Builder(this).setTitle("MT5 DIRECT • HP ONLY")
            .setMessage("Jalur eksekusi V5.2: HP → Exness Public Trader API → akun trading MT5 yang sama. Tidak memakai EA, desktop, atau VPS.\\n\\nBUY LIMIT dan SELL LIMIT dikirim sebagai pending order ke trading account melalui API, lalu status dikonfirmasi dari operation status dan snapshot. MT5/Exness Trade yang login ke akun yang sama akan melihat pending order tersebut.")
            .setPositiveButton("TEST CONNECTION",null).setNegativeButton("OK",null).create().show();
    }

    void showTelegramDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        EditText token=input("Bot token",store.get("tg_token",""));EditText chat=input("Chat ID",store.get("tg_chat",""));
        box.addView(token,new LinearLayout.LayoutParams(-1,55));box.addView(chat,new LinearLayout.LayoutParams(-1,55));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Telegram Alerts").setView(box).setNegativeButton("Cancel",null).setPositiveButton("SAVE",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            store.put("tg_token",token.getText().toString());store.put("tg_chat",chat.getText().toString());d.dismiss();toast("Telegram tersimpan.");
        }));d.show();
    }

    void loadMarket(){
        new Thread(()->{
            try{
                long now=System.currentTimeMillis();
                JSONObject t=new JSONObject(get(BASE));
                double mm=t.optDouble("mid",0),sp=t.optDouble("spread",0);
                ArrayList<StrategyEngine.Candle> a,b;
                if(m1.size()<60 || now-lastBarsLoad>=5000){
                    a=parse(get(BASE+"/ohlc?interval=1m&limit=100"));
                    b=parse(get(BASE+"/ohlc?interval=5m&limit=60"));
                    m15=parse(get(BASE+"/ohlc?interval=15m&limit=60"));
                    lastBarsLoad=now;
                }else{
                    a=cloneWithLive(m1,mm);
                    b=new ArrayList<>(m5);
                }
                if(a.size()<60 || b.size()<30 || m15.size()<20 || mm<=0)return;
                StrategyEngine.Decision dd=StrategyEngine.analyze(a,b,mm);
                runOnUiThread(()->{
                    mid=mm;spread=sp;m1=a;m5=b;decision=dd;lastLoad=now;
                    price.setText(fmt(mid));
                    double prev=lastPrice; lastPrice=mid;
                    priceChange.setText((prev>0&&mid>=prev?"+":"")+fmt(prev>0?mid-prev:0));
                    priceChange.setTextColor(mid>=prev?Color.rgb(0,230,118):Color.rgb(255,82,82));
                    spreadLine.setText("Spread "+fmt(spread));
                    ArrayList<StrategyEngine.Candle> hd="M5".equals(chartTf)?b:"M15".equals(chartTf)?m15:a;
                    if(!hd.isEmpty()) highLow.setText("H "+fmt(hd.stream().mapToDouble(x->x.high).max().orElse(mm))+"   L "+fmt(hd.stream().mapToDouble(x->x.low).min().orElse(mm)));
                    if(ticker!=null)ticker.setText("BIQUOTE • XAUUSD • 1s • "+(t.optBoolean("stale",false)?"STALE":"LIVE"));
                    connection.setText("● 1s MARKET FEED  •  "+(t.optBoolean("stale",false)?"STALE":"LIVE")+"  • Spread "+fmt(spread));
                    renderDecision();
                });
            }catch(Exception e){runOnUiThread(()->connection.setText("● MARKET FEED ERROR • retrying 1s")); }
        }).start();
    }

    ArrayList<StrategyEngine.Candle> cloneWithLive(ArrayList<StrategyEngine.Candle> src,double live){
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(StrategyEngine.Candle c:src)out.add(new StrategyEngine.Candle(c.t,c.open,c.high,c.low,c.close));
        if(!out.isEmpty() && live>0){
            StrategyEngine.Candle c=out.get(out.size()-1);
            c.high=Math.max(c.high,live);c.low=Math.min(c.low,live);c.close=live;
        }
        return out;
    }

    String get(String u)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestMethod("GET");
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));StringBuilder s=new StringBuilder();String x;while((x=r.readLine())!=null)s.append(x);r.close();c.disconnect();return s.toString();
    }

    ArrayList<StrategyEngine.Candle> parse(String s)throws Exception{
        JSONArray ar=new JSONObject(s).getJSONArray("bars");ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(int i=ar.length()-1;i>=0;i--){JSONObject o=ar.getJSONObject(i);out.add(new StrategyEngine.Candle(o.optLong("openTime",0),o.getDouble("open"),o.getDouble("high"),o.getDouble("low"),o.getDouble("close")));}return out;
    }

    void renderChart(){ if(chart!=null){ ArrayList<StrategyEngine.Candle> d="M5".equals(chartTf)?m5:"M15".equals(chartTf)?m15:m1; chart.setData(d,chartTf,mid,decision,mid-spread/2.0,mid+spread/2.0); } }

    void renderDecision(){
        if(signal==null)return;
        boolean buy=decision.side.startsWith("BUY"),sell=decision.side.startsWith("SELL");
        signal.setText(decision.side.equals("WAIT")?"WAIT • SCORE "+decision.score+"/100":decision.side+" • "+(decision.score>=85?"ENTRY READY":"LIMIT ZONE"));
        signal.setTextColor(buy?Color.rgb(0,230,118):sell?Color.rgb(255,82,82):Color.WHITE);
        signalDetail.setText(decision.reason==null||decision.reason.isEmpty()?"M5 Bias → Liquidity Sweep → Wick Rejection → BOS":decision.reason);
        int stars=Math.max(0,Math.min(5,decision.score/20));
        confidence.setText("★★★★★".substring(0,stars)+"☆☆☆☆☆".substring(0,5-stars)+"  "+decision.score+"/100");
        m5Bias.setText("M5 "+(decision.bias?"BIAS CONFIRMED":"WAIT"));
        m1State.setText("M1 "+(decision.side.equals("WAIT")?"WATCH":decision.side));
        boxEntry.setText("ENTRY\n"+fmt(decision.entry)); boxSl.setText("SL\n"+fmt(decision.sl));
        boxTp1.setText("TP1\n"+fmt(decision.tp1)); boxTp2.setText("TP2\n"+fmt(decision.tp2));
        if(botState!=null)botState.setText(store.rawPrefs().getBoolean("auto",false)?"AUTO: ON • GUARDED":"AUTO: OFF • MANUAL");
        if(account!=null)account.setText("Account: "+(hasCredentials()?"API configured":"not connected"));
        if(chart!=null)renderChart();
    }

    String orderSymbol(){String s=store.get("symbol","XAUUSD").trim();return s.isEmpty()?"XAUUSD":s;}
    void showCancelAutoDialog(){
        new AlertDialog.Builder(this).setTitle("🛑 Cancel AUTO Pending Orders")
            .setItems(new String[]{"Cancel AUTO BUY LIMIT","Cancel AUTO SELL LIMIT","Cancel ALL AUTO LIMIT"},(d,which)->{
                if(which==0)cancelAutoOrders("buy"); else if(which==1)cancelAutoOrders("sell"); else cancelAutoOrders("all");
            }).setNegativeButton("Tutup",null).show();
    }

    void cancelAutoOrders(String filter){
        if(!hasCredentials()){toast("Sambungkan Exness API dulu.");return;}
        new Thread(()->{
            try{
                ExnessClient.Response snap=exness.snapshot();
                if(!snap.ok()){runOnUiThread(()->toast("Snapshot error "+snap.code));return;}
                JSONArray ar=new JSONArray();
                JSONObject rootJ=new JSONObject(snap.body);
                JSONArray q=rootJ.optJSONArray("orders");
                if(q==null)q=rootJ.optJSONArray("open_orders");
                if(q!=null)ar=q;
                ArrayList<String> ids=new ArrayList<>();
                ArrayList<String> sides=new ArrayList<>();
                for(int i=0;i<ar.length();i++){
                    JSONObject o=ar.getJSONObject(i);
                    String ins=o.optString("instrument",o.optString("symbol",""));
                    String side=o.optString("side",o.optString("direction","")).toLowerCase(Locale.US);
                    String comment=o.optString("comment","").toUpperCase(Locale.US);
                    String id=o.optString("id",o.optString("order_id",""));
                    if(!orderSymbol().equalsIgnoreCase(ins)||id.isEmpty())continue;
                    if(!comment.contains("AUTO"))continue;
                    if(!"all".equals(filter)&&!filter.equals(side))continue;
                    ids.add(id);sides.add(side);
                }
                if(ids.isEmpty()){runOnUiThread(()->toast("Tidak ada pending AUTO "+filter.toUpperCase(Locale.US)+" untuk "+orderSymbol()));return;}
                int ok=0;
                for(int i=0;i<ids.size();i++){
                    ExnessClient.Response r=exness.cancelOrder(ids.get(i));
                    if(r.ok()){ok++;String op=exness.operationId(r);if(!op.isEmpty())waitForCancelOperation(op,ids.get(i),sides.get(i));}
                }
                int finalOk=ok;
                runOnUiThread(()->{log.setText("CANCEL AUTO • "+finalOk+"/"+ids.size()+" ACK • "+filter.toUpperCase(Locale.US)+" • "+orderSymbol());toast("Cancel request dikirim: "+finalOk+" order");});
            }catch(Exception e){runOnUiThread(()->toast("Cancel error: "+e.getMessage()));}
        }).start();
    }

    void waitForCancelOperation(String op,String orderId,String side){
        new Thread(()->{
            try{
                for(int i=0;i<8;i++){
                    ExnessClient.Response r=exness.operationStatus(op);
                    if(r.ok()){
                        String st=new JSONObject(r.body).optString("status","pending");
                        if("confirmed".equalsIgnoreCase(st)){
                            try{TelegramClient tg=new TelegramClient(store);if(tg.enabled())tg.send("🛑 XAUUSD AUTO "+("buy".equals(side)?"BUY":"SELL")+" CANCELLED\nOrder: "+orderId);}catch(Exception ignored){}
                            runOnUiThread(()->log.setText("CANCEL CONFIRMED • "+orderId));
                            return;
                        }
                        if("rejected".equalsIgnoreCase(st)||"failed".equalsIgnoreCase(st)){runOnUiThread(()->log.setText("CANCEL FAILED • "+orderId));return;}
                    }
                    Thread.sleep(1000);
                }
            }catch(Exception ignored){}
        }).start();
    }

        String fmt(double d){return String.format(Locale.US,"%.2f",d);}
    String trim(String s){return s==null?"":s.length()>800?s.substring(0,800)+"…":s;}
    void toast(String s){Toast.makeText(this,s==null?"":s,Toast.LENGTH_LONG).show();}

    class ChartView extends View{
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ScaleGestureDetector scaleDetector;
        float xZoom=1f,yZoom=1f,xPan=0f,yPan=0f,lastX,lastY,crossX=-1,crossY=-1;
        boolean liveMode=true,showCross=false;
        ChartView(){super(MainActivity.this);scaleDetector=new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector d){float s=d.getScaleFactor();xZoom=Math.max(.65f,Math.min(5f,xZoom*s));yZoom=Math.max(.7f,Math.min(4f,yZoom*s));liveMode=false;invalidate();return true;}
        });}
        ArrayList<StrategyEngine.Candle> data(){return "M5".equals(chartTf)?m5:"M15".equals(chartTf)?m5:m1;}
        void resetView(){xZoom=1;yZoom=1;xPan=0;yPan=0;liveMode=true;invalidate();}
        void zoomX(float f){xZoom=Math.max(.65f,Math.min(5f,xZoom*f));liveMode=false;invalidate();}
        void zoomY(float f){yZoom=Math.max(.7f,Math.min(4f,yZoom*f));liveMode=false;invalidate();}
        public boolean onTouchEvent(MotionEvent e){
            scaleDetector.onTouchEvent(e);
            if(e.getPointerCount()>1)return true;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();crossX=lastX;crossY=lastY;showCross=true;return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!scaleDetector.isInProgress()){float dx=e.getX()-lastX,dy=e.getY()-lastY;xPan+=dx;yPan+=dy;liveMode=false;lastX=e.getX();lastY=e.getY();crossX=e.getX();crossY=e.getY();invalidate();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){if(e.getX()>getWidth()*.86f){resetView();}else{showCross=false;invalidate();}return true;}return true;
        }
        protected void onDraw(Canvas c){
            c.drawColor(Color.rgb(5,8,12));ArrayList<StrategyEngine.Candle> a=data();if(a.size()<2){p.setColor(Color.LTGRAY);p.setTextSize(14);c.drawText("Menunggu market Biquote...",20,40,p);return;}
            int left=12,right=getWidth()-72,top=38,bottom=getHeight()-22;float w=right-left;
            int n=Math.min(a.size(),90);int end=a.size();double hi=-1e99,lo=1e99;
            for(int i=Math.max(0,end-n);i<end;i++){StrategyEngine.Candle z=a.get(i);hi=Math.max(hi,z.high);lo=Math.min(lo,z.low);}
            hi=Math.max(hi,mid);lo=Math.min(lo,mid);double pad=Math.max((hi-lo)*.10,.5);hi+=pad;lo-=pad;
            double center=(hi+lo)/2,range=(hi-lo)/yZoom, max=center+range/2,min=center-range/2;
            java.util.function.DoubleFunction<Float> yy=v->(float)(bottom-((v-min)/(max-min))*(bottom-top)+yPan);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(Color.rgb(28,34,44));
            for(int g=0;g<=6;g++){float y=top+(bottom-top)*g/6f;c.drawLine(left,y,right,y,p);}
            p.setStyle(Paint.Style.FILL);p.setTextSize(11);p.setColor(Color.WHITE);c.drawText("XAU/USD "+chartTf,left,18,p);
            p.setColor(Color.rgb(0,230,118));c.drawText(liveMode?"● LIVE 1s":"● SCROLL",left+100,18,p);
            float step=w/(n+1f)*xZoom,bw=Math.max(2.5f,step*.55f);
            for(int j=0;j<n;j++){int idx=end-n+j;StrategyEngine.Candle z=a.get(idx);float x=right-(n-1-j)*step+xPan; if(x<left-20||x>right+20)continue;
                p.setColor(z.close>=z.open?Color.rgb(38,198,120):Color.rgb(239,83,80));p.setStrokeWidth(1.5f);
                c.drawLine(x,yy.apply(z.high),x,yy.apply(z.low),p);float o=yy.apply(z.open),cl=yy.apply(z.close);c.drawRect(x-bw/2,Math.min(o,cl),x+bw/2,Math.max(o,cl)+1,p);
            }
            double sr=StrategyEngine.resistance(a,45,a.size()-1),ss=StrategyEngine.support(a,45,a.size()-1);
            level(c,sr,yy,"R",Color.rgb(239,83,80));level(c,ss,yy,"S",Color.rgb(66,165,245));
            if(decision.entry>0)level(c,decision.entry,yy,"ENTRY",Color.YELLOW);if(decision.sl>0)level(c,decision.sl,yy,"SL",Color.RED);if(decision.tp1>0)level(c,decision.tp1,yy,"TP1",Color.CYAN);if(decision.tp2>0)level(c,decision.tp2,yy,"TP2",Color.GREEN);level(c,mid,yy,"LIVE",Color.WHITE);
            if(showCross&&crossX>=left&&crossX<=right&&crossY>=top&&crossY<=bottom){p.setColor(Color.argb(160,180,180,180));p.setStrokeWidth(1);c.drawLine(crossX,top,crossX,bottom,p);c.drawLine(left,crossY,right,crossY,p);}
            p.setColor(Color.LTGRAY);p.setTextSize(10);c.drawText("← drag →   ↑↓ move   pinch/zoom   tap RIGHT = LIVE",left,bottom+18,p);
        }
        void level(Canvas c,double v,java.util.function.DoubleFunction<Float> yy,String s,int col){float y=yy.apply(v);if(y<30||y>getHeight()-20)return;p.setColor(col);p.setStrokeWidth(s.equals("LIVE")?2:1.2f);c.drawLine(12,y,getWidth()-72,y,p);p.setTextSize(9);c.drawText(s+" "+fmt(v),getWidth()-68,y-3,p);}
    }
}
