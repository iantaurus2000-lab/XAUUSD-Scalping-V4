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

    private void handle(Socket s){
        try(Socket socket=s){
            socket.setSoTimeout(5000);
            InputStream in=socket.getInputStream();
            String requestLine=readHttpLine(in);
            if(requestLine==null||requestLine.isEmpty())return;
            String method=requestLine.split(" ")[0];
            String path=first(requestLine);
            int contentLength=0;
            String line;
            while((line=readHttpLine(in))!=null&&!line.isEmpty()){
                int colon=line.indexOf(':');
                if(colon>0&&"content-length".equalsIgnoreCase(line.substring(0,colon).trim())){
                    try{contentLength=Integer.parseInt(line.substring(colon+1).trim());}catch(Exception ignored){}
                }
            }
            if(path.startsWith("/chart/upload")&&"POST".equalsIgnoreCase(method)){
                if(contentLength<8||contentLength>2000000){
                    writeResponse(socket,400,"text/plain; charset=utf-8","CHART ERROR SIZE".getBytes(StandardCharsets.UTF_8));return;
                }
                byte[] png=readExact(in,contentLength);
                if(png==null||png.length<8||png[0]!=(byte)0x89||png[1]!=0x50||png[2]!=0x4e||png[3]!=0x47){
                    writeResponse(socket,400,"text/plain; charset=utf-8","CHART ERROR PNG".getBytes(StandardCharsets.UTF_8));return;
                }
                // Write to a temporary file first so the UI never decodes a half-written PNG.
                File temp=getFileStreamPath("mt5_chart.tmp");
                File target=getFileStreamPath("mt5_chart.png");
                try(FileOutputStream file=new FileOutputStream(temp,false)){
                    file.write(png);file.flush();file.getFD().sync();
                }
                if(!temp.renameTo(target)){
                    // Some Android filesystems do not replace an existing target on rename.
                    if(target.exists()&&!target.delete()){
                        temp.delete();
                        writeResponse(socket,500,"text/plain; charset=utf-8","CHART ERROR REPLACE".getBytes(StandardCharsets.UTF_8));return;
                    }
                    if(!temp.renameTo(target)){
                        temp.delete();
                        writeResponse(socket,500,"text/plain; charset=utf-8","CHART ERROR COMMIT".getBytes(StandardCharsets.UTF_8));return;
                    }
                }
                long receivedAt=System.currentTimeMillis();
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit()
                    .putLong("mt5_chart_received_ms",receivedAt).putInt("mt5_chart_bytes",png.length).apply();
                writeResponse(socket,200,"text/plain; charset=utf-8","CHART OK".getBytes(StandardCharsets.UTF_8));return;
            }
            if(path.startsWith("/chart/latest")){
                File f=getFileStreamPath("mt5_chart.png");
                if(!f.isFile()||f.length()<8){
                    writeResponse(socket,404,"text/plain; charset=utf-8","CHART NOT READY".getBytes(StandardCharsets.UTF_8));return;
                }
                byte[] png=new byte[(int)Math.min(f.length(),2000000L)];
                try(FileInputStream file=new FileInputStream(f)){
                    int offset=0,n;
                    while(offset<png.length&&(n=file.read(png,offset,png.length-offset))>0)offset+=n;
                    if(offset!=png.length)png=Arrays.copyOf(png,offset);
                }
                writeResponse(socket,200,"image/png",png);return;
            }
            String out;
            if(path.startsWith("/next")){
                long now=System.currentTimeMillis();
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putLong("ea_last_poll_ms",now).apply();
                if(now-lastNextPollLog>=30000L){lastNextPollLog=now;AppLog.add(this,"MT5 GATEWAY","EA polling /next received");}
                out=getPending();
            }else if(path.startsWith("/ack")){
                out=ack(query(path,"id"),query(path,"result"),query(path,"detail"));
            }else if(path.startsWith("/indicator_next")){
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putLong("indicator_ea_last_poll_ms",System.currentTimeMillis()).apply();
                out=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).getString("indicator_next","");
            }else if(path.startsWith("/indicator_ack")){
                String id=query(path,"id");SharedPreferences ip=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
                String queued=ip.getString("indicator_next","");
                if(id!=null&&!id.isEmpty()&&id.equals(field(queued,"id"))){
                    ip.edit().remove("indicator_next").putString("indicator_ack_id",id).putLong("indicator_ack_at",System.currentTimeMillis()).apply();out="INDICATOR ACK OK";
                }else out="INDICATOR ACK STALE";
            }else if(path.startsWith("/state"))out=receiveState(path);
            else if(path.startsWith("/quote"))out=receiveQuote(path);
            else out="Rayyan4 gateway OK";
            writeResponse(socket,200,"text/plain; charset=utf-8",out.getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){AppLog.add(this,"GATEWAY","request error: "+e.getMessage());}
    }

    private String readHttpLine(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c,previous=-1;
        while((c=in.read())!=-1){
            if(previous==13&&c==10){
                byte[] a=b.toByteArray();int n=a.length;
                return new String(a,0,n>0&&a[n-1]==13?n-1:n,StandardCharsets.US_ASCII);
            }
            b.write(c);previous=c;if(b.size()>8192)throw new IOException("HTTP header too large");
        }
        return b.size()==0?null:new String(b.toByteArray(),StandardCharsets.US_ASCII);
    }
    private byte[] readExact(InputStream in,int length)throws IOException{
        byte[] data=new byte[length];int offset=0;
        while(offset<length){int n=in.read(data,offset,length-offset);if(n<0)return null;offset+=n;}
        return data;
    }
    private void writeResponse(Socket socket,int status,String contentType,byte[] body)throws IOException{
        String reason=status==200?"OK":status==404?"Not Found":"Bad Request";
        String h="HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+contentType+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o=socket.getOutputStream();o.write(h.getBytes(StandardCharsets.US_ASCII));o.write(body);o.flush();
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

    /** Persist real MT5 Bid/Ask, SYMBOL_POINT and terminal tick time. */
    private synchronized String receiveQuote(String path){
        String symbol=query(path,"symbol");
        if(!"XAUUSDm".equals(symbol)&&!"BTCUSDm".equals(symbol)&&!"ETHUSDm".equals(symbol))
            return "QUOTE ERROR SYMBOL";
        try{
            double bid=Double.parseDouble(query(path,"bid"));
            double ask=Double.parseDouble(query(path,"ask"));
            double point=Double.parseDouble(query(path,"point"));
            int digits=Integer.parseInt(query(path,"digits"));
            long tickMs=Long.parseLong(query(path,"tickms"));
            long now=System.currentTimeMillis();
            if(!QuoteSafety.valid(bid,ask)||point<=0.0||digits<0||digits>8||tickMs<=0L)
                return "QUOTE ERROR VALUES";
            getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit()
                .putString("broker_quote_bid_"+symbol,Double.toString(bid))
                .putString("broker_quote_ask_"+symbol,Double.toString(ask))
                .putString("broker_quote_point_"+symbol,Double.toString(point))
                .putInt("broker_quote_digits_"+symbol,digits)
                .putLong("broker_quote_tick_ms_"+symbol,tickMs)
                .putLong("broker_quote_received_ms_"+symbol,now)
                .apply();
            return "QUOTE OK";
        }catch(Exception e){return "QUOTE ERROR FORMAT";}
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
            Candle[] m1=BiquoteClient.candles("1m");
            Candle[] m5=BiquoteClient.candles("5m");
            double[] t=BiquoteClient.tick();
            bgFailureCount=0;
            nextBgAttemptAt=System.currentTimeMillis()+30000L;
            if(m1==null||m5==null||m1.length<30||m5.length<30){
                logBgStatus("DATA_INSUFFICIENT M1="+(m1==null?0:m1.length)+" M5="+(m5==null?0:m5.length));
                return;
            }
            logBgStatus("FEED_OK M1="+m1.length+" M5="+m5.length);
            SignalResult s=SignalEngine.evaluate(m1,m5,t[0],t[1],t[0],t[1]);
            if(s==null){logBgStatus("SIGNAL_RESULT_NULL");return;}
            String side=s.side;
            if(!"BUY".equals(side)&&!"SELL".equals(side))logBgStatus("SIGNAL_WAIT side="+side+" confidence="+s.confidence);
            SharedPreferences p=getSharedPreferences("rayyan4_bg_signal",MODE_PRIVATE);
            String last=p.getString("side","");
            long lastAlertAt=p.getLong("last_alert_at",0L);
            long alertNow=System.currentTimeMillis();
            if(("BUY".equals(side)||"SELL".equals(side))&&!side.equals(last)&&alertNow-lastAlertAt>=180000L){
                p.edit().putString("side",side).putLong("last_alert_at",alertNow).apply();
                String msg="XAUUSD SCALPING\n\n"+side+
                    "\nEntry: "+f(s.entry)+"\nSL: "+f(s.sl)+"\nTP1: "+f(s.tp1)+"\nTP2: "+f(s.tp2)+
                    "\nTF: M1\nBias: M5 "+s.m5Bias+"\nSetup: "+s.setup+"\nConfidence: "+s.confidence+"/5";
                AppLog.add(this,"BACKGROUND SIGNAL",side+" "+f(s.entry)+" score="+s.score);
                HistoryStore.add(this,"SIGNAL",side,s.entry,s.sl,s.tp1,s.tp2,s.confidence);
                String token=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getString("token","");
                String chat=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getString("chat","");
                boolean tg=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getBoolean("enabled",false);
                if(tg)TelegramClient.send(token,chat,msg,(ok,detail)->AppLog.add(this,"TELEGRAM",ok?"background sent":"background error"));
                notifyAlert("XAUUSD "+side,"Entry "+f(s.entry)+" | SL "+f(s.sl)+" | TP1 "+f(s.tp1));
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
