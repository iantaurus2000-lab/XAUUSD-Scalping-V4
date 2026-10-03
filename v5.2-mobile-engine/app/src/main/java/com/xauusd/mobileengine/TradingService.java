package com.xauusd.mobileengine;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.graphics.Color;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class TradingService extends Service {
    static final String CH="xauusd_engine";
    final Handler h=new Handler(Looper.getMainLooper());
    SecurityStore store;
    ExnessClient exness;
    MetaApiClient metaApi;
    FxOpenTickTraderClient fxOpen;
    TelegramClient telegram;
    TradeManager manager;
    static final String BASE="https://biquote.io/api/XAUUSD";
    long lastOrderAt=0;
    long lastSnapshotAt=0;
    String lastSnapshotBody="";
    String lastSignalKey="";
    int dayCount=0;
    long lastBarsAt=0;
    PowerManager.WakeLock wakeLock;
    volatile boolean busy=false;
    ArrayList<StrategyEngine.Candle> cachedM1=new ArrayList<>(),cachedM5=new ArrayList<>();
    double dayStartEquity=-1;
    String tradeDay="";

    Runnable loop=new Runnable(){@Override public void run(){tick();h.postDelayed(this,3000);}};

    @Override public void onCreate(){
        super.onCreate();
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"XAUUSD:Engine");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        store=new SecurityStore(this);exness=new ExnessClient(store);metaApi=new MetaApiClient(store);telegram=new TelegramClient(store);manager=new TradeManager(store,exness);fxOpen=new FxOpenTickTraderClient(store);createChannel();
        Notification n=new Notification.Builder(this,CH).setContentTitle("XAUUSD V5.2 Auto Engine")
            .setContentText("Auto execution armed with risk guards").setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setColor(Color.rgb(0,200,83)).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(77,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(77,n);
        h.postDelayed(loop,1000);
    }

    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}

    @Override public void onDestroy(){h.removeCallbacksAndMessages(null);if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}

    void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"XAUUSD Auto Trading",NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Background strategy and execution status");getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    void tick(){
        if(!store.rawPrefs().getBoolean("auto",false)||busy)return;
        busy=true;
        new Thread(()->{
            try{
                long now=System.currentTimeMillis();
                resetDailyGuardsIfNeeded();
                JSONObject tick=new JSONObject(get(BASE));
                double mid=tick.optDouble("mid",0),spread=tick.optDouble("spread",0);
                ArrayList<StrategyEngine.Candle> m1,m5;
                if(cachedM1.size()<60||now-lastBarsAt>=5000){
                    cachedM1=parse(get(BASE+"/ohlc?interval=1m&limit=100"));
                    cachedM5=parse(get(BASE+"/ohlc?interval=5m&limit=60"));
                    lastBarsAt=now;
                }
                m1=cloneWithLive(cachedM1,mid);m5=new ArrayList<>(cachedM5);
                StrategyEngine.Decision d=StrategyEngine.analyze(m1,m5,mid);

                double maxSpread=parseDouble(store.rawPrefs().getString("max_spread","0.50"),0.50);
                int minScore=(int)parseDouble(store.rawPrefs().getString("min_score","75"),75);
                int maxTrades=(int)parseDouble(store.rawPrefs().getString("max_trades","3"),3);
                long cooldown=(long)parseDouble(store.rawPrefs().getString("cooldown","10"),10)*60_000L;

                refreshSnapshot();
                if(!fxOpen.configured()) manager.manage(lastSnapshotBody,mid);
                if(dayStartEquity<0)dayStartEquity=readEquity();
                if(dayStartEquity>0){
                    double eq=readEquity();
                    double dd=100.0*(dayStartEquity-eq)/dayStartEquity;
                    double maxLoss=parseDouble(store.rawPrefs().getString("max_loss","2.0"),2.0);
                    if(dd>=maxLoss)return;
                }
                if(tick.optBoolean("stale",false)||spread>maxSpread||d.score<minScore||"WAIT".equals(d.side))return;
                if(d.side.startsWith("BUY")&&!store.rawPrefs().getBoolean("auto_buy",true))return;
                if(d.side.startsWith("SELL")&&!store.rawPrefs().getBoolean("auto_sell",true))return;
                if(dayCount>=maxTrades||System.currentTimeMillis()-lastOrderAt<cooldown)return;

                String key=d.side+"-"+m1.get(m1.size()-2).t;
                if(key.equals(lastSignalKey))return;

                String symbol=orderSymbol();
                if(store.rawPrefs().getBoolean("mt5_bridge",false) && telegram.enabled()){
                    String bridgeChat=store.get("mt5_bridge_chat","").trim();
                    if(!bridgeChat.isEmpty()){
                        String bridgeKey="XAUUSD-"+side+"-"+m1.get(m1.size()-2).t+"-"+System.currentTimeMillis();
                        try{
                            telegram.sendTo(bridgeChat,"[MT5BRIDGE]|V1|"+side.toUpperCase(Locale.US)+"_LIMIT|"+symbol+"|"+lot+"|"+fmt(d.entry,digits)+"|"+fmt(d.sl,digits)+"|"+fmt(d.tp2,digits)+"|"+bridgeKey);
                            telegram.sendTo(bridgeChat,"🟢 XAUUSD SCALPING\n"+d.side+"\n"+d.summary()+"\nMT5 BRIDGE: COMMAND SENT");
                            lastOrderAt=System.currentTimeMillis();lastSignalKey=key;dayCount++;
                            notifyUser("MT5 BRIDGE COMMAND SENT",d.summary());
                        }catch(Exception e){
                            notifyUser("MT5 BRIDGE ERROR",e.getMessage());
                        }
                    }
                    return;
                }
                if(fxOpen.configured()){ try{ FxOpenTickTraderClient.Response fr=fxOpen.trades(); if(fr.ok() && fr.body.contains(symbol)) return; }catch(Exception ignored){} } else if(metaApi.configured()){ try{ MetaApiClient.Response mr=metaApi.orders(); if(mr.ok() && mr.body.contains(symbol)) return; }catch(Exception ignored){} } else if(hasActiveExposure(symbol))return;

                String lot=store.rawPrefs().getString("lot","0.01");
                int digits=2;
                try{
                    ExnessClient.Response cr=exness.instrumentConditions(symbol);
                    if(cr.ok()&&cr.body.length()>0)digits=new JSONObject(cr.body).optInt("point_digits",2);
                }catch(Exception ignored){}

                String side=d.side.startsWith("BUY")?"buy":"sell";
                if(fxOpen.configured()){
                    FxOpenTickTraderClient.Response fr=fxOpen.placeLimit(symbol,side,Double.parseDouble(lot),Double.parseDouble(fmt(d.entry,digits)),Double.parseDouble(fmt(d.sl,digits)),Double.parseDouble(fmt(d.tp2,digits)),"XAUUSD-V5.2-AUTO");
                    if(fr.ok()){
                        lastOrderAt=System.currentTimeMillis();lastSignalKey=key;dayCount++;
                        notifyUser("FXOPEN DEMO ORDER",d.summary()+"\\n"+trim(fr.body));
                        if(telegram.enabled())try{telegram.send("🟢 XAUUSD SCALPING\\n"+d.side+"\\n"+d.summary()+"\\nFXOPEN DEMO: ORDER SUBMITTED");}catch(Exception ignored){}
                    }else notifyUser("FXOPEN ORDER REJECT "+fr.code,trim(fr.body));
                    return;
                }
                ExnessClient.Response r=exness.placeLimit(symbol,side,lot,fmt(d.entry,digits),fmt(d.sl,digits),fmt(d.tp2,digits),"XAUUSD-V5.2-AUTO");
                if(r.ok()){
                    lastOrderAt=System.currentTimeMillis();lastSignalKey=key;dayCount++;
                    String op=exness.operationId(r);
                    notifyUser("ORDER ACK "+d.side,d.summary()+"\nOperation: "+(op.isEmpty()?"pending":op));
                    waitForOperation(op);
                    if(telegram.enabled())try{
                        telegram.send("🟢 XAUUSD SCALPING\n"+d.side+"\n"+d.summary()+"\nTF: M1 | Bias: M5\nSetup: Liquidity Sweep + Wick Rejection + BOS\nConfidence: "+d.score+"/100");
                    }catch(Exception ignored){}
                }else{
                    notifyUser("ORDER REJECT "+r.code,trim(r.body));
                }
            }catch(Exception e){
                notifyUser("AUTO ERROR",e.getMessage());
            }finally{
                busy=false;
            }
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

    String orderSymbol(){
        String s=store.get("symbol","XAUUSD").trim();
        return s.isEmpty()?"XAUUSD":s;
    }

    boolean hasActiveExposure(String symbol){
        try{
            if(lastSnapshotBody.isEmpty())return false;
            JSONObject j=new JSONObject(lastSnapshotBody);
            JSONArray orders=j.optJSONArray("orders");
            if(orders==null)orders=j.optJSONArray("open_orders");
            if(orders!=null)for(int i=0;i<orders.length();i++){
                JSONObject o=orders.getJSONObject(i);
                if(symbol.equalsIgnoreCase(o.optString("instrument",o.optString("symbol",""))))return true;
            }
            JSONArray positions=j.optJSONArray("positions");
            if(positions==null)positions=j.optJSONArray("open_positions");
            if(positions!=null)for(int i=0;i<positions.length();i++){
                JSONObject o=positions.getJSONObject(i);
                if(symbol.equalsIgnoreCase(o.optString("instrument",o.optString("symbol",""))))return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    void waitForOperation(String operationId){
        if(operationId==null || operationId.isEmpty()) return;
        new Thread(()->{
            try{
                for(int i=0;i<8;i++){
                    ExnessClient.Response x=exness.operationStatus(operationId);
                    if(x.ok()){
                        String st=new JSONObject(x.body).optString("status","pending");
                        if("confirmed".equalsIgnoreCase(st)){ notifyUser("ORDER CONFIRMED","Exness confirmed operation "+operationId); refreshSnapshot(); return; }
                        if("rejected".equalsIgnoreCase(st)||"failed".equalsIgnoreCase(st)){ notifyUser("ORDER FAILED",trim(x.body)); return; }
                    }
                    Thread.sleep(1000);
                }
                refreshSnapshot();
            }catch(Exception ignored){}
        }).start();
    }

    void resetDailyGuardsIfNeeded(){
        String today=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(new java.util.Date());
        if(!today.equals(tradeDay)){ tradeDay=today; dayCount=0; lastSignalKey=""; dayStartEquity=readEquity(); }
    }

    void refreshSnapshot(){
        if(System.currentTimeMillis()-lastSnapshotAt < 15000 && !lastSnapshotBody.isEmpty()) return;
        try{
            if(fxOpen.configured()){
                FxOpenTickTraderClient.Response r=fxOpen.accountInfo();
                if(r.ok()){ lastSnapshotBody=r.body; lastSnapshotAt=System.currentTimeMillis(); }
                return;
            }
            ExnessClient.Response r=exness.snapshot();
            if(r.ok()){ lastSnapshotBody=r.body; lastSnapshotAt=System.currentTimeMillis(); }
        }catch(Exception ignored){}
    }

    double readEquity(){
        try{
            if(lastSnapshotBody.isEmpty()) return -1;
            JSONObject j=new JSONObject(lastSnapshotBody);
            double e=j.optDouble("Equity",Double.NaN);
            if(Double.isNaN(e))e=j.optDouble("equity",Double.NaN);
            if(!Double.isNaN(e))return e;
            JSONObject a=j.optJSONObject("account_state");
            if(a==null)a=j.optJSONObject("accountState");
            if(a!=null)return a.optDouble("equity",-1);
        }catch(Exception ignored){}
        return -1;
    }

    boolean hasActiveExposure(){
        try{
            if(lastSnapshotBody.isEmpty()) return false;
            JSONObject j=new JSONObject(lastSnapshotBody);
            JSONArray orders=j.optJSONArray("orders");if(orders==null)orders=j.optJSONArray("open_orders");
            if(orders!=null)for(int i=0;i<orders.length();i++){
                JSONObject o=orders.getJSONObject(i);
                if("XAUUSD".equalsIgnoreCase(o.optString("instrument",o.optString("symbol",""))))return true;
            }
            JSONArray positions=j.optJSONArray("positions");if(positions==null)positions=j.optJSONArray("open_positions");
            if(positions!=null)for(int i=0;i<positions.length();i++){
                JSONObject o=positions.getJSONObject(i);
                if("XAUUSD".equalsIgnoreCase(o.optString("instrument",o.optString("symbol",""))))return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    String get(String u)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setRequestMethod("GET");
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));StringBuilder s=new StringBuilder();String x;while((x=r.readLine())!=null)s.append(x);r.close();c.disconnect();return s.toString();
    }
    ArrayList<StrategyEngine.Candle> parse(String s)throws Exception{
        JSONArray ar=new JSONObject(s).getJSONArray("bars");ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(int i=ar.length()-1;i>=0;i--){JSONObject o=ar.getJSONObject(i);out.add(new StrategyEngine.Candle(o.optLong("openTime",0),o.getDouble("open"),o.getDouble("high"),o.getDouble("low"),o.getDouble("close")));}return out;
    }
    double parseDouble(String s,double f){try{return Double.parseDouble(s);}catch(Exception e){return f;}}
    String fmt(double d){return String.format(Locale.US,"%.2f",d);}
    String fmt(double d,int digits){return String.format(Locale.US,"%."+Math.max(0,Math.min(8,digits))+"f",d);}
    String trim(String s){return s==null?"":s.length()>500?s.substring(0,500)+"…":s;}
    void notifyUser(String title,String msg){
        Notification n=new Notification.Builder(this,CH).setContentTitle(title).setContentText(msg==null?"":trim(msg))
            .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis()%100000),n);
    }
}
