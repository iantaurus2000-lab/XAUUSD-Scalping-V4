package com.xauusd.tradinggateway;
import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.os.Bundle;import android.graphics.Color;import android.widget.*;
public class MainActivity extends Activity{
 TextView status; MetaKitClient meta;
 @Override protected void onCreate(Bundle b){super.onCreate(b);meta=new MetaKitClient(this);if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
 LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(28,28,28,28);
 TextView t=new TextView(this);t.setText("XAUUSD Trading Gateway\nV4 • MT5 Cloud Connector");t.setTextSize(22);r.addView(t);
 status=new TextView(this);status.setTextSize(16);r.addView(status);refresh();
 Button cfg=new Button(this);cfg.setText("⚙ CONFIG MT5 CLOUD");cfg.setOnClickListener(v->config());r.addView(cfg);
 Button start=new Button(this);start.setText("START GATEWAY");start.setOnClickListener(v->{startForegroundService(new Intent(this,GatewayService.class));refresh();});r.addView(start);
 Button stop=new Button(this);stop.setText("STOP GATEWAY");stop.setOnClickListener(v->{stopService(new Intent(this,GatewayService.class));refresh();});r.addView(stop);setContentView(r);}
 void refresh(){status.setText("\nSTATUS: "+(meta.configured()?"READY":"NOT CONFIGURED")+"\nAPI: LAN :8787\nConnector: MetaKit → Exness MT5");}
 void config(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(18,4,18,4);EditText key=new EditText(this);key.setHint("MetaKit full API key (stk_live_...)");key.setText(meta.p.getString("metakit_key",""));key.setInputType(129);EditText acc=new EditText(this);acc.setHint("MetaKit Account ID");acc.setText(meta.p.getString("metakit_account",""));EditText sym=new EditText(this);sym.setHint("MT5 symbol");sym.setText(meta.symbol());b.addView(key);b.addView(acc);b.addView(sym);
 new AlertDialog.Builder(this).setTitle("MT5 CLOUD • METAKIT").setMessage("Hubungkan akun Exness MT5 demo yang sudah connected di MetaKit. Gunakan API key scope FULL untuk order.").setView(b).setNegativeButton("CANCEL",null).setPositiveButton("SAVE + TEST",(d,w)->{meta.save(key.getText().toString().trim(),acc.getText().toString().trim(),sym.getText().toString().trim().isEmpty()?"XAUUSD":sym.getText().toString().trim());new Thread(()->{try{String a=meta.accountInfo();runOnUiThread(()->new AlertDialog.Builder(this).setTitle("RESULT").setMessage(a).setPositiveButton("OK",null).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}}).start();refresh();}).show();}
}