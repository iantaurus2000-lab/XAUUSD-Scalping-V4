package com.xauusd.mobileengine;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class FxOpenTickTraderClient {
    public static final String DEFAULT_DEMO_HOST="https://marginalttdemowebapi.fxopen.net:8443";
    public static final String DEFAULT_LIVE_HOST="https://ttlivewebapi.fxopen.net:8443";

    public static final class Response {
        public final int code;
        public final String body;
        Response(int c,String b){code=c;body=b==null?"":b;}
        public boolean ok(){return code>=200&&code<300;}
    }

    private final SecurityStore store;
    public FxOpenTickTraderClient(SecurityStore s){store=s;}

    String id(){return store.get("fx_id","").trim();}
    String key(){return store.get("fx_key","").trim();}
    String secret(){return store.get("fx_secret","").trim();}
    String host(){
        String h=store.get("fx_host",DEFAULT_DEMO_HOST).trim();
        if(h.isEmpty())h=DEFAULT_DEMO_HOST;
        if(!h.startsWith("http://")&&!h.startsWith("https://"))h="https://"+h;
        while(h.endsWith("/"))h=h.substring(0,h.length()-1);
        return h;
    }
    public boolean configured(){return !id().isEmpty()&&!key().isEmpty()&&!secret().isEmpty();}

    public void save(String webApiId,String webApiKey,String webApiSecret,String apiHost){
        store.put("fx_id",webApiId==null?"":webApiId.trim());
        store.put("fx_key",webApiKey==null?"":webApiKey.trim());
        store.put("fx_secret",webApiSecret==null?"":webApiSecret.trim());
        store.put("fx_host",(apiHost==null||apiHost.trim().isEmpty())?DEFAULT_DEMO_HOST:apiHost.trim());
    }

    public Response quoteHistory(String symbol,String periodicity,String priceType,long timestampMs,int count) throws Exception{
        String s=symbol==null||symbol.trim().isEmpty()?"XAUUSD":symbol.trim();
        String p=periodicity==null||periodicity.trim().isEmpty()?"M1":periodicity.trim();
        String pt=priceType==null||priceType.trim().isEmpty()?"Bid":priceType.trim();
        String path="/api/v2/quotehistory/"+URLEncoder.encode(s,"UTF-8")+"/"+URLEncoder.encode(p,"UTF-8")+"/bars/"+URLEncoder.encode(pt,"UTF-8")+"?timestamp="+timestampMs+"&count="+Math.max(-1000,Math.min(1000,count));
        return request("GET",path,"");
    }
    public Response publicQuoteHistory(String symbol,String periodicity,String priceType,long timestampMs,int count) throws Exception{
        String s=symbol==null||symbol.trim().isEmpty()?"XAUUSD":symbol.trim();
        String p=periodicity==null||periodicity.trim().isEmpty()?"M1":periodicity.trim();
        String pt=priceType==null||priceType.trim().isEmpty()?"Bid":priceType.trim();
        String path="/api/v2/public/quotehistory/"+URLEncoder.encode(s,"UTF-8")+"/"+URLEncoder.encode(p,"UTF-8")+"/bars/"+URLEncoder.encode(pt,"UTF-8")+"?timestamp="+timestampMs+"&count="+Math.max(-1000,Math.min(1000,count));
        return requestPublic(path);
    }
    public Response publicTickV2(String symbol) throws Exception{
        String s=symbol==null||symbol.trim().isEmpty()?"XAUUSD":symbol.trim();
        return requestPublic("/api/v2/public/tick/"+URLEncoder.encode(s,"UTF-8"));
    }
    private Response requestPublic(String path)throws Exception{
        URL u=new URL(host()+path); HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestMethod("GET");
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("Accept-Encoding","gzip, deflate");
        int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();String body=read(in);c.disconnect();
        return new Response(code,body);
    }

    public Response accountInfo() throws Exception { return request("GET","/api/v1/account",""); }
    public Response trades() throws Exception { return request("GET","/api/v1/trade",""); }
    public Response tradeSession() throws Exception { return request("GET","/api/v1/tradesession",""); }

    public Response placeLimit(String symbol,String side,double lot,double price,double sl,double tp,String comment) throws Exception{
        JSONObject j=new JSONObject();
        j.put("Type","Limit");
        j.put("Side","buy".equalsIgnoreCase(side)?"Buy":"Sell");
        j.put("Symbol",symbol);
        j.put("Amount",amountFromLot(symbol,lot));
        j.put("Price",price);
        if(sl>0)j.put("StopLoss",sl);
        if(tp>0)j.put("TakeProfit",tp);
        if(comment!=null&&!comment.isEmpty())j.put("Comment",comment);
        j.put("ClientId","xauusd-"+System.currentTimeMillis());
        return request("POST","/api/v1/trade",j.toString());
    }

    public Response cancel(String tradeId) throws Exception{
        if(tradeId==null||tradeId.trim().isEmpty())throw new IllegalArgumentException("Trade ID kosong");
        return request("DELETE","/api/v1/trade?type=Cancel&id="+URLEncoder.encode(tradeId,"UTF-8"),"");
    }

    public Response modify(String tradeId,double price,double sl,double tp,String comment) throws Exception{
        JSONObject j=new JSONObject();
        j.put("Id",Long.parseLong(tradeId));
        if(price>0)j.put("Price",price);
        if(sl>0)j.put("StopLoss",sl);
        if(tp>0)j.put("TakeProfit",tp);
        if(comment!=null&&!comment.isEmpty())j.put("Comment",comment);
        return request("PUT","/api/v1/trade",j.toString());
    }

    public Response close(String tradeId,String amount) throws Exception{
        String path="/api/v1/trade?type=Close&id="+URLEncoder.encode(tradeId,"UTF-8");
        if(amount!=null&&!amount.trim().isEmpty())path+="&amount="+URLEncoder.encode(amount.trim(),"UTF-8");
        return request("DELETE",path,"");
    }

    public Response publicTick(String symbol) throws Exception{
        URL u=new URL(host()+"/api/v1/public/tick/"+URLEncoder.encode(symbol,"UTF-8"));
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestMethod("GET");
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();
        InputStream in=code>=400?c.getErrorStream():c.getInputStream();
        String body=read(in);c.disconnect();
        return new Response(code,body);
    }

    private Response request(String method,String path,String body)throws Exception{
        String b=body==null?"":body;
        String absolute=host()+path;
        URL u=new URL(absolute);
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("Accept-Encoding","gzip, deflate");
        c.setRequestProperty("Content-Type","application/json");
        long ts=System.currentTimeMillis();
        String signatureText=String.valueOf(ts)+id()+key()+method+absolute+b;
        String sig=hmacBase64(secret(),signatureText);
        c.setRequestProperty("Authorization","HMAC "+id()+":"+key()+":"+ts+":"+sig);
        if("POST".equals(method)||"PUT".equals(method)){
            c.setDoOutput(true);
            try(OutputStream out=c.getOutputStream()){out.write(b.getBytes(StandardCharsets.UTF_8));}
        }
        int code=c.getResponseCode();
        InputStream in=code>=400?c.getErrorStream():c.getInputStream();
        String out=read(in);
        c.disconnect();
        return new Response(code,out);
    }

    static String hmacBase64(String secret,String message)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    static String read(InputStream in)throws Exception{
        if(in==null)return "";
        BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder();String x;
        while((x=r.readLine())!=null)b.append(x);
        r.close();return b.toString();
    }

    public static double amountFromLot(String symbol,double lot){
        if(symbol!=null){
            String s=symbol.toUpperCase();
            if(s.contains("XAUUSD"))return lot*100.0;
            if(s.contains("XAGUSD"))return lot*5000.0;
        }
        return lot*100000.0;
    }

    public String feedWsUrl(){
        String h=host();
        if(h.startsWith("https://")) h="wss://"+h.substring(8);
        else if(h.startsWith("http://")) h="ws://"+h.substring(7);
        h=h.replace("webapi","feed");
        return h;
    }

    public String feedWsUrlLiveFallback(){
        return "wss://ttlivewebapi.fxopen.com:443";
    }

    public Response wsQuoteHistory(String symbol,String periodicity,String priceType,long timestampMs,int count) throws Exception{
        org.json.JSONArray rows = wsRequestQuoteHistory(symbol,periodicity,priceType,timestampMs,count);
        return new Response(200, rows.toString());
    }

    private org.json.JSONArray wsRequestQuoteHistory(final String symbol,final String periodicity,final String priceType,final long timestampMs,final int count) throws Exception{
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> result=new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>("");
        final String requestId=java.util.UUID.randomUUID().toString();
        okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder()
                .connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15,java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build();

        okhttp3.Request req=new okhttp3.Request.Builder().url(feedWsUrl()).build();
        okhttp3.WebSocket ws=client.newWebSocket(req,new okhttp3.WebSocketListener(){
            private boolean logged=false;
            private void sendLogin(okhttp3.WebSocket s){
                try{
                    long ts=System.currentTimeMillis();
                    org.json.JSONObject p=new org.json.JSONObject();
                    p.put("AuthType","HMAC");p.put("WebApiId",id());p.put("WebApiKey",key());
                    p.put("Timestamp",ts);p.put("Signature",hmacBase64(secret(),ts+id()+key()));
                    p.put("DeviceId","XAUUSD-Mobile-Trading-Engine");
                    p.put("AppSessionId",requestId);
                    org.json.JSONObject x=new org.json.JSONObject();
                    x.put("Id",requestId);x.put("Request","Login");x.put("Params",p);
                    s.send(x.toString());
                }catch(Exception e){error.set(e.toString());done.countDown();}
            }
            public void onOpen(okhttp3.WebSocket s,okhttp3.Response r){sendLogin(s);}
            public void onMessage(okhttp3.WebSocket s,String text){
                try{
                    org.json.JSONObject o=new org.json.JSONObject(text);
                    String response=o.optString("Response","");
                    if("Login".equals(response)){
                        logged=true;
                        org.json.JSONObject p=new org.json.JSONObject();
                        p.put("Symbol",symbol);p.put("Periodicity",periodicity);p.put("PriceType",priceType);
                        p.put("Timestamp",timestampMs);p.put("Count",Math.max(-1000,Math.min(1000,count)));
                        org.json.JSONObject x=new org.json.JSONObject();
                        x.put("Id",requestId);x.put("Request","QuoteHistoryBars");x.put("Params",p);
                        s.send(x.toString());
                    }else if("QuoteHistoryBars".equals(response)){
                        org.json.JSONObject r=o.optJSONObject("Result");
                        org.json.JSONArray bars=null;
                        if(r!=null){
                            bars=r.optJSONArray("Bars");
                            if(bars==null)bars=r.optJSONArray("bars");
                            if(bars==null)bars=r.optJSONArray("Data");
                        }
                        if(bars==null && o.has("Result") && o.opt("Result") instanceof org.json.JSONArray)
                            bars=o.optJSONArray("Result");
                        if(bars==null) throw new Exception("FXOpen QuoteHistoryBars: bars kosong");
                        result.set(bars.toString());done.countDown();s.close(1000,"done");
                    }else if("Error".equals(response)){
                        error.set(o.optString("Error","FXOpen websocket error"));done.countDown();s.close(1000,"error");
                    }else if("TwoFactor".equals(response)){
                        error.set("FXOpen API membutuhkan TwoFactor");done.countDown();s.close(1000,"2fa");
                    }
                }catch(Exception e){error.set(e.toString());done.countDown();s.close(1000,"parse");}
            }
            public void onFailure(okhttp3.WebSocket s,Throwable t,okhttp3.Response r){
                error.set(t==null?"WebSocket failure":String.valueOf(t.getMessage()));done.countDown();
            }
        });
        if(!done.await(20,java.util.concurrent.TimeUnit.SECONDS)){ws.cancel();throw new IOException("FXOpen WebSocket timeout");}
        client.dispatcher().executorService().shutdown();
        if(result.get().isEmpty())throw new IOException(error.get().isEmpty()?"FXOpen WebSocket no data":error.get());
        return new org.json.JSONArray(result.get());
    }

    public String wsFeedProbe() throws Exception{
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> result=new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>("");
        final String rid=java.util.UUID.randomUUID().toString();
        okhttp3.OkHttpClient client=new okhttp3.OkHttpClient();
        okhttp3.Request req=new okhttp3.Request.Builder().url(feedWsUrl()).build();
        okhttp3.WebSocket ws=client.newWebSocket(req,new okhttp3.WebSocketListener(){
            public void onOpen(okhttp3.WebSocket s,okhttp3.Response r){
                try{
                    long ts=System.currentTimeMillis();org.json.JSONObject p=new org.json.JSONObject();
                    p.put("AuthType","HMAC");p.put("WebApiId",id());p.put("WebApiKey",key());p.put("Timestamp",ts);
                    p.put("Signature",hmacBase64(secret(),ts+id()+key()));p.put("DeviceId","XAUUSD-Mobile-Trading-Engine");p.put("AppSessionId",rid);
                    org.json.JSONObject x=new org.json.JSONObject();x.put("Id",rid);x.put("Request","Login");x.put("Params",p);s.send(x.toString());
                }catch(Exception e){error.set(e.toString());done.countDown();}
            }
            public void onMessage(okhttp3.WebSocket s,String text){
                try{
                    org.json.JSONObject o=new org.json.JSONObject(text);String rr=o.optString("Response","");
                    if("Login".equals(rr)){
                        org.json.JSONObject p=new org.json.JSONObject();p.put("Subscribe",new org.json.JSONArray().put(new org.json.JSONObject().put("Symbol","XAUUSD").put("BookDepth",1).put("FrequencyPriority",0)));
                        org.json.JSONObject x=new org.json.JSONObject();x.put("Id",rid);x.put("Request","FeedSubscribe");x.put("Params",p);s.send(x.toString());
                    }else if("FeedSubscribe".equals(rr)||"FeedTick".equals(rr)){result.set(text);done.countDown();s.close(1000,"probe");}
                    else if("Error".equals(rr)){error.set(o.optString("Error","FXOpen feed error"));done.countDown();s.close(1000,"error");}
                }catch(Exception e){error.set(e.toString());done.countDown();s.close(1000,"parse");}
            }
            public void onFailure(okhttp3.WebSocket s,Throwable t,okhttp3.Response r){error.set(String.valueOf(t));done.countDown();}
        });
        if(!done.await(15,java.util.concurrent.TimeUnit.SECONDS)){ws.cancel();throw new IOException("FXOpen feed timeout");}
        client.dispatcher().executorService().shutdown();
        if(result.get().isEmpty())throw new IOException(error.get().isEmpty()?"FXOpen feed no response":error.get());
        return result.get();
    }

}
