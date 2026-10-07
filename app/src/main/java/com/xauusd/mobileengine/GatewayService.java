package com.xauusd.mobileengine;
import android.app.*; import android.content.*; import android.os.*; import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets; import java.util.concurrent.*;
public final class GatewayService extends Service {
 private ServerSocket server; private ExecutorService pool;
 public int onStartCommand(Intent i,int f,int id){startForeground(77,notification()); startServer(); return START_STICKY;}
 void startServer(){if(pool!=null)return; pool=Executors.newSingleThreadExecutor(); pool.execute(()->{try{server=new ServerSocket(8787); while(!server.isClosed())handle(server.accept());}catch(Exception ignored){}});}
 void handle(Socket s){try{BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream())); String line=r.readLine(); while(line!=null&&!line.isEmpty())line=r.readLine(); String body=""; if(line!=null){} String path=first(line);
  String out=path.equals("/next")?getPending(): "Rayyan4 gateway OK";
  OutputStream o=s.getOutputStream(); byte[] b=out.getBytes(StandardCharsets.UTF_8); String h="HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n"; o.write(h.getBytes(StandardCharsets.UTF_8));o.write(b);o.flush();s.close();
 }catch(Exception ignored){}}
 String first(String l){if(l==null)return "";String[] a=l.split(" ");return a.length>1?a[1]:"";}
 synchronized String getPending(){return getSharedPreferences("rayyan4_gateway",0).getString("next","");}
 Notification notification(){String id="rayyan4_gateway"; NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE); if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(id,"MT5 Gateway",NotificationManager.IMPORTANCE_LOW)); return new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_menu_upload).setContentTitle("Rayyan4 MT5 Gateway").setContentText("LAN gateway aktif").build();}
 public void onDestroy(){try{if(server!=null)server.close();}catch(Exception e){}if(pool!=null)pool.shutdownNow();super.onDestroy();}
 public android.os.IBinder onBind(Intent i){return null;}
}