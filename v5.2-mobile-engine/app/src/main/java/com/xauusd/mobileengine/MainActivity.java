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
    TextView price, connection, signal, metrics, account, botState, log;
    ChartView chart;
    SecurityStore store;
    ExnessClient exness;
    Handler handler = new Handler(Looper.getMainLooper());
    ArrayList<StrategyEngine.Candle> m1 = new ArrayList<>(), m5 = new ArrayList<>();
    StrategyEngine.Decision decision = new StrategyEngine.Decision();
    double mid=0, spread=0;
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
        loadMarket();
        handler.postDelayed(marketPoll,2500);
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
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(10,10,10,6);root.setBackgroundColor(Color.rgb(7,10,15));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=tv("XAUUSD  •  MOBILE TRADING ENGINE",17);title.setTypeface(null,1);
        price=tv("--.--",25);price.setGravity(Gravity.RIGHT);price.setTypeface(null,1);
        head.addView(title,new LinearLayout.LayoutParams(0,60,1));head.addView(price,new LinearLayout.LayoutParams(0,60,1));root.addView(head);
        connection=tv("● NOT CONNECTED",12);connection.setTextColor(Color.LTGRAY);root.addView(connection);

        ScrollView sv=new ScrollView(this);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);sv.addView(content);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));

        chart=new ChartView(); addCard(chart,330);
        LinearLayout chartTools=new LinearLayout(this);
        chartTools.setGravity(Gravity.CENTER);
        String[] chartBtns={"LIVE","X+","X-","Y+","Y-"};
        for(String s:chartBtns){
            Button cb=btn(s);
            chartTools.addView(cb,new LinearLayout.LayoutParams(0,48,1));
            if("LIVE".equals(s)) cb.setOnClickListener(v->chart.resetView());
            else if("X+".equals(s)) cb.setOnClickListener(v->chart.zoomX(1.18f));
            else if("X-".equals(s)) cb.setOnClickListener(v->chart.zoomX(0.85f));
            else if("Y+".equals(s)) cb.setOnClickListener(v->chart.zoomY(1.18f));
            else cb.setOnClickListener(v->chart.zoomY(0.85f));
        }
        content.addView(chartTools,new LinearLayout.LayoutParams(-1,50));
        signal=tv("WAITING FOR SETUP",19);signal.setGravity(Gravity.CENTER);signal.setTypeface(null,1);addCard(signal,64);
        metrics=tv("M5 Bias: --\nWick: --  Sweep: --  BOS: --\nEMA9/21/50: -- / -- / --\nRSI: --  ATR: --  MACD: --",12);addCard(metrics,110);

        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,4,0,4);
        Button connect=btn("🔐 Exness");
        Button manual=btn("🎯 Manual Limit");
        Button start=btn("▶ Start Auto");
        Button stop=btn("■ Stop Auto");
        actions.addView(connect,new LinearLayout.LayoutParams(0,56,1));
        actions.addView(manual,new LinearLayout.LayoutParams(0,56,1));
        actions.addView(start,new LinearLayout.LayoutParams(0,56,1));
        actions.addView(stop,new LinearLayout.LayoutParams(0,56,1));
        content.addView(actions);

        Button manager=btn("🛡 Position Manager");
        Button risk=btn("💰 Risk & Strategy");
        Button orders=btn("📋 Orders / Account");
        Button mt5=btn("🔗 MT5 DIRECT • HP ONLY");
        Button telegram=btn("📲 Telegram");
        Button cancelAuto=btn("🛑 Cancel Auto BUY / SELL");
        content.addView(manager,new LinearLayout.LayoutParams(-1,56));
        content.addView(risk,new LinearLayout.LayoutParams(-1,56));
        content.addView(orders,new LinearLayout.LayoutParams(-1,56));
        content.addView(mt5,new LinearLayout.LayoutParams(-1,56));
        content.addView(telegram,new LinearLayout.LayoutParams(-1,56));
        cancelAuto.setBackground(bg("#28151A",16));
        content.addView(cancelAuto,new LinearLayout.LayoutParams(-1,58));

        botState=tv("AUTO: OFF",13);botState.setGravity(Gravity.CENTER);botState.setBackground(bg("#151B23",15));content.addView(botState,new LinearLayout.LayoutParams(-1,48));
        account=tv("Account: --",12);content.addView(account);
        log=tv("Ready. Signal engine active; automatic execution is OFF until explicitly started.",11);log.setTextColor(Color.GRAY);content.addView(log);

        connect.setOnClickListener(v->showExnessDialog());
        manual.setOnClickListener(v->showManualOrderDialog());
        start.setOnClickListener(v->startAuto());
        stop.setOnClickListener(v->stopAuto());
        manager.setOnClickListener(v->showManagerDialog());
        risk.setOnClickListener(v->showRiskDialog());
        orders.setOnClickListener(v->showAccountDialog());
        mt5.setOnClickListener(v->showMt5Dialog());
        telegram.setOnClickListener(v->showTelegramDialog());
        cancelAuto.setOnClickListener(v->showCancelAutoDialog());
        setContentView(root);
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
                    lastBarsLoad=now;
                }else{
                    a=cloneWithLive(m1,mm);
                    b=new ArrayList<>(m5);
                }
                if(a.size()<60 || b.size()<30 || mm<=0)return;
                StrategyEngine.Decision dd=StrategyEngine.analyze(a,b,mm);
                runOnUiThread(()->{
                    mid=mm;spread=sp;m1=a;m5=b;decision=dd;lastLoad=now;
                    price.setText(fmt(mid));
                    connection.setText("● 1s MARKET FEED  •  "+(t.optBoolean("stale",false)?"STALE":"LIVE")+"  • Spread "+fmt(spread));
                    renderDecision();chart.invalidate();
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

    void renderDecision(){
        signal.setText(decision.side.equals("WAIT")?"WAIT  •  SCORE "+decision.score+"/100":decision.side+"  •  "+decision.score+"/100\n"+decision.summary());
        signal.setTextColor(decision.side.startsWith("BUY")?Color.rgb(0,230,118):decision.side.startsWith("SELL")?Color.rgb(255,82,82):Color.WHITE);
        metrics.setText("M5 Bias: "+(decision.side.startsWith("BUY")?"BUY":decision.side.startsWith("SELL")?"SELL":"NEUTRAL")+"\nWick: "+(decision.wick?"YES":"--")+"  Sweep: "+(decision.sweep?"YES":"--")+"  BOS: "+(decision.bos?"YES":"--")+"\nEMA9/21/50: "+fmt(decision.ema9)+" / "+fmt(decision.ema21)+" / "+fmt(decision.ema50)+"\nRSI: "+fmt(decision.rsi)+"  ATR: "+fmt(decision.atr)+"  MACD: "+fmt(decision.macd));
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
        float xZoom=1f,yZoom=1f,xPan=0f,yPan=0f,lastX,lastY;
        boolean liveMode=true;
        final float MIN_X=0.65f,MAX_X=5f,MIN_Y=0.75f,MAX_Y=4f;

        ChartView(){
            super(MainActivity.this);
            scaleDetector=new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){
                    float s=d.getScaleFactor();zoomX(s);zoomY(s);liveMode=false;return true;
                }
            });
            setClickable(true);
        }

        void resetView(){xZoom=1f;yZoom=1f;xPan=0f;yPan=0f;liveMode=true;invalidate();}
        void zoomX(float f){xZoom=Math.max(MIN_X,Math.min(MAX_X,xZoom*f));invalidate();}
        void zoomY(float f){yZoom=Math.max(MIN_Y,Math.min(MAX_Y,yZoom*f));invalidate();}

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            scaleDetector.onTouchEvent(e);
            if(e.getPointerCount()>1)return true;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();return true;
                case MotionEvent.ACTION_MOVE:
                    if(!scaleDetector.isInProgress()){
                        float dx=e.getX()-lastX,dy=e.getY()-lastY;
                        xPan+=dx;yPan+=dy;liveMode=false;lastX=e.getX();lastY=e.getY();
                        invalidate();
                    }return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:return true;
            }
            return true;
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.drawColor(Color.rgb(8,12,18));if(m1.size()<2)return;
            final int left=30,right=getWidth()-48,top=24,bottom=getHeight()-34;
            final float midY=(top+bottom)/2f;
            final float baseW=Math.max(5f,(right-left)/65f),cw=Math.max(3f,baseW*xZoom);

            double hi=-1e99,lo=1e99;
            for(int k=0;k<m1.size();k++){
                float x=right-(m1.size()-1-k)*cw+xPan;
                if(x>left-cw && x<right+cw){hi=Math.max(hi,m1.get(k).high);lo=Math.min(lo,m1.get(k).low);}
            }
            if(!(hi>lo)){hi=mid+1;lo=mid-1;}
            double pad=(hi-lo)*0.10;hi+=pad;lo-=pad;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(Color.rgb(35,42,52));
            for(int g=0;g<=6;g++){float y=top+(bottom-top)*g/6f;c.drawLine(left,y,right,y,p);}
            for(int g=0;g<=8;g++){float x=left+(right-left)*g/8f;c.drawLine(x,top,x,bottom,p);}
            p.setStyle(Paint.Style.FILL);

            p.setTextSize(13);p.setColor(Color.LTGRAY);c.drawText("M1 • 1s LIVE",left,16,p);
            p.setTextSize(11);
            for(int g=0;g<=6;g++){double v=hi-(hi-lo)*g/6.0;float y=top+(bottom-top)*g/6f;c.drawText(fmt(v),right+4,y+4,p);}

            for(int k=0;k<m1.size();k++){
                StrategyEngine.Candle x=m1.get(k);
                float cx=right-(m1.size()-1-k)*cw+xPan;
                if(cx<left-cw || cx>right+cw)continue;
                float yH=mapY(x.high,lo,hi,top,bottom),yL=mapY(x.low,lo,hi,top,bottom);
                float yO=mapY(x.open,lo,hi,top,bottom),yC=mapY(x.close,lo,hi,top,bottom);
                int col=x.close>=x.open?Color.rgb(0,210,105):Color.rgb(255,75,75);
                p.setColor(col);p.setStrokeWidth(Math.max(1.5f,xZoom*1.5f));c.drawLine(cx,yH,cx,yL,p);
                p.setStyle(Paint.Style.FILL);
                float bw=Math.max(2.5f,cw*.58f);
                c.drawRect(cx-bw/2,Math.min(yO,yC),cx+bw/2,Math.max(yO,yC)+1,p);
                if(k==m1.size()-1){p.setColor(Color.WHITE);c.drawCircle(cx,yC,4f,p);}
            }

            double srHigh=StrategyEngine.resistance(m1,45,m1.size()-1),srLow=StrategyEngine.support(m1,45,m1.size()-1);
            drawLevel(c,srHigh,lo,hi,top,bottom,Color.MAGENTA,"R");
            drawLevel(c,srLow,lo,hi,top,bottom,Color.GREEN,"S");
            double range=srHigh-srLow;
            if(range>0){
                drawLevel(c,srHigh-range*.382,lo,hi,top,bottom,Color.LTGRAY,"F38");
                drawLevel(c,srHigh-range*.500,lo,hi,top,bottom,Color.LTGRAY,"F50");
                drawLevel(c,srHigh-range*.618,lo,hi,top,bottom,Color.LTGRAY,"F62");
            }

            if(decision.entry>0)drawLevel(c,decision.entry,lo,hi,top,bottom,Color.YELLOW,"ENTRY");
            if(decision.sl>0)drawLevel(c,decision.sl,lo,hi,top,bottom,Color.RED,"SL");
            if(decision.tp1>0)drawLevel(c,decision.tp1,lo,hi,top,bottom,Color.rgb(0,180,255),"TP1");
            if(decision.tp2>0)drawLevel(c,decision.tp2,lo,hi,top,bottom,Color.CYAN,"TP2");
            if(mid>0)drawLevel(c,mid,lo,hi,top,bottom,Color.WHITE,"LIVE");

            if(!"WAIT".equals(decision.side)){
                float mx=right-18;
                float my=decision.side.startsWith("BUY")?top+42:bottom-22;
                p.setColor(decision.side.startsWith("BUY")?Color.rgb(0,230,118):Color.rgb(255,82,82));
                c.drawCircle(mx,my,11,p);
                p.setColor(Color.BLACK);p.setTextSize(10);p.setTextAlign(Paint.Align.CENTER);
                c.drawText(decision.side.startsWith("BUY")?"B":"S",mx,my+4,p);p.setTextAlign(Paint.Align.LEFT);
                p.setColor(Color.WHITE);p.setTextSize(12);
                c.drawText((decision.side.startsWith("BUY")?"BUY READY":"SELL READY")+"  "+decision.score+"/100",left+8,top+34,p);
            }
            if(liveMode){p.setColor(Color.rgb(0,230,118));p.setTextSize(11);c.drawText("LIVE",right-34,bottom+24,p);}
        }

        float mapY(double v,double lo,double hi,int top,int bottom){
            float raw=bottom-(float)((v-lo)/(hi-lo))*(bottom-top);
            return (top+bottom)/2f+(raw-(top+bottom)/2f)*yZoom+yPan;
        }
        void drawLevel(Canvas c,double v,double lo,double hi,int top,int bottom,int color,String label){
            float y=mapY(v,lo,hi,top,bottom);if(y<top-20||y>bottom+20)return;
            p.setColor(color);p.setStrokeWidth(label.equals("LIVE")?2.2f:1.3f);p.setStyle(Paint.Style.STROKE);
            c.drawLine(30,y,getWidth()-48,y,p);p.setStyle(Paint.Style.FILL);
            p.setTextSize(10);c.drawRect(32,y-14,116,y+1,p);p.setColor(Color.BLACK);c.drawText(label+" "+fmt(v),36,y-3,p);
        }
    }
}
