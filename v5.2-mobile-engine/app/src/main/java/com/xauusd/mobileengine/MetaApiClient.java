package com.xauusd.mobileengine;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MetaApiClient {
    public static final String DEFAULT_HOST="https://mt-client-api-v1.new-york.agiliumtrade.ai";
    public static final class Response {
        public final int code; public final String body;
        Response(int c,String b){code=c;body=b;}
        public boolean ok(){return code>=200&&code<300;}
    }
    private final SecurityStore store;
    public MetaApiClient(SecurityStore s){store=s;}
    String token(){return store.get("meta_token","").trim();}
    String accountId(){return store.get("meta_account","").trim();}
    String host(){String h=store.get("meta_host",DEFAULT_HOST).trim();return h.isEmpty()?DEFAULT_HOST:normalize(h);}
    public boolean configured(){return !token().isEmpty()&&!accountId().isEmpty();}
    public Response accountInfo() throws Exception{return request("GET","/users/current/accounts/"+accountId()+"/account-information","");}
    public Response orders() throws Exception{return request("GET","/users/current/accounts/"+accountId()+"/orders","");}
    public Response placeLimit(String symbol,String side,double volume,double price,double sl,double tp,String comment) throws Exception{
        JSONObject j=new JSONObject();
        j.put("actionType","buy".equalsIgnoreCase(side)?"ORDER_TYPE_BUY_LIMIT":"ORDER_TYPE_SELL_LIMIT");
        j.put("symbol",symbol); j.put("volume",volume); j.put("openPrice",price);
        if(sl>0)j.put("stopLoss",sl); if(tp>0)j.put("takeProfit",tp);
        j.put("comment",comment==null?"XAUUSD-V5.2":comment);
        j.put("clientId","xauusd-"+UUID.randomUUID().toString().replace("-","").substring(0,20));
        return request("POST","/users/current/accounts/"+accountId()+"/trade",j.toString());
    }
    public Response cancel(String orderId) throws Exception{
        JSONObject j=new JSONObject();j.put("actionType","ORDER_CANCEL");j.put("orderId",orderId);
        return request("POST","/users/current/accounts/"+accountId()+"/trade",j.toString());
    }
    private Response request(String method,String path,String body)throws Exception{
        URL u=URI.create(host()+path).toURL();HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("auth-token",token());
        if("POST".equals(method)){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();String out=read(in);c.disconnect();return new Response(code,out);
    }
    static String read(InputStream in)throws Exception{if(in==null)return "";BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String x;while((x=r.readLine())!=null)b.append(x);r.close();return b.toString();}
    static String normalize(String h){if(!h.startsWith("http://")&&!h.startsWith("https://"))h="https://"+h;while(h.endsWith("/"))h=h.substring(0,h.length()-1);return h;}
}
