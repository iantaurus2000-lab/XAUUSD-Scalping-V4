package com.xauusd.tradinggateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class GatewayService extends Service {
    private static final String CHANNEL_ID="gateway_status";
    private static final int PORT=8787;
    private volatile boolean running=false;
    private ServerSocket serverSocket;
    private volatile String lastError="";
    private volatile long startedAt=0L;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null)nm.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID,"Gateway Status",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        startForeground(1001,notification());
        startServer();
        startedAt=System.currentTimeMillis();
        getSharedPreferences("gateway",0).edit().putBoolean("server_running",true).putString("last_error","").apply();
        return START_STICKY;
    }

    private Notification notification(){
        return new Notification.Builder(this,CHANNEL_ID)
            .setContentTitle("XAUUSD Mini Trading Server")
            .setContentText("HP B ACTIVE • LAN :8787")
            .setSmallIcon(com.xauusd.tradinggateway.R.drawable.ic_gateway_foreground)
            .setOngoing(true).build();
    }

    private synchronized void startServer(){
        if(running)return;
        running=true;
        new Thread(()->{
            try{
                serverSocket=new ServerSocket(PORT,50,InetAddress.getByName("0.0.0.0"));
                while(running){
                    Socket s=serverSocket.accept();
                    new Thread(()->handle(s),"gateway-client").start();
                }
            }catch(Exception e){
                lastError=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
                getSharedPreferences("gateway",0).edit().putString("last_error",lastError).putBoolean("server_running",false).apply();
            }
            finally{running=false;closeServer();}
        },"gateway-api").start();
    }

    private void closeServer(){
        try{if(serverSocket!=null)serverSocket.close();}catch(Exception ignored){}
        serverSocket=null;
    }

    private void handle(Socket socket){
        try(Socket s=socket){
            s.setSoTimeout(10000);
            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            OutputStream out=s.getOutputStream();

            String request=in.readLine();
            if(request==null)return;

            int contentLength=0;
            String auth="";
            String line;
            while((line=in.readLine())!=null && !line.isEmpty()){
                String low=line.toLowerCase(java.util.Locale.US);
                if(low.startsWith("content-length:")){
                    try{contentLength=Integer.parseInt(line.substring(15).trim());}catch(Exception ignored){}
                }
                if(low.startsWith("x-gateway-token:"))auth=line.substring(line.indexOf(":")+1).trim();
            }

            String body="";
            if(contentLength>0){
                char[] buf=new char[contentLength];
                int n=0,read;
                while(n<contentLength&&(read=in.read(buf,n,contentLength-n))>0)n+=read;
                body=new String(buf,0,n);
            }

            JSONObject result;
            String expected=getSharedPreferences("gateway",0).getString("gateway_token","");
            if(!expected.isEmpty() && !expected.equals(auth)){
                result=new JSONObject().put("ok",false).put("error","unauthorized");
            }else{
                result=route(request,body);
            }

            String json=result.toString();
            String resp="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                +json.getBytes(StandardCharsets.UTF_8).length+"\r\nConnection: close\r\n\r\n"+json;
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }catch(Exception e){
            lastError=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
            getSharedPreferences("gateway",0).edit().putString("last_error",lastError).apply();
        }
    }

    private JSONObject route(String request,String body)throws Exception{
        String path=request.split(" ")[1];

        if(path.equals("/health")||path.equals("/status")){
            JSONObject r=new JSONObject()
                .put("ok",true)
                .put("service","xauusd-mini-trading-server")
                .put("version","5.0")
                .put("connector","LOCAL_HP_B")
                .put("execution","MT5_CONNECTOR_PENDING")
                .put("serverRunning",running)
                .put("port",PORT)
                .put("lastError",getSharedPreferences("gateway",0).getString("last_error",""));
            String last=getSharedPreferences("gateway",0).getString("last_command","");
            if(!last.isEmpty())r.put("lastCommand",new JSONObject(last));
            return r;
        }

        if(!path.equals("/command"))
            return new JSONObject().put("ok",false).put("error","not_found");

        JSONObject j=body==null||body.trim().isEmpty()?new JSONObject():new JSONObject(body);
        String action=j.optString("action","").toUpperCase(java.util.Locale.US);
        if(action.isEmpty())return new JSONObject().put("ok",false).put("error","action_required");

        if("STATUS".equals(action)||"HEALTH".equals(action))
            return route("GET /status","");

        if("BUY_LIMIT".equals(action)||"SELL_LIMIT".equals(action)||"CANCEL".equals(action)
                ||"ORDERS".equals(action)||"POSITIONS".equals(action)){
            getSharedPreferences("gateway",0).edit().putString("last_command",j.toString()).apply();
            return new JSONObject().put("ok",true)
                .put("accepted",true)
                .put("action",action)
                .put("message","Command accepted by HP B mini server")
                .put("execution","MT5_CONNECTOR_PENDING");
        }

        return new JSONObject().put("ok",false).put("error","unsupported_action").put("action",action);
    }

    @Override public void onDestroy(){
        running=false;
        getSharedPreferences("gateway",0).edit().putBoolean("server_running",false).apply();
        closeServer();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
