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

    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(77,notification());
        if(!running){ running=true; startServer(); startBackgroundEngine(); }
        return START_STICKY;
    }

    private void startServer(){
        serverPool=Executors.newCachedThreadPool();
        serverPool.execute(()->{
            try{
                server=new ServerSocket(8787);
                while(!server.isClosed()){
                    final Socket s=server.accept();
                    serverPool.execute(()->handle(s));
                }
            }catch(Exception e){ AppLog.add(this,"GATEWAY","server stopped: "+e.getMessage()); }
        });
    }

    private void handle(Socket s){
        try(Socket socket=s){
            socket.setSoTimeout(4000);
            BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String requestLine=r.readLine();
            String line;
            while((line=r.readLine())!=null && !line.isEmpty()){}
            String path=first(requestLine);
            String out;
            if(path.startsWith("/next")){
                out=getPending();
            }else if(path.startsWith("/ack")){
                String id=query(path,"id");
                String result=query(path,"result");
                out=ack(id,result);
            }else{
                out="Rayyan4 gateway OK";
            }
            byte[] b=out.getBytes(StandardCharsets.UTF_8);
            String h="HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n";
            OutputStream o=socket.getOutputStream();
            o.write(h.getBytes(StandardCharsets.UTF_8));o.write(b);o.flush();
        }catch(Exception e){ AppLog.add(this,"GATEWAY","request error: "+e.getMessage()); }
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
            if(e>0&&key.equals(x.substring(0,e)))return URLDecoder.decode(x.substring(e+1),StandardCharsets.UTF_8);
        }
        return "";
    }

    private synchronized String getPending(){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",0);
        String cmd=p.getString("next","");
        // Discard stale TEST/ACK commands left by older Rayyan4 APK builds.
        if(cmd.contains("type=TEST") || cmd.contains("side=TEST") || cmd.contains("id=TEST-")){
            p.edit().remove("next").apply();
            AppLog.add(this,"MT5","discarded legacy TEST/ACK queue; LIMIT only");
            return "";
        }
        return cmd;
    }

    private synchronized String ack(String id,String result){
        if(id==null||id.isEmpty())return "ACK ERROR";
        String current=getPending();
        String currentId=field(current,"id");
        if(id.equals(currentId)){
            getSharedPreferences("rayyan4_gateway",0).edit().remove("next").apply();
            AppLog.add(this,"MT5","ACK id="+id+" result="+result);
            return "ACK OK";
        }
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
        engine.scheduleAtFixedRate(this::backgroundSignalTick,5,5,TimeUnit.SECONDS);
    }

    private void backgroundSignalTick(){
        if(MainActivity.isAppVisible())return;
        try{
            Candle[] m1=BiquoteClient.candles("1m");
            Candle[] m5=BiquoteClient.candles("5m");
            double[] t=BiquoteClient.tick();
            if(m1.length<30||m5.length<30)return;
            SignalResult s=SignalEngine.evaluate(m1,m5,t[0],t[1]);
            String side=s.side;
            SharedPreferences p=getSharedPreferences("rayyan4_bg_signal",MODE_PRIVATE);
            String last=p.getString("side","");
            if(("BUY".equals(side)||"SELL".equals(side))&&!side.equals(last)){
                p.edit().putString("side",side).apply();
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
            AppLog.add(this,"BACKGROUND","feed error: "+e.getMessage());
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

    private Notification notification(){
        String id="rayyan4_gateway";
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(id,"MT5 Gateway / Background Engine",NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Rayyan4 Background Engine")
            .setContentText("Signal monitor + MT5 LAN gateway aktif")
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