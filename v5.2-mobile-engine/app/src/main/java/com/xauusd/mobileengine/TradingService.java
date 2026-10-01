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
    TelegramClient telegram;
    static final String BASE="https://biquote.io/api/XAUUSD";
    long lastOrderAt=0;
    long lastSnapshotAt=0;
    String lastSnapshotBody="";
    String lastSignalKey="";
    int dayCount=0;
    double dayStartEquity=-1;
    String tradeDay="";

    Runnable loop=new Runnable(){@Override public void run(){tick();h.postDelayed(this,3000);}};

    @Override public void onCreate(){
        super.onCreate();store=new SecurityStore(this);exness=new ExnessClient(store);telegram=new TelegramClient(store);createChannel();
        Notification n=new Notification.Builder(this,CH).setContentTitle("XAUUSD V5.2 Auto Engine")
            .setContentText("Auto execution armed with risk guards").setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setColor(Color.rgb(0,200,83)).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(77,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(77,n);
        h.post(loop);
    }

    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}

    @Override public void onDestroy(){h.removeCallbacksAndMessages(null);stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}

    void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"XAUUSD Auto Trading",NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Background strategy and execution status");getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    void tick(){
        if(!store.rawPrefs().getBoolean("auto",false))return;
        new Thread(()->{
            try{
                resetDailyGuardsIfNeeded();
                JSONObject t=new JSONObject(get(BASE));
                double mid=t.optDouble("mid",0), spread=t.optDouble("spread",0);
                ArrayList<StrategyEngine.Candle> m1=parse(get(BASE+"/ohlc?interval=1m&limit=100"));
                ArrayList<StrategyEngine.Candle> m5=parse(get(BASE+"/ohlc?interval=5m&limit=60"));
                StrategyEngine.Decision d=StrategyEngine.analyze(m1,m5,mid);
                double maxSpread=parseDouble(store.rawPrefs().getString("max_spread","0.50"),0.50);
                int minScore=(int)parseDouble(store.rawPrefs().getString("min_score","75"),75);
                int maxTrades=(int)parseDouble(store.rawPrefs().getString("max_trades","3"),3);
                long cooldown=(long)parseDouble(store.rawPrefs().getString("cooldown","10"),10)*60_000L;
                refreshSnapshot();
                if(dayStartEquity<0)dayStartEquity=readEquity();
                if(dayStartEquity>0){
                    double eq=readEquity();
                    double dd=100.0*(dayStartEquity-eq)/dayStartEquity;
                    double maxLoss=parseDouble(store.rawPrefs().getString("max_loss","2.0"),2.0);
                    if(dd>=maxLoss){notifyUser("RISK STOP","Daily drawdown guard reached; auto engine remains armed but will not place new orders.");return;}
                }
                if(t.optBoolean("stale",false))return;\n                if(spread>maxSpread || d.score<minScore || "WAIT".equals(d.side))return;
                if(dayCount>=maxTrades)return;
                if(System.currentTimeMillis()-lastOrderAt<cooldown)return;
                String key=d.side+"-"+(m1.get(m1.size()-2).t);
                if(key.equals(lastSignalKey))return;

                // One-at-a-time safety: do not stack orders blindly. Snapshot check is best-effort.
                if(hasActiveExposure())return;

                String lot=store.rawPrefs().getString("lot","0.01");
                int digits=2;
                try{ ExnessClient.Response cr=exness.instrumentConditions("XAUUSD"); if(cr.ok()) digits=new JSONObject(cr.body).optInt("point_digits",2); }catch(Exception ignored){}
                String side=d.side.startsWith("BUY")?"buy":"sell";
                ExnessClient.Response r=exness.placeLimit("XAUUSD",side,lot,fmt(d.entry,digits),fmt(d.sl,digits),fmt(d.tp2,digits),"XAUUSD-V5.2-AUTO");
                if(r.ok()){
                    lastOrderAt=System.currentTimeMillis();lastSignalKey=key;dayCount++;
                    notifyUser("ORDER ACK "+d.side,d.summary());\n                    if(telegram.enabled()) try{ telegram.send("🟢 XAUUSD SCALPING\\n"+d.side+"\\n"+d.summary()+"\\nTF: M1 | Bias: M5\\nSetup: Wick + Sweep + BOS + EMA/RSI/MACD\nScore: "+d.score+"/100"); }catch(Exception ignored){}
                }else notifyUser("ORDER REJECT "+r.code,trim(r.body));
            }catch(Exception e){notifyUser("AUTO ERROR",e.getMessage());}
        }).start();
    }

    void resetDailyGuardsIfNeeded(){
        String today=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(new java.util.Date());
        if(!today.equals(tradeDay)){ tradeDay=today; dayCount=0; lastSignalKey=""; dayStartEquity=readEquity(); }
    }

    void refreshSnapshot(){
        if(System.currentTimeMillis()-lastSnapshotAt < 15000 && !lastSnapshotBody.isEmpty()) return;
        try{ ExnessClient.Response r=exness.snapshot(); if(r.ok()){ lastSnapshotBody=r.body; lastSnapshotAt=System.currentTimeMillis(); } }
        catch(Exception ignored){}
    }

    double readEquity(){
        try{
            if(lastSnapshotBody.isEmpty()) return -1;
            JSONObject j=new JSONObject(lastSnapshotBody);
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
    String fmt(double d){return String.format(Locale.US,"%.2f",d);}\n    String fmt(double d,int digits){return String.format(Locale.US,"%."+Math.max(0,Math.min(8,digits))+"f",d);}
    String trim(String s){return s==null?"":s.length()>500?s.substring(0,500)+"…":s;}
    void notifyUser(String title,String msg){
        Notification n=new Notification.Builder(this,CH).setContentTitle(title).setContentText(msg==null?"":trim(msg))
            .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis()%100000),n);
    }
}
