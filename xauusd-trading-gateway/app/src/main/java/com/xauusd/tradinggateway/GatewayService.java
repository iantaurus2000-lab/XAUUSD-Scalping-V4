package com.xauusd.tradinggateway;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class GatewayService extends android.app.Service {
    private static final String CHANNEL_ID="gateway_status";
    private static final int PORT=8787;
    private volatile boolean running; private ServerSocket serverSocket;
    private final java.util.concurrent.atomic.AtomicReference<String> lastCommand=new java.util.concurrent.atomic.AtomicReference<>("");
    private MetaKitClient meta;
    @Override public void onCreate(){super.onCreate();meta=new MetaKitClient(this);
        android.app.NotificationManager nm=getSystemService(android.app.NotificationManager.class);
        if(nm!=null)nm.createNotificationChannel(new android.app.NotificationChannel(CHANNEL_ID,"Gateway Status",android.app.NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(android.content.Intent i,int f,int id){startForeground(1001,notification());startServer();return START_STICKY;}
    private android.app.Notification notification(){return new android.app.Notification.Builder(this,CHANNEL_ID).setContentTitle("XAUUSD Trading Gateway V4").setContentText("Gateway ACTIVE • MT5 connector ready").setSmallIcon(com.xauusd.tradinggateway.R.drawable.ic_gateway_foreground).setOngoing(true).build();}
    private synchronized void startServer(){if(running)return;running=true;new Thread(()->{try{serverSocket=new ServerSocket(PORT);while(running){Socket s=serverSocket.accept();handle(s);}}catch(Exception ignored){}finally{running=false;closeServer();}},"gateway-api").start();}
    private void handle(Socket socket){try(Socket s=socket;BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));OutputStream out=s.getOutputStream()){
        String req=in.readLine();if(req==null)return;int len=0;String auth="";String line;while((line=in.readLine())!=null&&!line.isEmpty()){String low=line.toLowerCase();if(low.startsWith("content-length:"))try{len=Integer.parseInt(line.substring(15).trim());}catch(Exception ignored){}if(low.startsWith("x-gateway-token:"))auth=line.substring(line.indexOf(":")+1).trim();}
        char[] b=new char[Math.max(0,len)];int n=len>0?in.read(b):0;String body=n>0?new String(b,0,n):"";
        String path=req.split(" ")[1], response;int code=200;
        try{
            String expected=getSharedPreferences("gateway",0).getString("gateway_token","");
            if(expected.isEmpty()||!expected.equals(auth)){code=401;response="{\"ok\":false,\"error\":\"unauthorized\"}";}
            else if(path.startsWith("/health"))response="{\"ok\":true,\"service\":\"xauusd-trading-gateway\",\"version\":\"4.0\",\"connector\":\"metakit\"}";
            else if(path.startsWith("/status"))response=meta.accountInfo();
            else if(path.startsWith("/last-command"))response="{\"ok\":true,\"command\":"+json(lastCommand.get())+"}";
            else if(path.startsWith("/command")&&req.startsWith("POST")){lastCommand.set(body);response=execute(body);}
            else {code=404;response="{\"ok\":false,\"error\":\"not_found\"}";}
        }catch(Exception e){code=500;response="{\"ok\":false,\"error\":"+json(e.getMessage())+"}";}
        byte[] outb=response.getBytes(StandardCharsets.UTF_8);String h="HTTP/1.1 "+code+" "+(code==200?"OK":"ERROR")+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+outb.length+"\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.UTF_8));out.write(outb);out.flush();
    }catch(Exception ignored){}}
    private String execute(String body)throws Exception{
        JSONObject j=new JSONObject(body);String action=j.optString("action","").toUpperCase();String signal=j.optString("signal_id","gw-"+System.currentTimeMillis());
        if(!meta.configured())return "{\"ok\":false,\"error\":\"metakit_not_configured\"}";
        if(action.equals("BUY_LIMIT")||action.equals("SELL_LIMIT")){String side=action.startsWith("BUY")?"buy":"sell";return meta.placeLimit(side,j.getDouble("volume"),j.getDouble("entry"),j.getDouble("sl"),j.getDouble("tp"),signal);}
        if(action.equals("CANCEL"))return meta.cancel(j.getString("ticket"),signal);
        if(action.equals("STATUS"))return meta.accountInfo();
        if(action.equals("ORDERS"))return meta.orders();
        if(action.equals("POSITIONS"))return meta.positions();
        return "{\"ok\":false,\"error\":\"unsupported_action\"}";
    }
    private String json(String s){return s==null||s.isEmpty()?"null":"\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","").replace("\n","\\n")+"\"";}
    @Override public void onDestroy(){running=false;closeServer();super.onDestroy();}
    private void closeServer(){try{if(serverSocket!=null)serverSocket.close();}catch(Exception ignored){}serverSocket=null;}
    @Override public android.os.IBinder onBind(android.content.Intent i){return null;}
}