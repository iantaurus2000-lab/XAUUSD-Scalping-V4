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
    long lastLoad=0;

    Runnable marketPoll = new Runnable() {
        @Override public void run() {
            loadMarket();
            handler.postDelayed(this, 2500);
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

        chart=new ChartView(); addCard(chart,300);
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

        Button risk=btn("💰 Risk & Strategy");
        Button orders=btn("📋 Orders / Account");
        Button mt5=btn("🔗 MT5 Bridge");
        Button telegram=btn("📲 Telegram");
        content.addView(risk,new LinearLayout.LayoutParams(-1,56));
        content.addView(orders,new LinearLayout.LayoutParams(-1,56));
        content.addView(mt5,new LinearLayout.LayoutParams(-1,56));
        content.addView(telegram,new LinearLayout.LayoutParams(-1,56));

        botState=tv("AUTO: OFF",13);botState.setGravity(Gravity.CENTER);botState.setBackground(bg("#151B23",15));content.addView(botState,new LinearLayout.LayoutParams(-1,48));
        account=tv("Account: --",12);content.addView(account);
        log=tv("Ready. Signal engine active; automatic execution is OFF until explicitly started.",11);log.setTextColor(Color.GRAY);content.addView(log);

        connect.setOnClickListener(v->showExnessDialog());
        manual.setOnClickListener(v->showManualOrderDialog());
        start.setOnClickListener(v->startAuto());
        stop.setOnClickListener(v->stopAuto());
        risk.setOnClickListener(v->showRiskDialog());
        orders.setOnClickListener(v->showAccountDialog());
        mt5.setOnClickListener(v->showMt5Dialog());
        telegram.setOnClickListener(v->showTelegramDialog());
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
        CheckBox direct=new CheckBox(this);direct.setText("Direct Exness API (eligible accounts only)");direct.setTextColor(Color.WHITE);direct.setChecked(true);
        box.addView(id,new LinearLayout.LayoutParams(-1,55));box.addView(key,new LinearLayout.LayoutParams(-1,55));box.addView(secret,new LinearLayout.LayoutParams(-1,55));box.addView(host,new LinearLayout.LayoutParams(-1,55));box.addView(direct);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Exness API Connection").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("SAVE + TEST",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            exness.saveCredentials(id.getText().toString(),key.getText().toString(),secret.getText().toString(),host.getText().toString());
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
                ExnessClient.Response r=exness.placeLimit("XAUUSD",side,lot,entry,sl,tp,"XAUUSD-V5.2-"+tag);
                runOnUiThread(()->{
                    if(r.ok()){log.setText("ORDER ACK: "+r.body);toast("Order diterima Exness (ACK).");}
                    else {log.setText("ORDER REJECT "+r.code+": "+trim(r.body));toast("Order ditolak: "+r.code);}
                });
            }catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
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

    void showRiskDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(18,4,18,4);
        EditText lot=input("Fixed lot",store.get("lot","0.01"));
        EditText maxSpread=input("Max spread (price)",store.get("max_spread","0.50"));
        EditText minScore=input("Auto minimum score (0-100)",store.get("min_score","75"));
        EditText maxTrades=input("Max auto orders/day",store.get("max_trades","3"));
        EditText maxLoss=input("Max daily drawdown %",store.get("max_loss","2.0"));
        EditText cooldown=input("Cooldown minutes",store.get("cooldown","10"));
        EditText rr=input("TP2 risk multiple",store.get("rr","2.0"));
        for(EditText e:new EditText[]{lot,maxSpread,minScore,maxTrades,maxLoss,cooldown,rr})box.addView(e,new LinearLayout.LayoutParams(-1,52));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Risk / Strategy Guards").setView(box).setNegativeButton("Cancel",null).setPositiveButton("SAVE",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            store.rawPrefs().edit().putString("lot",lot.getText().toString()).putString("max_spread",maxSpread.getText().toString()).putString("min_score",minScore.getText().toString()).putString("max_trades",maxTrades.getText().toString()).putString("max_loss",maxLoss.getText().toString()).putString("cooldown",cooldown.getText().toString()).putString("rr",rr.getText().toString()).apply();
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
        new AlertDialog.Builder(this).setTitle("MT5 Execution Path")
            .setMessage("MT5 mobile tidak menyediakan eksekusi custom EA langsung. Untuk akun MT5 standar, jalur otomatis memerlukan terminal MT5 desktop/VPS/bridge yang menjalankan EA.\n\nV5.2 ini menyediakan Direct Exness API untuk akun yang eligible serta adapter MT5-Bridge sebagai jalur terpisah.")
            .setPositiveButton("OK",null).show();
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
                String tick=get(BASE);
                String j1=get(BASE+"/ohlc?interval=1m&limit=100");
                String j5=get(BASE+"/ohlc?interval=5m&limit=60");
                JSONObject t=new JSONObject(tick);
                ArrayList<StrategyEngine.Candle> a=parse(j1),b=parse(j5);
                double mm=t.optDouble("mid",0),sp=t.optDouble("spread",0);
                StrategyEngine.Decision dd=StrategyEngine.analyze(a,b,mm);
                runOnUiThread(()->{
                    mid=mm;spread=sp;m1=a;m5=b;decision=dd;lastLoad=System.currentTimeMillis();
                    price.setText(fmt(mid));connection.setText("● "+(t.optBoolean("stale",false)?"STALE":"MARKET DATA LIVE")+"   Spread "+fmt(spread));
                    renderDecision();chart.invalidate();
                });
            }catch(Exception e){runOnUiThread(()->connection.setText("● MARKET FEED ERROR")); }
        }).start();
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

    String fmt(double d){return String.format(Locale.US,"%.2f",d);}
    String trim(String s){return s==null?"":s.length()>800?s.substring(0,800)+"…":s;}
    void toast(String s){Toast.makeText(this,s==null?"":s,Toast.LENGTH_LONG).show();}

    class ChartView extends View{
        Paint p=new Paint(3);
        ChartView(){super(MainActivity.this);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.drawColor(Color.rgb(11,15,21));if(m1.size()<2)return;
            int left=18,right=getWidth()-22,top=18,bottom=getHeight()-22,start=Math.max(0,m1.size()-65),count=m1.size()-start;
            double hi=-1e99,lo=1e99;for(int i=start;i<m1.size();i++){hi=Math.max(hi,m1.get(i).high);lo=Math.min(lo,m1.get(i).low);}
            double pad=(hi-lo)*0.10;hi+=pad;lo-=pad;float w=(right-left)/(float)count;
            p.setTextSize(20);p.setColor(Color.GRAY);c.drawText("M1",left,top+18,p);
            int i=0;for(int k=start;k<m1.size();k++){StrategyEngine.Candle x=m1.get(k);float cx=left+(i+.5f)*w;
                float yH=bottom-(float)((x.high-lo)/(hi-lo))*(bottom-top),yL=bottom-(float)((x.low-lo)/(hi-lo))*(bottom-top);
                float yO=bottom-(float)((x.open-lo)/(hi-lo))*(bottom-top),yC=bottom-(float)((x.close-lo)/(hi-lo))*(bottom-top);
                p.setColor(x.close>=x.open?Color.rgb(0,200,83):Color.rgb(255,70,70));p.setStrokeWidth(2);c.drawLine(cx,yH,cx,yL,p);
                float bw=Math.max(3,w*.60f);c.drawRect(cx-bw/2,Math.min(yO,yC),cx+bw/2,Math.max(yO,yC)+1,p);i++;
            }
            if(decision.entry>0)drawLevel(c,decision.entry,lo,hi,top,bottom,Color.YELLOW,"ENTRY");
            if(decision.sl>0)drawLevel(c,decision.sl,lo,hi,top,bottom,Color.RED,"SL");
            if(decision.tp2>0)drawLevel(c,decision.tp2,lo,hi,top,bottom,Color.CYAN,"TP2");

            int fs=Math.max(start,m1.size()-45);
            double sh=-1e99, slow=1e99;
            for(int z=fs;z<m1.size();z++){ sh=Math.max(sh,m1.get(z).high); slow=Math.min(slow,m1.get(z).low); }
            drawLevel(c,sh,lo,hi,top,bottom,Color.MAGENTA,"R");
            drawLevel(c,slow,lo,hi,top,bottom,Color.GREEN,"S");
            double range=sh-slow;
            if(range>0){
                drawLevel(c,sh-range*0.382,lo,hi,top,bottom,Color.LTGRAY,"F38");
                drawLevel(c,sh-range*0.500,lo,hi,top,bottom,Color.LTGRAY,"F50");
                drawLevel(c,sh-range*0.618,lo,hi,top,bottom,Color.LTGRAY,"F62");
            }
        }
        void drawLevel(Canvas c,double v,double lo,double hi,int top,int bottom,int color,String label){
            float y=bottom-(float)((v-lo)/(hi-lo))*(bottom-top);p.setColor(color);p.setStrokeWidth(1.5f);c.drawLine(18,y,getWidth()-22,y,p);p.setTextSize(12);c.drawText(label+" "+fmt(v),24,y-3,p);
        }
    }
}
