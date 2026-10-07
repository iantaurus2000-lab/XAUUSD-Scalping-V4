package com.xauusd.tradinggateway;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.graphics.Color;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.*;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {
    TextView status, address;
    MrpcClient mrpc;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mrpc = new MrpcClient(this);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(24,24,24,24);

        TextView title = new TextView(this);
        title.setText("XAUUSD Mini Trading Server\nHP B • LAN Gateway");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        r.addView(title);

        address = new TextView(this);
        address.setTextSize(14);
        address.setPadding(0,14,0,10);
        r.addView(address);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0,8,0,14);
        r.addView(status);

        Button bridge = new Button(this);
        bridge.setText("☁ MT5 BRIDGE • MRPC");
        bridge.setOnClickListener(v -> bridgeConfig());
        r.addView(bridge);

        Button token = new Button(this);
        token.setText("⚙ SET HP A GATEWAY TOKEN");
        token.setOnClickListener(v -> config());
        r.addView(token);

        Button start = new Button(this);
        start.setText("START SERVER");
        start.setOnClickListener(v -> { startForegroundService(new Intent(this,GatewayService.class)); refresh(); });
        r.addView(start);

        Button stop = new Button(this);
        stop.setText("STOP SERVER");
        stop.setOnClickListener(v -> { stopService(new Intent(this,GatewayService.class)); refresh(); });
        r.addView(stop);

        Button statusBtn = new Button(this);
        statusBtn.setText("SERVER STATUS");
        statusBtn.setOnClickListener(v -> showStatus());
        r.addView(statusBtn);

        TextView note = new TextView(this);
        note.setText("\nJalur: HP A → Wi‑Fi/LAN → HP B → MRPC MT5 → Exness MT5 Demo\n\nMRPC connector: APIKey MRPC + akun MT5 demo.");
        note.setTextSize(13);
        r.addView(note);

        setContentView(r);
        refresh();
    }

    void bridgeConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18,8,18,8);
        android.content.SharedPreferences p = getSharedPreferences("gateway",0);

        EditText key = field("MRPC APIKey",p.getString("mrpc_api_key",""),false);
        EditText acc = field("MT5 Account ID",p.getString("mrpc_account",""),false);
        EditText pass = field("MT5 Trading Password",p.getString("mrpc_password",""),true);
        EditText cluster = field("MT5 Cluster / Server",p.getString("mrpc_cluster",""),false);
        EditText sym = field("MT5 Symbol",p.getString("symbol","XAUUSD"),false);
        box.addView(key); box.addView(acc); box.addView(pass); box.addView(cluster); box.addView(sym);

        new android.app.AlertDialog.Builder(this)
            .setTitle("MT5 BRIDGE • MRPC")
            .setMessage("HP B → MRPC MT5 → Exness MT5 Demo")
            .setView(box).setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE + TEST",(d,w)->{
                p.edit().putString("mrpc_api_key",key.getText().toString().trim())
                    .putString("mrpc_account",acc.getText().toString().trim())
                    .putString("mrpc_password",pass.getText().toString())
                    .putString("mrpc_cluster",cluster.getText().toString().trim())
                    .putString("symbol",sym.getText().toString().trim().isEmpty()?"XAUUSD":sym.getText().toString().trim()).apply();
                new Thread(()->{
                    try {
                        String a=mrpc.connect(), c=mrpc.account();
                        p.edit().putString("mrpc_last_test",a+"\n"+c).apply();
                        runOnUiThread(()->status.setText("MRPC TEST: "+(a.startsWith("200|")?"CONNECTED":"ERROR")+"\n"+trim(a)+"\nACCOUNT\n"+trim(c)));
                    } catch(Exception e) { runOnUiThread(()->status.setText("MRPC ERROR: "+e.getMessage())); }
                }).start();
            }).show();
    }

    EditText field(String hint,String value,boolean password) {
        EditText e=new EditText(this);
        e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setPadding(12,10,12,10);
        if(password) e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,6,0,6); e.setLayoutParams(lp);
        return e;
    }

    String trim(String x) { return x==null?"":(x.length()>900?x.substring(0,900)+"…":x); }

    void config() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(18,8,18,8);
        android.content.SharedPreferences p=getSharedPreferences("gateway",0);
        EditText tok=field("HP A Gateway Token",p.getString("gateway_token",""),true); tok.setTextSize(17);
        EditText sym=field("MT5 Symbol",p.getString("symbol","XAUUSD"),false); sym.setTextSize(17);
        box.addView(tok); box.addView(sym);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(box);

        new android.app.AlertDialog.Builder(this).setTitle("HP B • SET HP A GATEWAY")
            .setMessage("Token harus SAMA dengan HP A. Bukan MRPC APIKey.")
            .setView(scroll).setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE",(d,w)->{
                p.edit().putString("gateway_token",tok.getText().toString().trim())
                    .putString("symbol",sym.getText().toString().trim().isEmpty()?"XAUUSD":sym.getText().toString().trim()).apply();
                refresh();
            }).show();
    }

    void refresh() {
        android.content.SharedPreferences p=getSharedPreferences("gateway",0);
        boolean cfg=!p.getString("gateway_token","").trim().isEmpty();
        address.setText("LAN: http://"+localIp()+":8787");
        status.setText("STATUS: "+(cfg?"READY":"SET TOKEN")+"\nSERVER: 8787\nMODE: HP B MINI SERVER");
    }

    void showStatus() {
        android.content.SharedPreferences p=getSharedPreferences("gateway",0);
        String last=p.getString("last_command","");
        new android.app.AlertDialog.Builder(this).setTitle("HP B SERVER STATUS")
            .setMessage("LAN: http://"+localIp()+":8787\nToken: "+(p.getString("gateway_token","").isEmpty()?"NOT SET":"SET")+"\n\nLast command:\n"+(last.isEmpty()?"--":last))
            .setPositiveButton("OK",null).show();
    }

    String localIp() {
        try {
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))
                for(java.net.InetAddress a:Collections.list(ni.getInetAddresses()))
                    if(a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String s=a.getHostAddress(); if(s!=null&&!s.startsWith("127.")) return s;
                    }
        } catch(Exception ignored) {}
        return "0.0.0.0";
    }
}
