package com.xauusd.tradinggateway;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.*;

public class MrpcClient {
    static final String BASE="https://mt5.mrpc.pro";
    final SharedPreferences p;
    MrpcClient(Context c){p=c.getSharedPreferences("gateway",Context.MODE_PRIVATE);}
    boolean configured(){return !p.getString("mrpc_api_key","").isEmpty()&&!p.getString("mrpc_account","").isEmpty()&&!p.getString("mrpc_password","").isEmpty()&&!p.getString("mrpc_cluster","").isEmpty();}
    String apiKey(){return p.getString("mrpc_api_key","");}
    String id(){String x=p.getString("mrpc_id",""); if(x.isEmpty()){x=UUID.randomUUID().toString();p.edit().putString("mrpc_id",x).apply();} return x;}
    String q(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    String request(String method,String path,String body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("APIKey",apiKey());c.setRequestProperty("id",id());
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] b=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream o=c.getOutputStream()){o.write(b);}}
        int code=c.getResponseCode();InputStream is=code>=400?c.getErrorStream():c.getInputStream();StringBuilder z=new StringBuilder();
        if(is!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){String s;while((s=r.readLine())!=null)z.append(s);}
        c.disconnect();return code+"|"+z;
    }
    String connect()throws Exception{
        String path="/ConnectEx?user="+q(p.getString("mrpc_account",""))+"&password="+q(p.getString("mrpc_password",""))+"&mtClusterName="+q(p.getString("mrpc_cluster",""))+"&baseChartSymbol="+q(p.getString("symbol","XAUUSD"))+"&timeoutSeconds=120";
        String r=request("GET",path,null); if(r.startsWith("200|")){p.edit().putBoolean("mrpc_connected",true).apply();} return r;
    }
    String account()throws Exception{return request("GET","/AccountSummary",null);}
    String orders()throws Exception{return request("GET","/OpenedOrders",null);}
    String positions()throws Exception{return request("GET","/PositionsTotal",null);}
    String placeLimit(String symbol,String side,double volume,double price,double sl,double tp,String comment)throws Exception{
        int type="buy".equalsIgnoreCase(side)?2:3;
        String path="/OrderSend?symbol="+q(symbol)+"&operation="+type+"&volume="+volume+"&price="+price+"&slippage=0&stoploss="+sl+"&takeprofit="+tp+"&comment="+q(comment)+"&expert_id=520234";
        return request("GET",path,null);
    }
    String disconnect()throws Exception{return request("GET","/Disconnect",null);}
}