package com.xauusd.tradinggateway;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class MetaKitClient {
    static final String DEFAULT_BASE="https://api.metakit.cloud";
    final SharedPreferences p;
    public MetaKitClient(Context c){p=c.getSharedPreferences("gateway",Context.MODE_PRIVATE);}
    public boolean configured(){return !p.getString("metakit_key","").isEmpty()&&!p.getString("metakit_account","").isEmpty();}
    public void save(String key,String account,String symbol){p.edit().putString("metakit_key",key).putString("metakit_account",account).putString("symbol",symbol).apply();}
    public String account(){return p.getString("metakit_account","");}
    public String symbol(){return p.getString("symbol","XAUUSD");}
    public String base(){return DEFAULT_BASE;}
    public String request(String method,String path,String body,String idem)throws Exception{
        URL u=new URL(base()+path); HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setRequestMethod(method);
        c.setRequestProperty("Authorization","Bearer "+p.getString("metakit_key",""));
        c.setRequestProperty("Accept","application/json"); c.setRequestProperty("Content-Type","application/json");
        if(idem!=null&&!idem.isEmpty())c.setRequestProperty("Idempotency-Key",idem);
        if(body!=null){c.setDoOutput(true);byte[] b=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);c.getOutputStream().write(b);}
        int code=c.getResponseCode(); InputStream is=code>=400?c.getErrorStream():c.getInputStream();
        String out=""; if(is!=null){BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));String s;StringBuilder z=new StringBuilder();while((s=r.readLine())!=null)z.append(s);r.close();out=z.toString();}
        c.disconnect(); return code+"|"+out;
    }
    public String accountInfo()throws Exception{return request("GET","/v1/accounts/"+account(),null,null);}
    public String orders()throws Exception{return request("GET","/v1/accounts/"+account()+"/orders",null,null);}
    public String positions()throws Exception{return request("GET","/v1/accounts/"+account()+"/positions",null,null);}
    public String placeLimit(String side,double volume,double price,double sl,double tp,String signal)throws Exception{
        JSONObject j=new JSONObject();j.put("symbol",symbol());j.put("side",side);j.put("type","limit");j.put("volume",volume);j.put("price",price);j.put("sl",sl);j.put("tp",tp);j.put("comment",signal.length()>31?signal.substring(0,31):signal);
        return request("POST","/v1/accounts/"+account()+"/orders",j.toString(),signal);
    }
    public String cancel(String ticket,String signal)throws Exception{return request("DELETE","/v1/accounts/"+account()+"/orders/"+ticket,null,signal);}
}