package com.xauusd.tradinggateway;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class GatewayService extends Service {
    private static final String CHANNEL_ID="gateway_status";
    private static final int PORT=8787;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final AtomicReference<String> lastCommand=new AtomicReference<>("");

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,"Gateway Status",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        startForeground(1001,notification()); startServer(); return START_STICKY;
    }
    private Notification notification(){
        return new Notification.Builder(this,CHANNEL_ID).setContentTitle("XAUUSD Trading Gateway V3")
          .setContentText("Gateway ACTIVE • API :8787").setSmallIcon(com.xauusd.tradinggateway.R.drawable.ic_gateway_foreground)
          .setOngoing(true).build();
    }
    private synchronized void startServer(){
        if(running)return; running=true;
        new Thread(()->{
            try{
                serverSocket=new ServerSocket(PORT);
                while(running){ Socket s=serverSocket.accept(); handle(s); }
            }catch(Exception ignored){} finally{running=false;closeServer();}
        },"gateway-local-api").start();
    }
    private void handle(Socket socket){
        try(Socket s=socket; BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8)); OutputStream out=s.getOutputStream()){
            String request=in.readLine(); if(request==null)return;
            int contentLength=0; String line;
            while((line=in.readLine())!=null&&!line.isEmpty()){
                if(line.toLowerCase().startsWith("content-length:")) try{contentLength=Integer.parseInt(line.substring(15).trim());}catch(Exception ignored){}
            }
            String bodyIn="";
            if(contentLength>0){char[] buf=new char[contentLength];int n=in.read(buf);if(n>0)bodyIn=new String(buf,0,n);}
            String body; int code;
            if(request.startsWith("GET /health")){body="{\"ok\":true,\"service\":\"xauusd-trading-gateway\",\"version\":\"3.0.0\",\"running\":true}";code=200;}
            else if(request.startsWith("GET /status")){body="{\"ok\":true,\"gateway\":\"active\",\"port\":8787,\"lastCommand\":"+json(lastCommand.get())+"}";code=200;}
            else if(request.startsWith("GET /last-command")){body="{\"ok\":true,\"command\":"+json(lastCommand.get())+"}";code=200;}
            else if(request.startsWith("POST /command")){lastCommand.set(bodyIn);body="{\"ok\":true,\"accepted\":true}";code=200;}
            else {body="{\"ok\":false,\"error\":\"not_found\"}";code=404;}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            String h="HTTP/1.1 "+(code==200?"200 OK":"404 Not Found")+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
        }catch(Exception ignored){}
    }
    private String json(String s){return s==null||s.isEmpty()?"null":"\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","").replace("\n","\\n")+"\"";}
    @Override public void onDestroy(){running=false;closeServer();super.onDestroy();}
    private void closeServer(){try{if(serverSocket!=null)serverSocket.close();}catch(Exception ignored){}serverSocket=null;}
    @Override public IBinder onBind(Intent intent){return null;}
}