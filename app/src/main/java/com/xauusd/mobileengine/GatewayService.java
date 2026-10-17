package com.xauusd.mobileengine;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class GatewayService extends Service {
    private ServerSocket server;
    private ExecutorService serverPool;
    private ScheduledExecutorService engine;
    private volatile boolean running;
    private volatile String lastBgStatus = "";
    private volatile long lastBgHeartbeat = 0L;
    private volatile long lastNextPollLog = 0L;
    private volatile long nextBgAttemptAt = 0L;
    private volatile int bgFailureCount = 0;
    private volatile String lastFeedStatus = "";
    private volatile long lastFeedStatusAt = 0L;

    public static boolean isEaRecentlyOnline(Context c){
        long last=c.getSharedPreferences("rayyan4_gateway",Context.MODE_PRIVATE).getLong("ea_last_poll_ms",0L);
        long age=System.currentTimeMillis()-last;
        return last>0L && age>=0L && age<=90000L;
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(77,notification("Background Engine berjalan • menyiapkan LAN Gateway"));
        if(!running){ running=true; startServer(); startBackgroundEngine(); }
        return START_STICKY;
    }

    private void startServer(){
        serverPool=Executors.newCachedThreadPool();
        serverPool.execute(()->{
            try{
                server=new ServerSocket(8787);
                AppLog.add(this,"GATEWAY","LAN gateway listening on port 8787");
                getSystemService(NotificationManager.class).notify(77,notification("MT5 LAN Gateway aktif • port 8787 | Background Engine berjalan"));
                while(!server.isClosed()){
                    final Socket s=server.accept();
                    serverPool.execute(()->handle(s));
                }
            }catch(Exception e){
                AppLog.add(this,"GATEWAY","server stopped: "+e.getMessage());
                getSystemService(NotificationManager.class).notify(77,notification("LAN Gateway gagal aktif • periksa port 8787"));
            }
        });
    }

    static String decorateFeedSnapshot(String rawJson,long feedAgeMs,boolean feedStale){
        try{
            org.json.JSONObject snapshot=new org.json.JSONObject(rawJson);
            long sourceTickAge=snapshot.optLong("tickAgeSec",0L);
            long ageSeconds=Math.max(0L,feedAgeMs)/1000L;
            long effectiveTickAge=Math.max(0L,sourceTickAge)+ageSeconds;
            snapshot.put("gatewayFeedAgeSec",ageSeconds);
            snapshot.put("tickAgeSec",effectiveTickAge);

            boolean quoteFresh=snapshot.optBoolean("ok",false)&&!feedStale&&effectiveTickAge<=120L;
            if(!quoteFresh){
                snapshot.put("ok",false);
                snapshot.put("marketOpen",false);
                if(feedStale){
                    snapshot.put("error","MT5_FEED_STALE");
                }else if(effectiveTickAge>120L){
                    snapshot.put("error","MARKET_CLOSED");
                }else if(snapshot.optString("error","").isEmpty()){
                    snapshot.put("error","MARKET_CLOSED");
                }
            }
            return snapshot.toString();
        }catch(Exception ignored){
            return "{\"ok\":false,\"error\":\"MT5_FEED_STALE\"}";
        }
    }

    private void handle(Socket s){
        try(Socket socket=s){
            socket.setSoTimeout(4000);
            BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String requestLine=r.readLine();
            String line; int contentLength=0;
            while((line=r.readLine())!=null && !line.isEmpty()){
                String lower=line.toLowerCase(Locale.US);
                if(lower.startsWith("content-length:")){try{contentLength=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}catch(Exception ignored){}}
            }
            String path=first(requestLine);
            String out;
            if(path.startsWith("/feed") && requestLine!=null && requestLine.startsWith("POST ")){
                char[] body=new char[Math.max(0,Math.min(contentLength,250000))];
                int total=0;
                while(total<body.length){int got=r.read(body,total,body.length-total);if(got<0)break;total+=got;}
                String json=new String(body,0,total).trim();
                String feedSymbol="";
                try { feedSymbol=new org.json.JSONObject(json).optString("symbol",""); } catch(Exception ignored) {}
                if(json.startsWith("{") && json.contains("\"bid\"") && json.contains("\"m1\"") &&
                    ("XAUUSDm".equals(feedSymbol)||"BTCUSDm".equals(feedSymbol)||"ETHUSDm".equals(feedSymbol))){
                    getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit()
                        .putString("mt5_feed_json_"+feedSymbol,json)
                        .putLong("mt5_feed_at_"+feedSymbol,System.currentTimeMillis()).apply();
                    logFeedStatus("POST RECEIVED symbol="+feedSymbol+" bytes="+json.length());
                    out="FEED OK";
                }else{
                    logFeedStatus("POST REJECTED contentLength="+contentLength+" bodyChars="+json.length());
                    out="FEED ERROR";
                }
            }else if(path.startsWith("/feed")){
                String requestedSymbol=query(path,"symbol");
                if(requestedSymbol.isEmpty())requestedSymbol="XAUUSDm";
                SharedPreferences fp=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
                String json=fp.getString("mt5_feed_json_"+requestedSymbol,"");
                long at=fp.getLong("mt5_feed_at_"+requestedSymbol,0L);
                if(!("XAUUSDm".equals(requestedSymbol)||"BTCUSDm".equals(requestedSymbol)||"ETHUSDm".equals(requestedSymbol))){
                    out="{\"ok\":false,\"error\":\"UNSUPPORTED_SYMBOL\"}";
                }else if(json.isEmpty()){
                    logFeedStatus("WAITING_FOR_MT5_FEED symbol="+requestedSymbol);
                    out="{\"ok\":false,\"error\":\"WAITING_FOR_MT5_FEED\"}";
                }else{
                    long feedAgeMs=Math.max(0L,System.currentTimeMillis()-at);
                    boolean feedStale=feedAgeMs>10000L;
                    if(feedStale){
                        logFeedStatus("MT5_FEED_STALE_HISTORY symbol="+requestedSymbol+" ageMs="+feedAgeMs);
                    }else{
                        logFeedStatus("FEED SERVED symbol="+requestedSymbol+" bytes="+json.length()+" ageMs="+feedAgeMs);
                    }
                    // Keep valid OHLC available for chart rendering even when the live feed is old.
                    // The returned snapshot is explicitly marked not-tradeable when stale.
                    out=decorateFeedSnapshot(json,feedAgeMs,feedStale);
                }
            }else if(path.startsWith("/next")){
                long now=System.currentTimeMillis();
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putLong("ea_last_poll_ms",now).apply();
                if(now-lastNextPollLog>=30000L){
                    lastNextPollLog=now;
                    AppLog.add(this,"MT5 GATEWAY","EA polling /next received");
                }
                out=getPending();
            }else if(path.startsWith("/ack")){
                String id=query(path,"id");
                String result=query(path,"result");
                String detail=query(path,"detail");
                out=ack(id,result,detail);
            }else if(path.startsWith("/state")){
                out=receiveState(path);
            }else{
                out="Rayyan4 gateway OK";
            }
            byte[] b=out.getBytes(StandardCharsets.UTF_8);
            String h="HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n";
            OutputStream o=socket.getOutputStream();
            o.write(h.getBytes(StandardCharsets.UTF_8));o.write(b);o.flush();
        }catch(Exception e){ AppLog.add(this,"GATEWAY","request error: "+e.getMessage()); }
    }

    private synchronized void logFeedStatus(String value){
        long now=System.currentTimeMillis();
        if(!value.equals(lastFeedStatus)||now-lastFeedStatusAt>=30000L){
            lastFeedStatus=value;
            lastFeedStatusAt=now;
            AppLog.add(this,"MT5 FEED",value);
        }
    }

    /**
     * Read-only EA order-state heartbeat. This endpoint never queues or sends trades.
     * The APK uses it to distinguish a pending order from an open position before
     * considering cancellation/re-entry.
     */
    private synchronized String receiveState(String path){
        String id=query(path,"id");
        String state=query(path,"state");
        String ticket=query(path,"ticket");
        String symbol=query(path,"symbol");
        String side=query(path,"side");
        String price=query(path,"price");
        if(id==null||id.trim().isEmpty())return "STATE ERROR";
        if(!"NONE".equals(state)&&!"PENDING".equals(state)&&!"POSITION".equals(state))
            return "STATE ERROR";
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String snapshot=id+"|"+state+"|"+ticket+"|"+symbol+"|"+side+"|"+price;
        String previous=p.getString("ea_order_state_snapshot","");
        p.edit().putString("ea_order_state_id",id)
            .putString("ea_order_state",state)
            .putString("ea_order_state_ticket",ticket==null?"":ticket)
            .putString("ea_order_state_symbol",symbol==null?"":symbol)
            .putString("ea_order_state_side",side==null?"":side)
            .putString("ea_order_state_price",price==null?"":price)
            .putString("ea_order_state_snapshot",snapshot)
            .putLong("ea_order_state_at",System.currentTimeMillis()).apply();
        if(!snapshot.equals(previous)){
            AppLog.add(this,"MT5 STATE","id="+id+" state="+state+
                (ticket==null||ticket.isEmpty()?"":" ticket="+ticket)+
                (symbol==null||symbol.isEmpty()?"":" symbol="+symbol));
        }
        return "STATE OK";
    }

    private String first(String l){
        if(l==null)return "";
        String[] a=l.split(" ");
        return a.length>1?a[1]:"";
    }

    private String query(String path,String key){
        int q=path.indexOf('?');if(q<0)return "";
        String[] a=path.substring(q+1).split("&");
        for(String x:a){
            int e=x.indexOf('=');
            if(e>0&&key.equals(x.substring(0,e))){
                String value=x.substring(e+1);
                try {
                    return URLDecoder.decode(value,"UTF-8");
                } catch (UnsupportedEncodingException ignored) {
                    return value;
                }
            }
        }
        return "";
    }

    private synchronized String getPending(){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",0);
        String cmd=p.getString("next","");
        // Discard legacy test commands; never execute them on a later reconnect.
        if(cmd.contains("type=TEST") || cmd.contains("side=TEST") || cmd.contains("id=TEST-")){
            p.edit().remove("next").apply();
            AppLog.add(this,"MT5","discarded legacy TEST/ACK queue; LIMIT only");
            return "";
        }
        // A scalping command must not sit in the queue and execute after MT5 reconnects much later.
        String queuedId=field(cmd,"id");
        try{
            long queuedAt=Long.parseLong(queuedId);
            if(System.currentTimeMillis()-queuedAt>15000L){
                p.edit().remove("next")
                    .putString("order_status_id",queuedId)
                    .putString("order_status","EXPIRED")
                    .putString("order_status_detail","Perintah LIMIT kedaluwarsa setelah 15 detik; cek tab Trade dan Experts MT5 sebelum mengirim ulang")
                    .putLong("order_status_at",System.currentTimeMillis()).apply();
                AppLog.add(this,"ORDER","EXPIRED id="+queuedId+" stale LIMIT command not sent to EA");
                return "";
            }
        }catch(Exception ignored){}
        return cmd;
    }

    private synchronized String ack(String id,String result,String detail){
        if(id==null||id.isEmpty())return "ACK ERROR";
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String current=p.getString("next","");
        String currentId=field(current,"id");
        if(id.equals(currentId)){
            String safeResult=(result==null||result.isEmpty())?"UNKNOWN":result;
            p.edit().remove("next")
                .putString("order_status_id",id)
                .putString("order_status",safeResult)
                .putString("order_status_detail",(detail==null||detail.trim().isEmpty())?"Hasil diterima dari EA MT5":detail)
                .putLong("order_status_at",System.currentTimeMillis()).apply();
            AppLog.add(this,"MT5","ACK id="+id+" result="+safeResult);
            return "ACK OK";
        }
        AppLog.add(this,"MT5","ACK STALE id="+id+" current="+currentId);
        return "ACK STALE";
    }

    private String field(String s,String key){
        if(s==null)return "";
        String p=key+"=";int a=s.indexOf(p);if(a<0)return "";
        a+=p.length();int b=s.indexOf(';',a);if(b<0)b=s.length();
        return s.substring(a,b);
    }

    private void startBackgroundEngine(){
        engine=Executors.newScheduledThreadPool(1);
        engine.scheduleWithFixedDelay(this::backgroundSignalTick,5,15,TimeUnit.SECONDS);
    }

    private void backgroundSignalTick(){
        if(MainActivity.isAppVisible())return;
        long now=System.currentTimeMillis();
        if(!isEaRecentlyOnline(this)){
            logBgStatus("EA_OFFLINE signal notifications paused");
            return;
        }
        if(now<nextBgAttemptAt)return;
        nextBgAttemptAt=now+30000L;
        try{
            String market=getSharedPreferences("rayyan4_indicators",MODE_PRIVATE).getString("market","XAUUSD");
            Mt5FeedClient.Snapshot feed=Mt5FeedClient.fetch(market);
            String expected=Mt5FeedClient.expectedSymbol(market);
            if(!expected.equals(feed.symbol))throw new java.io.IOException("MT5 feed symbol mismatch expected="+expected+" got="+feed.symbol);
            Candle[] m1=feed.m1;
            Candle[] m5=feed.m5;
            double bid=feed.bid,ask=feed.ask;
            bgFailureCount=0;
            nextBgAttemptAt=System.currentTimeMillis()+30000L;
            if(m1==null||m5==null||m1.length<30||m5.length<30){
                logBgStatus("DATA_INSUFFICIENT "+market+" M1="+(m1==null?0:m1.length)+" M5="+(m5==null?0:m5.length));
                return;
            }
            logBgStatus("FEED_OK "+market+" "+feed.symbol+" M1="+m1.length+" M5="+m5.length);
            SignalResult s=SignalEngine.evaluate(m1,m5,bid,ask);
            if(s==null){logBgStatus("SIGNAL_RESULT_NULL "+market);return;}
            String side=s.side;
            if(!"BUY".equals(side)&&!"SELL".equals(side))logBgStatus("SIGNAL_WAIT "+market+" side="+side+" confidence="+s.confidence);
            SharedPreferences p=getSharedPreferences("rayyan4_bg_signal",MODE_PRIVATE);
            String last=p.getString("side_"+market,"");
            long lastAlertAt=p.getLong("last_alert_at_"+market,0L);
            long alertNow=System.currentTimeMillis();
            if(("BUY".equals(side)||"SELL".equals(side))&&!side.equals(last)&&alertNow-lastAlertAt>=180000L){
                p.edit().putString("side_"+market,side).putLong("last_alert_at_"+market,alertNow).apply();
                String msg=market+" SCALPING\n\n"+side+
                    "\nEntry: "+f(s.entry)+"\nSL: "+f(s.sl)+"\nTP1: "+f(s.tp1)+"\nTP2: "+f(s.tp2)+
                    "\nTF: M1\nBias: M5 "+s.m5Bias+"\nSetup: "+s.setup+"\nConfidence: "+s.confidence+"/5";
                AppLog.add(this,"BACKGROUND SIGNAL",market+" "+side+" "+f(s.entry)+" score="+s.score);
                HistoryStore.add(this,"SIGNAL",side,s.entry,s.sl,s.tp1,s.tp2,s.confidence);
                String token=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getString("token","");
                String chat=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getString("chat","");
                boolean tg=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getBoolean("enabled",false);
                if(tg)TelegramClient.send(token,chat,msg,(ok,detail)->AppLog.add(this,"TELEGRAM",ok?"background sent":"background error"));
                notifyAlert(market+" "+side,"Entry "+f(s.entry)+" | SL "+f(s.sl)+" | TP1 "+f(s.tp1));
            }
        }catch(Exception e){
            bgFailureCount=Math.min(bgFailureCount+1,5);
            long delay=Math.min(300000L,30000L*(1L<<Math.min(bgFailureCount-1,4)));
            nextBgAttemptAt=System.currentTimeMillis()+delay;
            logBgStatus("FEED_ERROR retry="+(delay/1000L)+"s "+e.getClass().getSimpleName()+": "+e.getMessage());
        }
    }

    private synchronized void logBgStatus(String status){
        long now=System.currentTimeMillis();
        boolean changed=!status.equals(lastBgStatus);
        boolean heartbeat=now-lastBgHeartbeat>=60000L;
        if(changed||heartbeat){
            AppLog.add(this,"BACKGROUND",status);
            lastBgStatus=status;
            lastBgHeartbeat=now;
        }
    }

    private String f(double v){return String.format(Locale.US,"%.2f",v);}

    private void notifyAlert(String title,String text){
        String id="rayyan4_alerts";
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(id,"Rayyan4 Alerts",NotificationManager.IMPORTANCE_HIGH));
        n.notify((int)(System.currentTimeMillis()&0x7fffffff),
            new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).build());
    }

    private Notification notification(String message){
        String id="rayyan4_gateway";
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(id,"MT5 Gateway / Background Engine",NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Rayyan4 Background Engine")
            .setContentText(message)
            .setOngoing(true).setPriority(Notification.PRIORITY_LOW).build();
    }

    @Override public void onDestroy(){
        running=false;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        if(serverPool!=null)serverPool.shutdownNow();
        if(engine!=null)engine.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i){return null;}
}