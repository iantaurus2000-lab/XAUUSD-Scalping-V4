package com.rayyan4.pingtest;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.Locale;

public class MainActivity extends Activity {
  EditText host, port;
  TextView log;
  ExecutorService worker = Executors.newSingleThreadExecutor();
  LinearLayout root;
  void addLog(String s) { runOnUiThread(() -> log.append(s + "\n")); }
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    root = new LinearLayout(this); root.setOrientation(1); root.setPadding(18,18,18,18); root.setBackgroundColor(Color.rgb(5,16,28));
    TextView title = new TextView(this); title.setText("RAYYAN4 • CONNECTION TEST"); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setGravity(Gravity.CENTER); root.addView(title);
    TextView note = new TextView(this); note.setText("Uji jaringan saja — tidak mengirim order."); note.setTextColor(Color.LTGRAY); note.setPadding(0,8,0,12); root.addView(note);
    host = new EditText(this); host.setSingleLine(true); host.setText("192.168.0.109"); host.setHint("IP Gateway HP Oppo"); host.setTextColor(Color.WHITE); host.setHintTextColor(Color.GRAY); root.addView(host);
    port = new EditText(this); port.setSingleLine(true); port.setInputType(2); port.setText("8787"); port.setTextColor(Color.WHITE); root.addView(port);
    Button ping = button("1. TEST PORT (TCP)"); root.addView(ping); ping.setOnClickListener(v -> testPort());
    Button http = button("2. TEST HTTP GATEWAY"); root.addView(http); http.setOnClickListener(v -> testHttp());
    Button clear = button("BERSIHKAN LOG"); root.addView(clear); clear.setOnClickListener(v -> log.setText(""));
    ScrollView sc = new ScrollView(this); log = new TextView(this); log.setTextColor(Color.rgb(0,235,155)); log.setTextSize(13); log.setPadding(8,12,8,12); sc.addView(log); root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root); addLog("Siap. Pastikan kedua HP pada Wi-Fi yang sama."); addLog("Target awal: " + host.getText() + ":" + port.getText());
  }
  Button button(String s) { Button b=new Button(this); b.setText(s); b.setAllCaps(false); return b; }
  String target(){ return host.getText().toString().trim(); }
  int portNum(){ try { int p=Integer.parseInt(port.getText().toString().trim()); return p>0&&p<65536?p:-1; } catch(Exception e){return -1;} }
  void testPort() {
    final String h=target(); final int p=portNum();
    if(h.isEmpty()||p<1){addLog("INPUT ERROR: IP/port tidak valid");return;}
    addLog("TCP TEST mulai: "+h+":"+p);
    worker.execute(() -> { long t=System.currentTimeMillis(); try(Socket s=new Socket()){s.connect(new InetSocketAddress(h,p),3500); addLog("TCP PASS • port terbuka • "+(System.currentTimeMillis()-t)+" ms");} catch(Exception e){addLog("TCP FAIL • "+e.getClass().getSimpleName()+": "+e.getMessage());}});
  }
  void testHttp() {
    final String h=target(); final int p=portNum();
    if(h.isEmpty()||p<1){addLog("INPUT ERROR: IP/port tidak valid");return;}
    addLog("HTTP TEST mulai: http://"+h+":"+p+"/");
    worker.execute(() -> { HttpURLConnection c=null; long t=System.currentTimeMillis(); try { URL u=new URL("http://"+h+":"+p+"/"); c=(HttpURLConnection)u.openConnection(); c.setConnectTimeout(3500); c.setReadTimeout(3500); c.setRequestMethod("GET"); int code=c.getResponseCode(); addLog("HTTP RESPONSE "+code+" • "+(System.currentTimeMillis()-t)+" ms"); InputStream in=(code>=400?c.getErrorStream():c.getInputStream()); if(in!=null){BufferedReader r=new BufferedReader(new InputStreamReader(in)); String line; int n=0; while((line=r.readLine())!=null&&n++<4)addLog(line);} } catch(Exception e){addLog("HTTP FAIL • "+e.getClass().getSimpleName()+": "+e.getMessage());} finally {if(c!=null)c.disconnect();}});
  }
  @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
