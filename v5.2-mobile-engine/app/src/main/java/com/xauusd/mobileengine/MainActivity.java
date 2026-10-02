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
    long lastReadyAt = 0, lastAlertAt = 0, lastAccountUiAt = 0;
    String lastHistoryKey = "";
    String newsTicker = "NEWS: loading...";
    StrategyEngine.Decision decision = new StrategyEngine.Decision();
    double mid=0, spread=0;
    String chartTf="M1";
    double lastPrice=0;
    long lastLoad=0, lastBarsLoad=0;
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
        buildUi();
        requestNotificationPermission();
        createNotificationChannel();
        loadMarket();
        loadNewsTicker();
        handler.postDelayed(marketPoll,2500);
        handler.postDelayed(newsPoll,60000);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
        findViewById(R.id.btnExness).setOnClickListener(v->showExnessDialog());
        findViewById(R.id.btnManual).setOnClickListener(v->showManualOrderDialog());
        findViewById(R.id.btnCancelAuto).setOnClickListener(v->showCancelAutoDialog());
        findViewById(R.id.btnLive).setOnClickListener(v->{chart.resetView();});
        findViewById(R.id.btnCopyEntry).setOnClickListener(v->copyPrice("ENTRY",decision.entry));
        findViewById(R.id.btnCopySl).setOnClickListener(v->copyPrice("SL",decision.sl));
        findViewById(R.id.btnCopyTp).setOnClickListener(v->copyPrice("TP1",decision.tp1));
        boxEntry.setOnClickListener(v->copyPrice("ENTRY",decision.entry));
        boxSl.setOnClickListener(v->copyPrice("SL",decision.sl));
        boxTp1.setOnClickListener(v->copyPrice("TP1",decision.tp1));
        boxTp2.setOnClickListener(v->copyPrice("TP2",decision.tp2));
        renderDecision();
    }

    void showProfessionalMenu(){
        String auto=store.rawPrefs().getBoolean("auto",false)?"ON":"OFF";
        String[] items={
            "🔐 Exness API / Instrument",
            "🎯 Manual BUY/SELL LIMIT",
            "📊 Indicators / Fibonacci",
            "🔎 Scan Market • Entry Ready",
            "🛡 Position Manager",
            "💰 Risk & Strategy",
            "📋 Orders / Account",
            "📋 Signal History",
            "🔗 MT5 DIRECT • HP ONLY",
            "📲 Telegram",
            "🛑 Cancel Auto Pending",
            "🔔 Notification",
            "📜 Log / Status",
            "AUTO ENGINE: "+auto
        };
        new AlertDialog.Builder(this).setTitle("XAUUSD ENGINE • CONTROL").setItems(items,(d,w)->{
            switch(w){
                case 0: showExnessDialog();break;
                case 1: showManualOrderDialog();break;
                case 2: showIndicatorDialog();break;
                case 3: scanMarket();break;
                case 4: showManagerDialog();break;
                case 5: showRiskDialog();break;
                case 6: showOrderManagerDialog();break;
                case 7: showHistoryDialog();break;
                case 8: showMt5Dialog();break;
                case 9: showTelegramDialog();break;
                case 10: showCancelAutoDialog();break;
                case 11: requestNotificationPermission();testAlarm();break;
                case 12: new AlertDialog.Builder(this).setTitle("ENGINE LOG").setMessage(log.getText()).setPositiveButton("OK",null).show();break;
                case 13: if(store.rawPrefs().getBoolean("auto",false))stopAuto(); else startAuto();break;
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
        EditText host=input("API Host (kosong = default resmi)",c.host);host.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText symbol=input("Instrument (MT5)",store.get("symbol","XAUUSD"));symbol.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        CheckBox direct=new CheckBox(this);direct.setText("Direct Exness API (eligible accounts only)");direct.setTextColor(Color.WHITE);direct.setChecked(true);
        box.addView(id,new LinearLayout.LayoutParams(-1,55));box.addView(key,new LinearLayout.LayoutParams(-1,55));box.addView(secret,new LinearLayout.LayoutParams(-1,55));box.addView(host,new LinearLayout.LayoutParams(-1,55));box.addView(symbol,new LinearLayout.LayoutParams(-1,55));box.addView(direct);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Exness API Connection").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String hostValue=host.getText().toString().trim();
            if(hostValue.isEmpty()) hostValue=ExnessClient.DEFAULT_HOST;
            exness.saveCredentials(id.getText().toString(),key.getText().toString(),secret.getText().toString(),hostValue);
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
        log.setText("AUTO PREFLIGHT • testing Exness API + instrument...");
        new Thread(()->{
            try{
                ExnessClient.Response a=exness.connectAndResolve();
                if(!a.ok()) throw new IllegalStateException("API connection "+a.code+": "+trim(a.body));
                ExnessClient.Response i=exness.instrumentConditions(orderSymbol());
                if(!i.ok()) throw new IllegalStateException("Instrument "+orderSymbol()+" "+i.code+": "+trim(i.body));
                store.rawPrefs().edit().putBoolean("auto",true).apply();
                runOnUiThread(()->{
                    botState.setText("AUTO: ON  •  guarded execution");
                    botState.setTextColor(Color.rgb(0,230,118));
                    Intent intent=new Intent(this,TradingService.class);
                    if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
                    log.setText("AUTO ON • API + "+orderSymbol()+" preflight OK");
                });
            }catch(Exception e){runOnUiThread(()->{store.rawPrefs().edit().putBoolean("auto",false).apply();botState.setText("AUTO: OFF • API ERROR");log.setText("AUTO PREFLIGHT ERROR • "+e.getMessage());toast("Auto gagal: "+e.getMessage());});}
        }).start();
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

    void showOrderManagerDialog(){
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
        EditText vol=input("Volume (kosong = full close)","");
        vol.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new AlertDialog.Builder(this).setTitle("Close / Partial Close").setView(vol).setNegativeButton("Tutup",null).setPositiveButton("CLOSE", (d,w)->{
            String v=vol.getText().toString().trim();
            new Thread(()->{try{ExnessClient.Response r=exness.closePosition(id,v);runOnUiThread(()->{log.setText(r.ok()?"CLOSE REQUEST • "+id:"CLOSE FAILED • "+r.code);toast(r.ok()?"Close request diterima":"Close gagal: "+trim(r.body));});}catch(Exception e){runOnUiThread(()->toast("Close error: "+e.getMessage()));}}).start();
        }).show();
    }

    void showModifyOrderDialog(String id){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(12,4,12,4);
        EditText price=input("Modify Entry","");EditText sl=input("Modify SL","");EditText tp=input("Modify TP","");
        box.addView(price);box.addView(sl);box.addView(tp);
        new AlertDialog.Builder(this).setTitle("Modify Order "+id).setView(box).setNegativeButton("Tutup",null).setPositiveButton("SAVE",(d,w)->{
            new Thread(()->{try{ExnessClient.Response r=exness.modifyOrder(id,price.getText().toString(),sl.getText().toString(),tp.getText().toString());runOnUiThread(()->{log.setText(r.ok()?"MODIFY REQUEST • "+id:"MODIFY FAILED • "+r.code);toast(r.ok()?"Modify request diterima":"Modify gagal: "+trim(r.body));});}catch(Exception e){runOnUiThread(()->toast("Modify error: "+e.getMessage()));}}).start();
        }).show();
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
        if(marketBusy || System.currentTimeMillis()<feedBackoffUntil)return;
        marketBusy=true;
        new Thread(()->{
            try{
                long now=System.currentTimeMillis();
                JSONObject t=new JSONObject(get(BASE+"?allowStale=true"));
                double mm=t.optDouble("mid",0),sp=t.optDouble("spread",0);
                if(mm<=0)throw new IOException("Biquote returned no mid price");

                ArrayList<StrategyEngine.Candle> a,b,c15;
                if(m1.size()<60 || now-lastBarsLoad>=10000){
                    try{
                        a=parse(get(BASE+"/ohlc?interval=1m&limit=100"));
                        b=parse(get(BASE+"/ohlc?interval=5m&limit=60"));
                        c15=parse(get(BASE+"/ohlc?interval=15m&limit=60"));
                        if(a.size()>=60&&b.size()>=30&&c15.size()>=20){
                            m1=a;m5=b;m15=c15;lastBarsLoad=now;
                        }else throw new IOException("OHLC data incomplete");
                    }catch(Exception barsError){
                        if(m1.size()<60||m5.size()<30||m15.size()<20)throw barsError;
                        a=new ArrayList<>(m1);b=new ArrayList<>(m5);c15=new ArrayList<>(m15);
                    }
                }else{
                    a=cloneWithLive(m1,mm);
                    b=new ArrayList<>(m5);
                    c15=new ArrayList<>(m15);
                }
                if(a.size()<60 || b.size()<30 || c15.size()<20)throw new IOException("Waiting for candle history");

                StrategyEngine.Decision dd=StrategyEngine.analyze(a,b,mm);
                final boolean stale=t.optBoolean("stale",false);
                final String state=t.optString("marketState","open");
                updateHistoryResults(mm);
                final ArrayList<StrategyEngine.Candle> fa=a, fb=b, fc15=c15;
                runOnUiThread(()->{
                    mid=mm;spread=sp;m1=fa;m5=fb;m15=fc15;decision=dd;lastLoad=now;
                    price.setText(fmt(mid));
                    double prev=lastPrice; lastPrice=mid;
                    if(prev>0){
                        priceChange.setText((mid>=prev?"+":"")+fmt(mid-prev));
                        priceChange.setTextColor(mid>=prev?Color.rgb(0,230,118):Color.rgb(255,82,82));
                    }
                    spreadLine.setText("Spread "+fmt(spread));
                    ArrayList<StrategyEngine.Candle> hd="M5".equals(chartTf)?fb:"M15".equals(chartTf)?fc15:fa;
                    if(!hd.isEmpty()) highLow.setText("H "+fmt(hd.stream().mapToDouble(x->x.high).max().orElse(mm))+"   L "+fmt(hd.stream().mapToDouble(x->x.low).min().orElse(mm)));
                    ticker.setText("BIQUOTE • XAUUSD • LIVE 1s");
                    connection.setText("● BIQUOTE 1s • "+("closed".equalsIgnoreCase(state)?"MARKET CLOSED":stale?"STALE":"LIVE")+" • Spread "+fmt(spread));
                    renderChart();
                    renderDecision();
                    renderResultsBar();
                    if(now-lastAccountUiAt>=15000){lastAccountUiAt=now;refreshAccountUi();}
                });
                feedBackoffUntil=0;
            }catch(Exception e){
                feedBackoffUntil=System.currentTimeMillis()+2000;
                runOnUiThread(()->connection.setText("● BIQUOTE FEED ERROR • retry 2s"));
            }finally{
                marketBusy=false;
            }
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
        for(int i=ar.length()-1;i>=0;i--){
            JSONObject o=ar.getJSONObject(i);
            String raw=o.optString("openTime","");
            long tm=0;
            try{tm=java.time.Instant.parse(raw).toEpochMilli();}catch(Exception ignored){tm=o.optLong("openTime",0);}
            out.add(new StrategyEngine.Candle(tm,o.getDouble("open"),o.getDouble("high"),o.getDouble("low"),o.getDouble("close")));
        }
        return out;
    }

    void renderChart(){ if(chart!=null){ ArrayList<StrategyEngine.Candle> d="M5".equals(chartTf)?m5:"M15".equals(chartTf)?m15:m1; chart.setData(d,chartTf,mid,decision,mid-spread/2.0,mid+spread/2.0); } }

    void renderDecision(){
        if(signal==null)return;
        boolean buy=decision.side.startsWith("BUY"),sell=decision.side.startsWith("SELL");
        boolean ready=buy||sell;
        if(ready){ lastReady=decision; lastReadyAt=System.currentTimeMillis(); }
        StrategyEngine.Decision shown=decision;
        if(!ready && (lastReady.side.startsWith("BUY")||lastReady.side.startsWith("SELL"))
                && System.currentTimeMillis()-lastReadyAt<900000) shown=lastReady;
        signal.setText(ready
                ? decision.side+" • "+(decision.score>=85?"ENTRY READY":"LIMIT ZONE")
                : shown.side.startsWith("BUY")||shown.side.startsWith("SELL")
                    ? "LAST READY • "+shown.side+" • "+shown.score+"/100"
                    : "WAIT • SCORE "+decision.score+"/100");
        signal.setTextColor((shown.side.startsWith("BUY"))?Color.rgb(0,230,118):shown.side.startsWith("SELL")?Color.rgb(255,82,82):Color.WHITE);
        if(lamp!=null){lamp.setText("●");lamp.setTextColor((shown.side.startsWith("BUY"))?Color.rgb(0,230,118):shown.side.startsWith("SELL")?Color.rgb(255,82,82):Color.rgb(120,144,156));}
        String why=(shown.reason==null||shown.reason.isEmpty()?"M5 Bias → Liquidity Sweep → Wick Rejection → BOS":shown.reason)
                +" • Pattern: "+(shown.pattern==null?"--":shown.pattern);
        signalDetail.setText(newsTicker); signalDetail.setSelected(true); signalDetail.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); signalDetail.setSingleLine(true);
        int stars=Math.max(0,Math.min(5,shown.score/20));
        confidence.setText("★★★★★".substring(0,stars)+"☆☆☆☆☆".substring(0,5-stars)+"  "+shown.score+"/100");
        m5Bias.setText("M5 "+(shown.bias?"BIAS CONFIRMED":"WAIT")+(shown.earlyReady?" • EARLY":""));
        m1State.setText("M1 "+(shown.side.equals("WAIT")?"WATCH":shown.side));
        boxEntry.setText("ENTRY   ⧉\n"+fmt(shown.entry)); boxSl.setText("SL   ⧉\n"+fmt(shown.sl));
        boxTp1.setText("TP1   ⧉\n"+fmt(shown.tp1)); boxTp2.setText("TP2   ⧉\n"+fmt(shown.tp2));
        if(botState!=null)botState.setText(store.rawPrefs().getBoolean("auto",false)?"AUTO: ON • GUARDED":"AUTO: OFF • MANUAL");
        if(account!=null && !hasCredentials())account.setText("Account: not connected");
        if(ready){String hk=decision.side+"-"+decision.candleTime;if(!hk.equals(lastHistoryKey)){lastHistoryKey=hk;saveSignalHistory(decision);}}
        if(chart!=null)renderChart();
    }

    void refreshAccountUi(){
        if(!hasCredentials())return;
        new Thread(()->{
            try{
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
            }catch(Exception ignored){}
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

    void loadNewsTicker(){
        new Thread(()->{
            String session=marketSession();
            String news="NEWS: "+session+" • XAUUSD • Network "+(isNetworkOk()?"OK":"OFFLINE");
            try{
                String body=get("https://biquote.io/api/calendar/upcoming?countries=US&importance=high&limit=10");
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
            final String out=news;runOnUiThread(()->{newsTicker=out;if(signalDetail!=null){signalDetail.setText(newsTicker);signalDetail.setSelected(true);signalDetail.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);signalDetail.setSingleLine(true);}});
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

    String fmt(double d){return String.format(Locale.US,"%.2f",d);}
    String trim(String s){return s==null?"":s.length()>800?s.substring(0,800)+"…":s;}
    void toast(String s){Toast.makeText(this,s==null?"":s,Toast.LENGTH_LONG).show();}

}