package com.xauusd.mobileengine;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.util.Log;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.animation.LinearInterpolator;
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
    MetaApiClient metaApi;
    GatewayClient gateway;
    FxOpenTickTraderClient fxOpen;
    Handler handler = new Handler(Looper.getMainLooper());
    ArrayList<StrategyEngine.Candle> m1 = new ArrayList<>(), m5 = new ArrayList<>(), m15 = new ArrayList<>();
    StrategyEngine.Decision lastReady = new StrategyEngine.Decision();
    long lastReadyAt = 0, lastAlertAt = 0, lastAccountUiAt = 0;
    String lastHistoryKey = "";
    String newsTicker = "NEWS: loading...";
    ObjectAnimator tickerAnimator;
    String tickerRendered = "";
    String lastTickerText = "";
    long lastBugScanAt = 0;
    StrategyEngine.Decision decision = new StrategyEngine.Decision();
    double mid=0, spread=0;
    String chartTf="M1";
    double lastPrice=0;
    long lastLoad=0, lastM1BarsLoad=0, lastM5BarsLoad=0, lastM15BarsLoad=0;
    volatile boolean marketBusy=false;
    long feedBackoffUntil=0;
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
        metaApi=new MetaApiClient(store);
        gateway=new GatewayClient(this);
        fxOpen=new FxOpenTickTraderClient(store);
        buildUi();
        requestNotificationPermission();
        createNotificationChannel();
        loadMarket();
        if (store.rawPrefs().getBoolean("auto", false) && hasCredentials()) handler.postDelayed(this::resumeBackgroundEngine, 1200);
        loadNewsTicker();
        handler.postDelayed(marketPoll,1000);
        handler.postDelayed(newsPoll,60000);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(tickerAnimator!=null){tickerAnimator.cancel();tickerAnimator=null;}
        super.onDestroy();
    }

    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIF);
    }

    TextView lamp;

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
        connection=findViewById(R.id.connection); ticker=findViewById(R.id.ticker); lamp=findViewById(R.id.lamp);
        signal=findViewById(R.id.signalState); signalDetail=findViewById(R.id.signalDetail);
        confidence=findViewById(R.id.confidence); m5Bias=findViewById(R.id.m5Bias); m1State=findViewById(R.id.m1State);
        spreadLine=findViewById(R.id.spreadLine); highLow=findViewById(R.id.highLow); resultsBar=findViewById(R.id.resultsBar);
        boxEntry=findViewById(R.id.boxEntry); boxSl=findViewById(R.id.boxSl); boxTp1=findViewById(R.id.boxTp1); boxTp2=findViewById(R.id.boxTp2);
        FrameLayout chartHost=findViewById(R.id.chartHost);
        chart=new CandleChartView(this);
        chartHost.addView(chart,new FrameLayout.LayoutParams(-1,-1));
        chart.setLayers(true,true,true);
        account=findViewById(R.id.account); botState=findViewById(R.id.botState); log=findViewById(R.id.log);
        
        findViewById(R.id.tfM1).setOnClickListener(v->{chartTf="M1";chart.resetView();renderChart();});
        findViewById(R.id.tfM5).setOnClickListener(v->{chartTf="M5";chart.resetView();renderChart();});
        findViewById(R.id.tfM15).setOnClickListener(v->{chartTf="M15";chart.resetView();renderChart();});
        findViewById(R.id.btnStart).setOnClickListener(v->startAuto());
        findViewById(R.id.btnStop).setOnClickListener(v->stopAuto());
        findViewById(R.id.btnMenu).setOnClickListener(v->showProfessionalMenu());
        Button exBtn=findViewById(R.id.btnExness); exBtn.setText("🎯 EXNESS MT5");
        exBtn.setOnClickListener(v->showExnessDialog());
        findViewById(R.id.btnManual).setOnClickListener(v->showManualOrderDialog());
        findViewById(R.id.btnCancelAuto).setOnClickListener(v->showCancelAutoDialog());
        findViewById(R.id.btnLive).setOnClickListener(v->{chart.resetView();loadMarket();});
        findViewById(R.id.btnCopyEntry).setOnClickListener(v->copyPrice("ENTRY",decision.entry));
        findViewById(R.id.btnCopySl).setOnClickListener(v->copyPrice("SL",decision.sl));
        findViewById(R.id.btnCopyTp).setOnClickListener(v->copyPrice("TP1",decision.tp1));
        boxEntry.setOnClickListener(v->copyPrice("ENTRY",decision.entry));
        boxSl.setOnClickListener(v->copyPrice("SL",decision.sl));
        boxTp1.setOnClickListener(v->copyPrice("TP1",decision.tp1));
        boxTp2.setOnClickListener(v->copyPrice("TP2",decision.tp2));
        ticker.setSelected(true); ticker.setHorizontallyScrolling(true); ticker.setMarqueeRepeatLimit(-1);
        renderDecision();
    }

    void showProfessionalMenu(){
        String auto=store.rawPrefs().getBoolean("auto",false)?"ON":"OFF";
        String[] items={
            "🔐 Exness API / Instrument",
            "☁️ MetaApi • MT5 CLOUD → EXNESS",
            "📱 HP B Gateway → MT5 CLOUD",
            "🎯 Manual BUY/SELL LIMIT",
            "📊 Indicators / Fibonacci",
            "🔎 Scan Market • Entry Ready",
            "⚡ Signal Min Score • "+store.rawPrefs().getInt("display_min_score",60),
            "🛡 Position Manager",
            "💰 Risk & Strategy",
            "📋 Orders / Account",
            "📋 Signal History",
            "🔗 FXOPEN TICKTRADER • HP ONLY",
            "📲 Telegram",
            "🛑 Cancel Auto Pending",
            "🔔 Notification",
            "📜 Log / Status",
            "AUTO ENGINE: "+auto
        };
        new AlertDialog.Builder(this).setTitle("XAUUSD ENGINE • CONTROL").setItems(items,(d,w)->{
            switch(w){
                case 0: showExnessDialog();break;
                case 1: showMetaApiDialog();break;
                case 2: showGatewayDialog();break;
                case 3: showManualOrderDialog();break;
                case 3: showIndicatorDialog();break;
                case 4: scanMarket();break;
                case 5: showSignalThresholdDialog();break;
                case 6: showManagerDialog();break;
                case 7: showRiskDialog();break;
                case 8: showOrderManagerDialog();break;
                case 9: showHistoryDialog();break;
                case 10: showFxOpenDialog();break;
                case 11: showTelegramDialog();break;
                case 12: showCancelAutoDialog();break;
                case 13: requestNotificationPermission();testAlarm();break;
                case 14: new AlertDialog.Builder(this).setTitle("ENGINE LOG").setMessage(log.getText()).setPositiveButton("OK",null).show();break;
                case 15: if(store.rawPrefs().getBoolean("auto",false))stopAuto(); else startAuto();break;
            }
        }).setNegativeButton("Tutup",null).show();
    }

    void addCard(View v,int h){v.setBackground(bg("#0E131A",18));content.addView(v,new LinearLayout.LayoutParams(-1,h));}

    void showGatewayDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,6,18,6);
        EditText host=input("HP B address: http://192.168.x.x:8787",gateway.p.getString("host",""));
        EditText tok=input("Gateway token",gateway.p.getString("token",""));tok.setInputType(129);
        TextView info=tv("HP A dan HP B harus satu Wi‑Fi/hotspot. Gateway V4 di HP B meneruskan order ke MetaKit → Exness MT5.",11);
        box.addView(info,new LinearLayout.LayoutParams(-1,72));box.addView(host,new LinearLayout.LayoutParams(-1,58));box.addView(tok,new LinearLayout.LayoutParams(-1,58));
        new AlertDialog.Builder(this).setTitle("📱 HP B TRADING GATEWAY").setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE + TEST",null).create().setOnShowListener(x->{});
        AlertDialog d=new AlertDialog.Builder(this).setTitle("📱 HP B TRADING GATEWAY").setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String h=host.getText().toString().trim();String t=tok.getText().toString().trim();if(h.isEmpty()||t.isEmpty()){toast("Host dan token wajib diisi");return;}gateway.save(h,t);d.dismiss();new Thread(()->{try{String q=gateway.request("GET","/health",null);runOnUiThread(()->{log.setText("HP B GATEWAY • "+q);toast(q.startsWith("200|")?"GATEWAY CONNECTED":"GATEWAY ERROR");});}catch(Exception e){runOnUiThread(()->toast("Gateway: "+e.getMessage()));}}).start();}));d.show();
    }

    void showMetaApiDialog(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18,6,18,6);

        EditText token=input("MetaApi API Token",store.get("meta_token",""));
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        EditText account=input("MetaApi Account ID",store.get("meta_account",""));
        account.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        EditText host=input("MetaApi Host (default resmi)",store.get("meta_host",MetaApiClient.DEFAULT_HOST));
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        EditText symbol=input("Instrument",store.get("symbol","XAUUSD"));
        symbol.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        TextView info=tv("Jalur: HP APK → MetaApi Cloud → Exness MT5\nToken dibuat dari MetaApi Web App. Account ID adalah ID akun yang sudah ditambahkan di MetaApi.",11);
        info.setTextColor(Color.LTGRAY);

        box.addView(info,new LinearLayout.LayoutParams(-1,72));
        box.addView(token,new LinearLayout.LayoutParams(-1,58));
        box.addView(account,new LinearLayout.LayoutParams(-1,58));
        box.addView(host,new LinearLayout.LayoutParams(-1,58));
        box.addView(symbol,new LinearLayout.LayoutParams(-1,58));

        AlertDialog d=new AlertDialog.Builder(this)
            .setTitle("☁️ METAAPI • MT5 CLOUD")
            .setView(box)
            .setNegativeButton("Tutup",null)
            .setPositiveButton("SAVE + TEST",null).create();

        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String tok=token.getText().toString().trim();
            String acc=account.getText().toString().trim();
            String h=host.getText().toString().trim();
            String sym=symbol.getText().toString().trim();
            if(tok.isEmpty()){toast("MetaApi Token belum diisi");return;}
            if(acc.isEmpty()){toast("MetaApi Account ID belum diisi");return;}
            if(h.isEmpty())h=MetaApiClient.DEFAULT_HOST;
            if(sym.isEmpty())sym="XAUUSD";
            store.put("meta_token",tok);
            store.put("meta_account",acc);
            store.put("meta_host",h);
            store.put("symbol",sym);
            store.put("active_connector","metaapi");
            d.dismiss();
            connection.setText("● CONNECTING METAAPI...");
            new Thread(()->{
                try{
                    MetaApiClient.Response a=metaApi.accountInfo();
                    MetaApiClient.Response o=a.ok()?metaApi.orders():a;
                    runOnUiThread(()->{
                        if(a.ok()&&o.ok()){
                            connection.setText("● MT5 CLOUD CONNECTED");
                            log.setText("MetaApi OK • Exness MT5 account connected • Orders API OK");
                            toast("METAAPI CONNECTED");
                            loadAccount();
                        }else{
                            connection.setText("● METAAPI ERROR");
                            log.setText("MetaApi error • "+(a.ok()?o.code:a.code)+" • "+trim(a.ok()?o.body:a.body));
                            toast("MetaApi gagal. Cek Token / Account ID.");
                        }
                    });
                }catch(Exception e){
                    runOnUiThread(()->{
                        connection.setText("● METAAPI ERROR");
                        log.setText("MetaApi error • "+e.getMessage());
                        toast("MetaApi error: "+e.getMessage());
                    });
                }
            }).start();
        }));
        d.show();
    }

    void showExnessDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,6,18,6);
        ExnessClient.Credentials c=exness.loadCredentials();
        EditText id=input("Trading Account ID",c.accountId);id.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText key=input("EXN API Key",c.apiKey);key.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText secret=input("Secret / Ed25519 Private Key",c.secret);secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText host=input("API Host (kosong = default resmi)",c.host);host.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText symbol=input("Instrument (MT5)",store.get("symbol","XAUUSD"));symbol.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        CheckBox direct=new CheckBox(this);direct.setText("Direct Exness API (eligible accounts only)");direct.setTextColor(Color.WHITE);direct.setChecked(true);
        box.addView(id,new LinearLayout.LayoutParams(-1,55));box.addView(key,new LinearLayout.LayoutParams(-1,55));box.addView(secret,new LinearLayout.LayoutParams(-1,55));box.addView(host,new LinearLayout.LayoutParams(-1,55));box.addView(symbol,new LinearLayout.LayoutParams(-1,55));box.addView(direct);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("EXNESS MT5 • Public Trader API").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String hostValue=host.getText().toString().trim();
            if(hostValue.isEmpty()) hostValue=ExnessClient.DEFAULT_HOST;
            exness.saveCredentials(id.getText().toString(),key.getText().toString(),secret.getText().toString(),hostValue);
            store.put("symbol",symbol.getText().toString().trim().isEmpty()?"XAUUSD":symbol.getText().toString().trim());
            store.put("active_connector","exness");
            d.dismiss();connectExness();
        }));
        d.show();
    }

    void loadAccount(){
        new Thread(()->{
            try{
                if(useFxOpen()){
                    FxOpenTickTraderClient.Response r=fxOpen.accountInfo();
                    runOnUiThread(()->account.setText(r.ok()?"FXOpen Account: CONNECTED":"FXOpen Account API: "+r.code+" • "+trim(r.body)));
                    return;
                }
                ExnessClient.Response r=exness.accountInfo();
                runOnUiThread(()->{
                    if(r.ok()) account.setText("Account: "+trim(r.body));
                    else account.setText("Account API: "+r.code+" • "+trim(r.body));
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
                if(gateway.configured()){
                    String gr=gateway.command("BUY".equalsIgnoreCase(side)?"BUY_LIMIT":"SELL_LIMIT",side,Double.parseDouble(lot),Double.parseDouble(entry),Double.parseDouble(sl),Double.parseDouble(tp),null,"hpA-"+System.currentTimeMillis());
                    runOnUiThread(()->{if(gr.startsWith("200|")){log.setText("HP B GATEWAY • ORDER ACCEPTED • "+trim(gr.substring(4)));toast("GATEWAY ORDER DITERIMA");}else{log.setText("GATEWAY ORDER REJECT • "+trim(gr));toast("Gateway order gagal");}});
                    return;
                }
                if(useMetaApi()){
                    MetaApiClient.Response mr=metaApi.placeLimit(orderSymbol(),side,Double.parseDouble(lot),Double.parseDouble(entry),Double.parseDouble(sl),Double.parseDouble(tp),"XAUUSD-V5.2-"+tag);
                    runOnUiThread(()->{
                        if(mr.ok()){ log.setText("MT5 CLOUD ORDER ACCEPTED • "+side.toUpperCase(Locale.US)+" LIMIT • "+trim(mr.body)); toast("MT5 PENDING ORDER DITERIMA"); }
                        else { log.setText("MT5 CLOUD ORDER REJECT "+mr.code+" • "+trim(mr.body)); toast("MT5 order ditolak "+mr.code); }
                    });
                    return;
                }
                if(useFxOpen()){
                    FxOpenTickTraderClient.Response fr=fxOpen.placeLimit(orderSymbol(),side,Double.parseDouble(lot),Double.parseDouble(entry),Double.parseDouble(sl),Double.parseDouble(tp),"XAUUSD-V5.2-"+tag);
                    runOnUiThread(()->{
                        if(fr.ok()){ log.setText("FXOPEN ORDER ACCEPTED • "+side.toUpperCase(Locale.US)+" LIMIT • "+trim(fr.body)); toast("FXOpen DEMO ORDER DITERIMA"); }
                        else { log.setText("FXOPEN ORDER REJECT "+fr.code+" • "+trim(fr.body)); toast("FXOpen order ditolak "+fr.code); }
                    });
                    return;
                }
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
        if(!hasCredentials()){toast("Sambungkan MT5 Cloud terlebih dahulu.");showMt5Dialog();return;}
        final boolean cloud=useMetaApi();
        final boolean direct="exness".equalsIgnoreCase(store.get("active_connector",""));
        if(!cloud&&!direct){toast("Pilih MT5 Cloud (MetaApi) atau Exness API.");showMt5Dialog();return;}
        log.setText(cloud?"AUTO PREFLIGHT • MT5 CLOUD → EXNESS MT5...":"AUTO PREFLIGHT • EXNESS API...");
        new Thread(()->{
            try{
                if(cloud){
                    MetaApiClient.Response a=metaApi.accountInfo();
                    if(!a.ok())throw new IllegalStateException("MetaApi "+a.code+": "+trim(a.body));
                    MetaApiClient.Response o=metaApi.orders();
                    if(!o.ok())throw new IllegalStateException("MT5 Orders "+o.code+": "+trim(o.body));
                }else{
                    ExnessClient.Response a=exness.connectAndResolve();
                    if(!a.ok())throw new IllegalStateException("Exness API "+a.code+": "+trim(a.body));
                    ExnessClient.Response i=exness.instrumentConditions(orderSymbol());
                    if(!i.ok())throw new IllegalStateException("Instrument "+orderSymbol()+" "+i.code+": "+trim(i.body));
                }
                store.rawPrefs().edit().putBoolean("auto",true).apply();
                runOnUiThread(()->requestBatteryOptimizationExemption());
                runOnUiThread(()->{
                    botState.setText(cloud?"AUTO: ON • MT5 CLOUD → EXNESS MT5":"AUTO: ON • EXNESS MT5 API");
                    botState.setTextColor(Color.rgb(0,230,118));
                    Intent intent=new Intent(this,TradingService.class);
                    if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
                    log.setText(cloud?"AUTO ON • MetaApi cloud connected • Exness MT5 execution ready":"AUTO ON • Exness API connected");
                });
            }catch(Exception e){runOnUiThread(()->{store.rawPrefs().edit().putBoolean("auto",false).apply();botState.setText("AUTO: OFF • CONNECTOR ERROR");log.setText("AUTO PREFLIGHT ERROR • "+e.getMessage());toast("Auto gagal: "+e.getMessage());});}
        }).start();
    }

    void stopAuto(){
        store.rawPrefs().edit().putBoolean("auto",false).apply();
        stopService(new Intent(this,TradingService.class));
        botState.setText("AUTO: OFF");botState.setTextColor(Color.WHITE);
        log.setText("Auto-engine stopped. No new orders will be submitted.");
    }

    boolean useFxOpen(){
        String active=store.get("active_connector","");
        return "fxopen".equalsIgnoreCase(active) && fxOpen!=null && fxOpen.configured();
    }
    boolean useMetaApi(){
        String active=store.get("active_connector","");
        return "metaapi".equalsIgnoreCase(active) && metaApi!=null && metaApi.configured();
    }

    String executionLabel(){
        String active=store.get("active_connector","").trim();
        if("metaapi".equalsIgnoreCase(active) && metaApi!=null && metaApi.configured()) return "MT5 CLOUD • EXNESS";
        if("fxopen".equalsIgnoreCase(active) && fxOpen!=null && fxOpen.configured()) return "FXOPEN DEMO";
        if("exness".equalsIgnoreCase(active) && exness!=null){
            ExnessClient.Credentials c=exness.loadCredentials();
            if(!c.accountId.isEmpty() && !c.apiKey.isEmpty() && !c.secret.isEmpty()) return "EXNESS MT5 API";
        }
        return "NOT CONNECTED";
    }

    void resumeBackgroundEngine(){
        try{
            Intent intent=new Intent(this,TradingService.class);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(intent); else startService(intent);
            botState.setText("AUTO: ON • BACKGROUND");
            botState.setTextColor(Color.rgb(0,230,118));
        }catch(Exception e){ log.setText("BACKGROUND ENGINE ERROR • "+e.getMessage()); }
    }

    void requestBatteryOptimizationExemption(){
        if(Build.VERSION.SDK_INT>=23){
            try{
                android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);
                String pkg=getPackageName();
                if(!pm.isIgnoringBatteryOptimizations(pkg)){
                    Intent i=new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(android.net.Uri.parse("package:"+pkg));
                    startActivity(i);
                }
            }catch(Exception ignored){}
        }
    }

    boolean hasCredentials(){
        if(metaApi!=null&&useMetaApi())return true;
        if(fxOpen!=null&&useFxOpen())return true;
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

    void showSignalThresholdDialog(){
        int current=store.rawPrefs().getInt("display_min_score",60);
        EditText e=input("Minimum signal score (45-90)",String.valueOf(current));
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("⚡ Signal Minimum Score")
            .setMessage("Ambang tampilan ENTRY WATCH. Auto execution tetap memakai Auto minimum score di Risk / Strategy.")
            .setView(e).setNegativeButton("Tutup",null).setPositiveButton("SAVE",(d,w)->{
                int v=current;
                try{v=Integer.parseInt(e.getText().toString().trim());}catch(Exception ignored){}
                v=Math.max(45,Math.min(90,v));
                store.rawPrefs().edit().putInt("display_min_score",v).apply();
                toast("Signal minimum score: "+v);
                renderDecision();
            }).show();
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
        if(useFxOpen()){
            new Thread(()->{try{FxOpenTickTraderClient.Response r=fxOpen.accountInfo();runOnUiThread(()->new AlertDialog.Builder(this).setTitle("FXOpen Trading Snapshot").setMessage(r.ok()?r.body:"Error "+r.code+" "+r.body).setPositiveButton("OK",null).show());}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}}).start();
            return;
        }
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

    void showOrderManagerDialog(){
        if(useFxOpen()){showFxOpenOrderManagerDialog();return;}
        if(!hasCredentials()){toast("Sambungkan Exness API dulu.");return;}
        new Thread(()->{
            try{
                ExnessClient.Response r=exness.snapshot();
                if(!r.ok())throw new IllegalStateException("Snapshot "+r.code+": "+trim(r.body));
                JSONObject root=new JSONObject(r.body); JSONArray orderData=root.optJSONArray("orders");if(orderData==null)orderData=root.optJSONArray("open_orders");
                JSONArray positionData=root.optJSONArray("positions");if(positionData==null)positionData=root.optJSONArray("open_positions");
                final JSONArray orders=orderData, positions=positionData;
                runOnUiThread(()->{
                    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(12,4,12,4);
                    TextView head=tv("PENDING ORDERS / OPEN POSITIONS",12);head.setTextColor(Color.WHITE);box.addView(head);
                    if(orders!=null && orders.length()>0)for(int i=0;i<orders.length();i++)addOrderRow(box,orders.optJSONObject(i));
                    else box.addView(tv("Tidak ada pending order.",11));
                    box.addView(tv("OPEN POSITIONS",12));
                    if(positions!=null && positions.length()>0)for(int i=0;i<positions.length();i++)addPositionRow(box,positions.optJSONObject(i));
                    else box.addView(tv("Tidak ada posisi aktif.",11));
                    AlertDialog d=new AlertDialog.Builder(this).setTitle("📋 ORDER MANAGER").setView(box)
                        .setNegativeButton("Tutup",null).setNeutralButton("CANCEL ID",null).create();
                    d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{d.dismiss();showCancelByIdDialog();}));
                    d.show();
                });
            }catch(Exception e){runOnUiThread(()->toast("Order Manager: "+e.getMessage()));}
        }).start();
    }

    void addOrderRow(LinearLayout box,JSONObject o){
        if(o==null)return;
        String id=o.optString("id",o.optString("order_id","--"));
        String side=o.optString("side",o.optString("direction","--"));
        String ins=o.optString("instrument",o.optString("symbol",orderSymbol()));
        String priceV=o.optString("price",o.optString("entry_price","--"));
        String vol=o.optString("volume","--");
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t=tv(side.toUpperCase(Locale.US)+" "+ins+" @ "+priceV+" • "+vol+" • ID "+id,10);row.addView(t,new LinearLayout.LayoutParams(0,60,1));
        Button b=btn("CANCEL");b.setOnClickListener(v->confirmCancelOrder(id));row.addView(b,new LinearLayout.LayoutParams(82,60)); Button m=btn("EDIT");m.setOnClickListener(v->showModifyOrderDialog(id));row.addView(m,new LinearLayout.LayoutParams(70,60));
        box.addView(row);
    }

    void addPositionRow(LinearLayout box,JSONObject p){
        if(p==null)return;
        String id=p.optString("id",p.optString("position_id","--"));
        String side=p.optString("side",p.optString("direction","--"));
        String entry=p.optString("open_price",p.optString("entry_price","--"));
        String vol=p.optString("volume",p.optString("current_volume","--"));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t=tv("POS "+side.toUpperCase(Locale.US)+" @ "+entry+" • "+vol+" • ID "+id,10);row.addView(t,new LinearLayout.LayoutParams(0,60,1));
        Button close=btn("CLOSE");close.setOnClickListener(v->showClosePositionDialog(id,vol));row.addView(close,new LinearLayout.LayoutParams(90,60));
        box.addView(row);
    }

    void confirmCancelOrder(String id){
        if(useFxOpen()){confirmCancelFxOpenOrder(id);return;}
        new AlertDialog.Builder(this).setTitle("Cancel order").setMessage("Batalkan order ID "+id+"?")
            .setNegativeButton("Tidak",null).setPositiveButton("Ya", (d,w)->{
                new Thread(()->{try{ExnessClient.Response r=exness.cancelOrder(id);String op=exness.operationId(r);runOnUiThread(()->{log.setText(r.ok()?"CANCEL REQUEST • "+id:"CANCEL FAILED • "+r.code);toast(r.ok()?"Cancel request diterima":"Cancel gagal: "+trim(r.body));});if(r.ok()&&!op.isEmpty())waitForCancelOperation(op,id,"");}catch(Exception e){runOnUiThread(()->toast("Cancel error: "+e.getMessage()));}}).start();
            }).show();
    }

    void showCancelByIdDialog(){
        EditText id=input("Order ID","");id.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Cancel by Order ID").setView(id).setNegativeButton("Tutup",null).setPositiveButton("CANCEL", (d,w)->confirmCancelOrder(id.getText().toString().trim())).show();
    }

    void showClosePositionDialog(String id,String currentVolume){
        if(useFxOpen()){showCloseFxOpenTradeDialog(id,currentVolume);return;}
        EditText vol=input("Volume (kosong = full close)","");
        vol.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new AlertDialog.Builder(this).setTitle("Close / Partial Close").setView(vol).setNegativeButton("Tutup",null).setPositiveButton("CLOSE", (d,w)->{
            String v=vol.getText().toString().trim();
            new Thread(()->{try{ExnessClient.Response r=exness.closePosition(id,v);runOnUiThread(()->{log.setText(r.ok()?"CLOSE REQUEST • "+id:"CLOSE FAILED • "+r.code);toast(r.ok()?"Close request diterima":"Close gagal: "+trim(r.body));});}catch(Exception e){runOnUiThread(()->toast("Close error: "+e.getMessage()));}}).start();
        }).show();
    }

    void showModifyOrderDialog(String id){
        if(useFxOpen()){showModifyFxOpenTradeDialog(id);return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(12,4,12,4);
        EditText price=input("Modify Entry","");EditText sl=input("Modify SL","");EditText tp=input("Modify TP","");
        box.addView(price);box.addView(sl);box.addView(tp);
        new AlertDialog.Builder(this).setTitle("Modify Order "+id).setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE",(d,w)->{
            new Thread(()->{try{ExnessClient.Response r=exness.modifyOrder(id,price.getText().toString(),sl.getText().toString(),tp.getText().toString());runOnUiThread(()->{log.setText(r.ok()?"MODIFY REQUEST • "+id:"MODIFY FAILED • "+r.code);toast(r.ok()?"Modify request diterima":"Modify gagal: "+trim(r.body));});}catch(Exception e){runOnUiThread(()->toast("Modify error: "+e.getMessage()));}}).start();
        }).show();
    }

    void showFxOpenDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        EditText id=input("Web API ID",store.get("fx_id",""));id.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText key=input("Web API Key",store.get("fx_key",""));key.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText secret=input("Web API Secret",store.get("fx_secret",""));secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText host=input("FXOpen Demo API Host",store.get("fx_host",FxOpenTickTraderClient.DEFAULT_DEMO_HOST));host.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(tv("FXOPEN TICKTRADER • FREE API • DEMO\nHP → APK → FXOpen TickTrader Web API.",11));
        box.addView(id,new LinearLayout.LayoutParams(-1,55));
        box.addView(key,new LinearLayout.LayoutParams(-1,55));
        box.addView(secret,new LinearLayout.LayoutParams(-1,55));
        box.addView(host,new LinearLayout.LayoutParams(-1,55));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("🔗 FXOPEN TICKTRADER API").setView(box)
            .setNegativeButton("Tutup",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            fxOpen.save(id.getText().toString(),key.getText().toString(),secret.getText().toString(),host.getText().toString());
            store.put("active_connector","fxopen");
            d.dismiss();testFxOpen();
        }));
        d.show();
    }

    void testFxOpen(){
        if(!useFxOpen()){toast("Isi Web API ID + Key + Secret.");return;}
        new Thread(()->{
            try{
                FxOpenTickTraderClient.Response r=fxOpen.accountInfo();
                runOnUiThread(()->{
                    if(r.ok()){
                        log.setText("FXOPEN CONNECTED • TickTrader account OK\n"+trim(r.body));
                        refreshAccountUi();
                        toast("FXOpen CONNECTED • siap test pending order");
                    }else{
                        log.setText("FXOPEN CONNECT ERROR "+r.code+" • "+trim(r.body));
                        toast("FXOpen connector error "+r.code);
                    }
                });
            }catch(Exception e){runOnUiThread(()->toast("FXOpen connector: "+e.getMessage()));}
        }).start();
    }

    JSONArray fxOpenTradesArray(String body){
        try{
            if(body==null||body.trim().isEmpty())return new JSONArray();
            String z=body.trim();
            if(z.startsWith("["))return new JSONArray(z);
            JSONObject o=new JSONObject(z);
            JSONArray a=o.optJSONArray("Trades"); if(a!=null)return a;
            a=o.optJSONArray("trades"); if(a!=null)return a;
            a=o.optJSONArray("Result"); if(a!=null)return a;
            JSONObject rr=o.optJSONObject("Result");
            if(rr!=null){
                a=rr.optJSONArray("Trades");if(a!=null)return a;
                a=rr.optJSONArray("trades");if(a!=null)return a;
            }
        }catch(Exception ignored){}
        return new JSONArray();
    }

    void showFxOpenOrderManagerDialog(){
        new Thread(()->{
            try{
                FxOpenTickTraderClient.Response r=fxOpen.trades();
                if(!r.ok())throw new IllegalStateException("FXOpen Trades "+r.code+": "+trim(r.body));
                JSONArray a=fxOpenTradesArray(r.body);
                runOnUiThread(()->{
                    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(12,4,12,4);
                    box.addView(tv("FXOPEN TICKTRADER • ORDERS / TRADES",12));
                    if(a.length()==0)box.addView(tv("Tidak ada order/trade.",11));
                    for(int i=0;i<a.length();i++)addFxOpenTradeRow(box,a.optJSONObject(i));
                    AlertDialog d=new AlertDialog.Builder(this).setTitle("📋 FXOPEN ORDER MANAGER").setView(box)
                        .setNegativeButton("Tutup",null).setNeutralButton("CANCEL ID",null).create();
                    d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{d.dismiss();showCancelByIdDialog();}));
                    d.show();
                });
            }catch(Exception e){runOnUiThread(()->toast("FXOpen Order Manager: "+e.getMessage()));}
        }).start();
    }

    void addFxOpenTradeRow(LinearLayout box,JSONObject o){
        if(o==null)return;
        String id=o.optString("Id",o.optString("id","--"));
        String side=o.optString("Side",o.optString("side","--"));
        String type=o.optString("Type",o.optString("type","--"));
        String sym=o.optString("Symbol",o.optString("symbol",orderSymbol()));
        String priceV=o.optString("Price",o.optString("price","--"));
        String amount=o.optString("Amount",o.optString("amount","--"));
        String status=o.optString("Status",o.optString("status","--"));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t=tv(side.toUpperCase(Locale.US)+" "+type+" "+sym+" @ "+priceV+" • "+amount+" • "+status+" • ID "+id,10);
        row.addView(t,new LinearLayout.LayoutParams(0,64,1));
        Button b=btn("CANCEL");b.setOnClickListener(v->confirmCancelFxOpenOrder(id));row.addView(b,new LinearLayout.LayoutParams(82,64));
        box.addView(row);
    }

    void confirmCancelFxOpenOrder(String id){
        new AlertDialog.Builder(this).setTitle("Cancel FXOpen order").setMessage("Batalkan order ID "+id+"?")
            .setNegativeButton("Tidak",null).setPositiveButton("Ya",(d,w)->new Thread(()->{
                try{
                    FxOpenTickTraderClient.Response r=fxOpen.cancel(id);
                    runOnUiThread(()->{log.setText(r.ok()?"FXOPEN CANCEL CONFIRMED • "+id:"FXOPEN CANCEL FAILED • "+r.code+" • "+trim(r.body));toast(r.ok()?"Order FXOpen dibatalkan":"Cancel gagal: "+r.code);});
                }catch(Exception e){runOnUiThread(()->toast("FXOpen cancel: "+e.getMessage()));}
            }).start()).show();
    }

    void showCloseFxOpenTradeDialog(String id,String currentVolume){
        EditText vol=input("Amount (kosong = full close)","");
        new AlertDialog.Builder(this).setTitle("FXOpen Close / Partial").setView(vol)
            .setNegativeButton("Tutup",null).setPositiveButton("CLOSE",(d,w)->new Thread(()->{
                try{
                    FxOpenTickTraderClient.Response r=fxOpen.close(id,vol.getText().toString().trim());
                    runOnUiThread(()->toast(r.ok()?"FXOpen close request diterima":"Close gagal: "+r.code));
                }catch(Exception e){runOnUiThread(()->toast("FXOpen close: "+e.getMessage()));}
            }).start()).show();
    }

    void showModifyFxOpenTradeDialog(String id){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(12,4,12,4);
        EditText price=input("Modify Entry","");EditText sl=input("Modify SL","");EditText tp=input("Modify TP","");
        box.addView(price);box.addView(sl);box.addView(tp);
        new AlertDialog.Builder(this).setTitle("Modify FXOpen Order "+id).setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE",(d,w)->new Thread(()->{
            try{
                double p=toDouble(price.getText().toString()),ss=toDouble(sl.getText().toString()),tt=toDouble(tp.getText().toString());
                FxOpenTickTraderClient.Response r=fxOpen.modify(id,p,ss,tt,"XAUUSD-V5.2-MODIFY");
                runOnUiThread(()->toast(r.ok()?"FXOpen modify diterima":"Modify gagal: "+r.code));
            }catch(Exception e){runOnUiThread(()->toast("FXOpen modify: "+e.getMessage()));}
        }).start()).show();
    }

    double toDouble(String x){try{return x==null||x.trim().isEmpty()?0:Double.parseDouble(x.trim());}catch(Exception e){return 0;}}

    void showMt5Dialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        EditText token=input("MetaApi auth token",store.get("meta_token","")); token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText aid=input("MetaApi account ID",store.get("meta_account",""));
        EditText host=input("MetaApi trade host",store.get("meta_host",MetaApiClient.DEFAULT_HOST));
        box.addView(tv("MT5 CLOUD CONNECTOR • DEMO TEST\nHP → MetaApi Cloud → akun MT5 demo.",11));
        box.addView(token,new LinearLayout.LayoutParams(-1,55));box.addView(aid,new LinearLayout.LayoutParams(-1,55));box.addView(host,new LinearLayout.LayoutParams(-1,55));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("🔗 MT5 CONNECTOR • MetaApi").setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{store.put("meta_token",token.getText().toString().trim());store.put("meta_account",aid.getText().toString().trim());store.put("meta_host",host.getText().toString().trim());store.put("active_connector","metaapi");d.dismiss();testMetaApi();}));d.show();
    }
    void testMetaApi(){
        if(!metaApi.configured()){toast("Isi MetaApi token + account ID.");return;}
        new Thread(()->{try{MetaApiClient.Response r=metaApi.accountInfo();runOnUiThread(()->{if(r.ok()){log.setText("MT5 CONNECTED • MetaApi account OK\n"+trim(r.body));toast("MT5 CONNECTED • siap test pending order");}else{log.setText("MT5 CONNECT ERROR "+r.code+" • "+trim(r.body));toast("MT5 connector error "+r.code);}});}catch(Exception e){runOnUiThread(()->toast("MT5 connector: "+e.getMessage()));}}).start();
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
        if(marketBusy)return;
        marketBusy=true;
        new Thread(()->{
            try{
                Market.Snapshot s=Market.snapshot(lastPrice, fxOpen);
                final ArrayList<StrategyEngine.Candle> fa=s.m1, fb=s.m5, fc=s.m15;
                final double mm=s.mid, sp=s.spread;
                 final double monitorMid=s.monitorMid, monitorBid=s.monitorBid, monitorAsk=s.monitorAsk, monitorSpread=s.monitorSpread;
                 final String monitorStatus=s.monitorStatus;
                StrategyEngine.Decision dd=(fa.size()>=60&&fb.size()>=30)
                        ?StrategyEngine.analyze(fa,fb,mm):new StrategyEngine.Decision();
                if(!fa.isEmpty()){
                    StrategyEngine.Candle z=fa.get(fa.size()-1);
                    Log.d("XAUUSD_REAL","M1="+fa.size()+" M5="+fb.size()+" M15="+fc.size()
                            +" source=FXOPEN monitor="+monitorStatus+" score="+dd.score+" candidate="+dd.candidateScore+" entry="+dd.entry
                            +" O="+z.open+" H="+z.high+" L="+z.low+" C="+z.close);
                }
                runOnUiThread(()->{
                    long now=System.currentTimeMillis();
                    mid=mm;spread=sp;m1=fa;m5=fb;m15=fc;decision=dd;lastLoad=now;
                    double displayMid=monitorMid>0?monitorMid:mm;
                    price.setText(fmt(displayMid));
                    double prev=lastPrice;lastPrice=displayMid;
                    if(prev>0){
                        priceChange.setText((displayMid>=prev?"+":"")+fmt(displayMid-prev));
                        priceChange.setTextColor(displayMid>=prev?Color.rgb(0,230,118):Color.rgb(255,82,82));
                    }
                    spreadLine.setText(monitorMid>0 ? "BIQ B "+fmt(monitorBid)+"  A "+fmt(monitorAsk)+"  S "+fmt(monitorSpread) : "BIQ OFFLINE  •  FX S "+fmt(sp));
                    ArrayList<StrategyEngine.Candle> hd="M5".equals(chartTf)?fb:"M15".equals(chartTf)?fc:fa;
                    if(!hd.isEmpty()){
                        double hh=-Double.MAX_VALUE,ll=Double.MAX_VALUE;
                        for(StrategyEngine.Candle c:hd){hh=Math.max(hh,c.high);ll=Math.min(ll,c.low);}
                        highLow.setText("H "+fmt(hh)+"   L "+fmt(ll));
                    }
                    String liveTicker=newsTicker+" • BIQUOTE "+monitorStatus+" • ENTRY FXOPEN";
                     if(!liveTicker.equals(lastTickerText)){ lastTickerText=liveTicker; setTickerText(liveTicker); }
                    connection.setText("● MARKET BIQUOTE "+monitorStatus+" • ENTRY FXOPEN "+executionLabel()+" • FX SPREAD "+fmt(sp));
                    renderChart();renderDecision();renderResultsBar();
                     if(now-lastBugScanAt>=5000){ lastBugScanAt=now; final String bug=BugScanner.scan(fa,fb,fc,Market.sourceStatus(),mm,sp,now); if(!bug.isEmpty()) log.setText("AI BUG SCANNER • "+bug); }
                    if(now-lastAccountUiAt>=15000){lastAccountUiAt=now;refreshAccountUi();}
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    connection.setText("● MARKET ERROR • "+trim(e.getMessage()));
                    if(log!=null)log.setText("MARKET FEED ERROR • "+trim(e.getMessage()));
                });
            }finally{marketBusy=false;}
        }).start();
    }

    ArrayList<StrategyEngine.Candle> cloneWithLive(ArrayList<StrategyEngine.Candle> src,double live){
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(StrategyEngine.Candle c:src)out.add(new StrategyEngine.Candle(c.t,c.open,c.high,c.low,c.close));
        if(!out.isEmpty()&&live>0){
            StrategyEngine.Candle c=out.get(out.size()-1);
            c.high=Math.max(c.high,live);c.low=Math.min(c.low,live);c.close=live;
        }
        return out;
    }

    void renderChart(){ if(chart!=null){ ArrayList<StrategyEngine.Candle> d="M5".equals(chartTf)?m5:"M15".equals(chartTf)?m15:m1; chart.setData(d,chartTf,mid,decision,mid-spread/2.0,mid+spread/2.0); } }

    void renderDecision(){
        if(signal==null)return;
        int displayMin=store.rawPrefs().getInt("display_min_score",60);
        boolean finalReady=decision.side.startsWith("BUY")||decision.side.startsWith("SELL");
        boolean watch=!finalReady&&decision.entryWatch&&decision.candidateScore>=displayMin;

        if(finalReady){lastReady=decision;lastReadyAt=System.currentTimeMillis();}

        if(finalReady){
            signal.setText(decision.side+" • ENTRY READY");
            signal.setTextColor(decision.side.startsWith("BUY")?Color.rgb(0,230,118):Color.rgb(255,82,82));
            if(lamp!=null){lamp.setText("●");lamp.setTextColor(decision.side.startsWith("BUY")?Color.rgb(0,230,118):Color.rgb(255,82,82));}
            int stars=Math.max(1,Math.min(5,decision.score/20));
            confidence.setText("Confidence "+stars+"/5");
        }else{
            signal.setText(watch?"ENTRY WATCH • "+decision.candidateSide:"WAIT");
            signal.setTextColor(Color.WHITE);
            if(lamp!=null){lamp.setText("●");lamp.setTextColor(Color.rgb(120,144,156));}
            confidence.setText("Confidence --");
        }

        String cs=decision.candidateSide==null?"WAIT":decision.candidateSide;
        signalDetail.setText((decision.reason==null||decision.reason.isEmpty()
                ?"M5 Bias → Liquidity Sweep → Wick Rejection → BOS"
                :decision.reason));
        signalDetail.setSelected(true);
        signalDetail.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        signalDetail.setSingleLine(true);

        m5Bias.setText((cs.startsWith("BUY")?"M5 BUY":cs.startsWith("SELL")?"M5 SELL":"M5 WAIT")
                +(decision.bias?" • BIAS":"")+(decision.earlyReady?" • EARLY":""));
        m1State.setText(finalReady
                ?(decision.side.startsWith("BUY")?"M1 BUY READY":"M1 SELL READY")
                :(watch?"M1 ENTRY WATCH":"M1 WATCH"));

        if(finalReady){
            boxEntry.setText("ENTRY   ⧉\n"+fmt(decision.entry));
            boxSl.setText("SL   ⧉\n"+fmt(decision.sl));
            boxTp1.setText("TP1   ⧉\n"+fmt(decision.tp1));
            boxTp2.setText("TP2   ⧉\n"+fmt(decision.tp2));
        }else{
            boxEntry.setText("ENTRY   ⧉\n");
            boxSl.setText("SL   ⧉\n");
            boxTp1.setText("TP1   ⧉\n");
            boxTp2.setText("TP2   ⧉\n");
        }

        if(botState!=null){
            boolean on=store.rawPrefs().getBoolean("auto",false);
            botState.setText(on?"AUTO: ON • "+executionLabel():"AUTO: OFF • MANUAL");
        }
        if(account!=null&&!hasCredentials())account.setText("Account: not connected");
        if(finalReady){
            String hk=decision.side+"-"+decision.candleTime;
            if(!hk.equals(lastHistoryKey)){lastHistoryKey=hk;saveSignalHistory(decision);}
        }
    }

    void refreshAccountUi(){
        if(!hasCredentials())return;
        new Thread(()->{
            try{
                if(useFxOpen()){
                    FxOpenTickTraderClient.Response r=fxOpen.accountInfo();
                    if(!r.ok()){
                        runOnUiThread(()->account.setText("FXOpen Account: API "+r.code));
                        return;
                    }
                    String body=r.body==null?"":r.body;
                    String login="";
                    try{
                        JSONObject j=new JSONObject(body);
                        login=j.optString("Login",j.optString("login",j.optString("AccountId",j.optString("accountId",""))));
                        if(login.isEmpty()){
                            JSONObject a=j.optJSONObject("Account");
                            if(a!=null)login=a.optString("Login",a.optString("login",a.optString("Id","")));
                        }
                    }catch(Exception ignored){}
                    final String shown=login.isEmpty()?"EXEC FXOPEN DEMO • CONNECTED":"EXEC FXOPEN DEMO • "+login+" • CONNECTED";
                    runOnUiThread(()->account.setText(shown));
                    return;
                }
                ExnessClient.Response r=exness.snapshot();
                if(!r.ok())return;
                JSONObject j=new JSONObject(r.body);
                JSONObject a=j.optJSONObject("account_state");
                if(a==null)a=j.optJSONObject("accountState");
                double balance=a==null?0:a.optDouble("balance",0);
                double equity=a==null?0:a.optDouble("equity",0);
                double margin=a==null?0:a.optDouble("used_margin",0);
                int orders=countArray(j,"orders","open_orders"), positions=countArray(j,"positions","open_positions");
                final String s=String.format(Locale.US,"B %.2f • E %.2f • M %.2f • P %d • O %d",balance,equity,margin,positions,orders);
                runOnUiThread(()->account.setText(s));
            }catch(Exception e){
                runOnUiThread(()->account.setText("Account connector error"));
            }
        }).start();
    }

    int countArray(JSONObject j,String a,String b){
        JSONArray x=j.optJSONArray(a);if(x==null)x=j.optJSONArray(b);return x==null?0:x.length();
    }


    String orderSymbol(){String s=store.get("symbol","XAUUSD").trim();return s.isEmpty()?"XAUUSD":s;}
    void showCancelAutoDialog(){
        new AlertDialog.Builder(this).setTitle("🛑 Cancel AUTO Pending Orders")
            .setItems(new String[]{"Cancel AUTO BUY LIMIT","Cancel AUTO SELL LIMIT","Cancel ALL AUTO LIMIT"},(d,which)->{
                if(which==0)cancelAutoOrders("buy"); else if(which==1)cancelAutoOrders("sell"); else cancelAutoOrders("all");
            }).setNegativeButton("Tutup",null).show();
    }

    void cancelAutoOrders(String filter){
        if(useFxOpen()){cancelAutoFxOpenOrders(filter);return;}
        if(!hasCredentials()){toast("Sambungkan FXOpen TickTrader atau Exness API dulu.");return;}
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

    void cancelAutoFxOpenOrders(String filter){
        new Thread(()->{
            try{
                FxOpenTickTraderClient.Response tr=fxOpen.trades();
                if(!tr.ok())throw new IllegalStateException("FXOpen Trades "+tr.code+": "+trim(tr.body));
                JSONArray ar=fxOpenTradesArray(tr.body);
                int ok=0,total=0;
                for(int i=0;i<ar.length();i++){
                    JSONObject o=ar.optJSONObject(i);if(o==null)continue;
                    String sym=o.optString("Symbol",o.optString("symbol",""));
                    String side=o.optString("Side",o.optString("side","")).toLowerCase(Locale.US);
                    String type=o.optString("Type",o.optString("type","")).toLowerCase(Locale.US);
                    String comment=o.optString("Comment",o.optString("comment","")).toUpperCase(Locale.US);
                    String id=o.optString("Id",o.optString("id",""));
                    if(id.isEmpty()||!orderSymbol().equalsIgnoreCase(sym)||!"limit".equals(type))continue;
                    if(!comment.contains("AUTO"))continue;
                    if(!"all".equals(filter)&&!filter.equals(side))continue;
                    total++;
                    try{FxOpenTickTraderClient.Response r=fxOpen.cancel(id);if(r.ok())ok++;}catch(Exception ignored){}
                }
                int f=ok,t=total;
                runOnUiThread(()->{log.setText("FXOPEN CANCEL AUTO • "+f+"/"+t+" ACK • "+filter.toUpperCase(Locale.US));toast("FXOpen cancel request: "+f+" order");});
            }catch(Exception e){runOnUiThread(()->toast("FXOpen cancel error: "+e.getMessage()));}
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

    
    void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel("xauusd_alerts","XAUUSD Signal Alerts",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Entry ready, order and risk alerts");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    void notifySignalReady(StrategyEngine.Decision d){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        String title="XAUUSD "+d.side+" • ENTRY READY";
        String msg="Entry "+fmt(d.entry)+" | SL "+fmt(d.sl)+" | TP1 "+fmt(d.tp1)+" | TP2 "+fmt(d.tp2)+" • "+d.score+"/100";
        Notification n=new Notification.Builder(this,"xauusd_alerts").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg+"\\n"+d.reason)).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis()%100000),n);
    }

    void testAlarm(){
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){toast("Izinkan Notification lalu tekan TEST lagi.");return;}
            StrategyEngine.Decision d=new StrategyEngine.Decision();d.side="BUY LIMIT";d.entry=mid>0?mid-0.50:0;d.sl=mid>0?mid-1.00:0;d.tp1=mid>0?mid+0.50:0;d.tp2=mid>0?mid+1.00:0;d.score=80;
            notifySignalReady(d);toast("TEST ALARM dikirim.");
        },300);
    }

    void copyPrice(String label,double value){
        if(value<=0){toast(label+" belum tersedia");return;}
        ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(label,fmt(value)));
        toast(label+" disalin: "+fmt(value));
    }

    void showIndicatorDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        CheckBox ema=new CheckBox(this);ema.setText("EMA 9 / 21 / 50");ema.setTextColor(Color.WHITE);ema.setChecked(store.rawPrefs().getBoolean("ind_ema",true));
        CheckBox sr=new CheckBox(this);sr.setText("Support / Resistance");sr.setTextColor(Color.WHITE);sr.setChecked(store.rawPrefs().getBoolean("ind_sr",true));
        CheckBox fib=new CheckBox(this);fib.setText("Fibonacci 38.2 / 50 / 61.8");fib.setTextColor(Color.WHITE);fib.setChecked(store.rawPrefs().getBoolean("ind_fib",true));
        CheckBox rsi=new CheckBox(this);rsi.setText("RSI filter");rsi.setTextColor(Color.WHITE);rsi.setChecked(store.rawPrefs().getBoolean("ind_rsi",true));
        CheckBox macd=new CheckBox(this);macd.setText("MACD filter");macd.setTextColor(Color.WHITE);macd.setChecked(store.rawPrefs().getBoolean("ind_macd",true));
        CheckBox atr=new CheckBox(this);atr.setText("ATR + spread filter");atr.setTextColor(Color.WHITE);atr.setChecked(store.rawPrefs().getBoolean("ind_atr",true));
        box.addView(ema);box.addView(sr);box.addView(fib);box.addView(rsi);box.addView(macd);box.addView(atr);
        new AlertDialog.Builder(this).setTitle("INDICATORS • CHART + ENGINE").setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE",null).setOnDismissListener(x->{
            store.rawPrefs().edit().putBoolean("ind_ema",ema.isChecked()).putBoolean("ind_sr",sr.isChecked()).putBoolean("ind_fib",fib.isChecked()).putBoolean("ind_rsi",rsi.isChecked()).putBoolean("ind_macd",macd.isChecked()).putBoolean("ind_atr",atr.isChecked()).apply();
            if(chart!=null)chart.setLayers(ema.isChecked(),sr.isChecked(),fib.isChecked());
        }).show();
    }

    void scanMarket(){
        if(m1.size()<60||m5.size()<30){toast("Data market belum cukup untuk scan.");return;}
        StrategyEngine.Decision d=StrategyEngine.analyze(m1,m5,mid);
        String msg="XAUUSD SCAN\\n\\n"+d.side+" • "+d.score+"/100\\nPattern: "+d.pattern+"\\nM5 Bias: "+(d.bias?"CONFIRMED":"WAIT")+
            "\\nWick: "+d.wick+" • Sweep: "+d.sweep+" • BOS: "+d.bos+
            "\\nEMA9/21/50: "+fmt(d.ema9)+" / "+fmt(d.ema21)+" / "+fmt(d.ema50)+
            "\\nRSI: "+fmt(d.rsi)+" • ATR: "+fmt(d.atr)+"\\nM5 candle close in: "+d.secondsToClose+"s\\n\\n"+d.reason;
        new AlertDialog.Builder(this).setTitle("🔎 MARKET SCANNER").setMessage(msg).setPositiveButton("OK",null).show();
    }

    void saveSignalHistory(StrategyEngine.Decision d){
        try{
            String old=store.get("signal_history","");
            String line=d.side+" | Entry "+fmt(d.entry)+" | SL "+fmt(d.sl)+" | TP1 "+fmt(d.tp1)+" | TP2 "+fmt(d.tp2)+" | WAITING";
            String out=line+"\n"+old;
            String[] rows=out.split("\\n");StringBuilder b=new StringBuilder();
            for(int i=0;i<Math.min(50,rows.length);i++){if(rows[i].trim().length()>0)b.append(rows[i]).append("\\n");}
            store.put("signal_history",b.toString());
        }catch(Exception ignored){}
    }

    void updateHistoryResults(double live){
        if(live<=0)return;
        try{
            String old=store.get("signal_history","");
            if(old.trim().isEmpty())return;
            String[] rows=old.split("\\n");StringBuilder out=new StringBuilder();boolean changed=false;
            for(String row:rows){
                if(row.trim().isEmpty()){continue;}
                String r=row.trim();
                if(r.endsWith("WIN")||r.endsWith("LOSS")){out.append(r).append("\\n");continue;}
                String[] p=r.split("\\|");
                if(p.length<6){out.append(r).append("\\n");continue;}
                String side=p[0].trim().toUpperCase(Locale.US);
                double entry=numField(p[1],"Entry"),sl=numField(p[2],"SL"),tp1=numField(p[3],"TP1");
                String state=p[5].trim().toUpperCase(Locale.US);
                if(entry<=0||sl<=0||tp1<=0){out.append(r).append("\\n");continue;}
                if("WAITING".equals(state)){
                    boolean filled=side.startsWith("BUY")?live<=entry:live>=entry;
                    if(filled){r=r.substring(0,r.lastIndexOf("|")+1)+" ACTIVE";changed=true;}
                }else if("ACTIVE".equals(state)){
                    String result=null;
                    if(side.startsWith("BUY")){
                        if(live<=sl)result="LOSS"; else if(live>=tp1)result="WIN";
                    }else if(side.startsWith("SELL")){
                        if(live>=sl)result="LOSS"; else if(live<=tp1)result="WIN";
                    }
                    if(result!=null){r=r.substring(0,r.lastIndexOf("|")+1)+" "+result;changed=true;}
                }
                out.append(r).append("\\n");
            }
            if(changed)store.put("signal_history",out.toString());
        }catch(Exception ignored){}
    }

    double numField(String part,String key){
        try{
            int i=part.indexOf(key);if(i<0)return 0;
            String s=part.substring(i+key.length()).trim().replace(",","");
            return Double.parseDouble(s);
        }catch(Exception e){return 0;}
    }

    void renderResultsBar(){
        try{
            String h=store.get("signal_history","");
            int win=0,loss=0;
            for(String r:h.split("\\n")){String s=r.trim();if(s.endsWith("WIN"))win++;else if(s.endsWith("LOSS"))loss++;}
            int total=win+loss;double wr=total==0?0:(100.0*win/total);
            resultsBar.setText(String.format(Locale.US,"RESULT • Entries %d • WIN %d • LOSS %d • WR %.0f%%",total,win,loss,wr));
        }catch(Exception ignored){resultsBar.setText("RESULT • WIN 0 • LOSS 0 • WR 0%");}
    }

    void showHistoryDialog(){
        String h=store.get("signal_history","Belum ada ENTRY READY.");
        if(!h.equals("Belum ada ENTRY READY.")){
            StringBuilder clean=new StringBuilder();
            for(String r:h.split("\\n")){
                if(r.trim().isEmpty())continue;
                String s=r.replace("WAITING","--").replace("ACTIVE","--");
                clean.append(s).append("\\n");
            }
            h=clean.toString().trim();
        }
        new AlertDialog.Builder(this).setTitle("📋 SIGNAL HISTORY").setMessage(h).setPositiveButton("OK",null).show();
    }

    Runnable newsPoll=new Runnable(){@Override public void run(){loadNewsTicker();handler.postDelayed(this,60000);}};

    void setTickerText(String text){
        if(ticker==null||text==null)return;
        if(text.equals(tickerRendered))return;
        tickerRendered=text;
        if(tickerAnimator!=null){tickerAnimator.cancel();tickerAnimator=null;}
        ticker.setTranslationX(0f);
        ticker.setText(text+"     •     "+text);
        ticker.setSingleLine(true);
        ticker.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        ticker.setMarqueeRepeatLimit(-1);
        ticker.setHorizontallyScrolling(true);
        ticker.setSelected(true);
        ticker.requestFocus();
    }

    void loadNewsTicker(){
        new Thread(()->{
            String session=marketSession();
            String news="NEWS: "+session+" • XAUUSD • Biquote "+(Market.monitorStatus().startsWith("BIQUOTE REAL")?"OK":"OFFLINE");
            try{
                String body=Market.publicGet("https://biquote.io/api/calendar/upcoming?countries=US&importance=high&limit=10");
                JSONArray ar=new JSONArray(body);long now=System.currentTimeMillis();long best=Long.MAX_VALUE;String bestTitle="";
                for(int i=0;i<ar.length();i++){
                    JSONObject o=ar.optJSONObject(i);if(o==null)continue;
                    String cur=o.optString("countryCode",o.optString("currency",""));String title=o.optString("name",o.optString("title",""));
                    String impact=o.optString("importance",o.optString("impact",""));String iso=o.optString("time","");
                    if(!"USD".equalsIgnoreCase(cur)&&!"US".equalsIgnoreCase(cur))continue;
                    if(!impact.toLowerCase(Locale.US).contains("high"))continue;
                    long ts=parseIsoTime(iso);if(ts>=now&&ts<best){best=ts;bestTitle=title;}
                }
                if(!bestTitle.isEmpty())news+=" • HIGH USD: "+bestTitle+" in "+Math.max(0,(best-now)/60000)+"m";
            }catch(Exception ignored){news+=" • Calendar feed unavailable";}
            final String out=news;runOnUiThread(()->{newsTicker=out;
                String liveTicker=newsTicker+" • BIQUOTE "+Market.monitorStatus()+" • ENTRY FXOPEN "+executionLabel();
                if(!liveTicker.equals(lastTickerText)){lastTickerText=liveTicker;setTickerText(liveTicker);}});
        }).start();
    }

    long parseIsoTime(String iso){try{return java.time.Instant.parse(iso).toEpochMilli();}catch(Exception e){return Long.MAX_VALUE;}}

    long parseNewsTime(String date,String time){
        try{
            String s=date+" "+time;
            java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd hh:mm a",Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        }catch(Exception e){return Long.MAX_VALUE;}
    }

    String marketSession(){
        java.util.Calendar c=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        int h=c.get(java.util.Calendar.HOUR_OF_DAY);
        if(h>=0&&h<7)return "ASIA";
        if(h>=7&&h<13)return "LONDON";
        if(h>=13&&h<21)return "NEW YORK";
        return "ASIA PREOPEN";
    }

    boolean isNetworkOk(){
        try{
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n=cm.getActiveNetwork();
            return n!=null;
        }catch(Exception e){return false;}
    }

    ArrayList<StrategyEngine.Candle> parse(String s)throws Exception{
        String z=s==null?"":s.trim();
        JSONArray ar=null;
        if(z.startsWith("[")) ar=new JSONArray(z);
        else {
            JSONObject env=new JSONObject(z);
            ar=env.optJSONArray("bars");
            if(ar==null)ar=env.optJSONArray("data");
            if(ar==null){
                JSONObject result=env.optJSONObject("result");
                if(result!=null)ar=result.optJSONArray("bars");
            }
        }
        if(ar==null)throw new JSONException("OHLC bars missing");
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(int i=ar.length()-1;i>=0;i--){
            JSONObject o=ar.getJSONObject(i);
            long t=o.optLong("openTime",o.optLong("time",0));
            if(t==0)t=o.optLong("timestamp",0);
            if(t==0){
                String iso=o.optString("openTime",o.optString("timestamp",""));
                if(!iso.isEmpty())try{t=java.time.Instant.parse(iso).toEpochMilli();}catch(Exception ignored){}
            }
            if(t>0&&t<100000000000L)t*=1000L;
            double open=o.optDouble("open",Double.NaN), high=o.optDouble("high",Double.NaN);
            double low=o.optDouble("low",Double.NaN), close=o.optDouble("close",Double.NaN);
            if(Double.isNaN(open)||Double.isNaN(high)||Double.isNaN(low)||Double.isNaN(close))continue;
            out.add(new StrategyEngine.Candle(t,open,high,low,close));
        }
        return out;
    }

    String get(String url) throws Exception{
        return Market.publicGet(url);
    }

    String fmt(double d){return String.format(Locale.US,"%.2f",d);}
    String trim(String s){return s==null?"":s.length()>800?s.substring(0,800)+"…":s;}
    void toast(String s){Toast.makeText(this,s==null?"":s,Toast.LENGTH_LONG).show();}

}
