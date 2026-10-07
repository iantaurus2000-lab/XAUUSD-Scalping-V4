package com.xauusd.mobileengine;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class GatewayClient {
    public static final class Response {
        public final int code; public final String body;
        Response(int c,String b){code=c;body=b==null?"":b;}
        public boolean ok(){return code>=200&&code<300;}
    }
    private final SecurityStore store;
    public GatewayClient(SecurityStore s){store=s;}
    public boolean configured(){return !store.get("gateway_host","").trim().isEmpty();}
    private String base(){
        String s=store.get("gateway_host","").trim();
        while(s.endsWith("/"))s=s.substring(0,s.length()-1);
        return s;
    }
    public Response health()throws Exception{return request("GET","/health",null);}
    public Response status()throws Exception{return request("GET","/status",null);}
    public Response placeLimit(String symbol,String side,double volume,double entry,double sl,double tp,String signal)throws Exception{
        JSONObject j=new JSONObject()
            .put("action",side.equalsIgnoreCase("buy")?"BUY_LIMIT":"SELL_LIMIT")
            .put("symbol",symbol).put("volume",volume).put("entry",entry)
            .put("sl",sl).put("tp",tp).put("signal",signal);
        return request("POST","/command",j.toString());
    }
    public Response cancel(String id,String signal)throws Exception{
        JSONObject j=new JSONObject().put("action","CANCEL").put("id",id).put("signal",signal);
        return request("POST","/command",j.toString());
    }
    private Response request(String method,String path,String body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(base()+path).openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(8000);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");
        String token=store.get("gateway_token","");
        if(!token.isEmpty())c.setRequestProperty("X-Gateway-Token",token);
        if(body!=null){
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Length",String.valueOf(b.length));
            try(OutputStream out=c.getOutputStream()){out.write(b);}
        }
        int code;
        try{code=c.getResponseCode();}catch(Exception e){code=599;}
        InputStream in=code>=400?c.getErrorStream():c.getInputStream();
        StringBuilder sb=new StringBuilder();
        if(in!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String line;while((line=r.readLine())!=null)sb.append(line);
        }
        c.disconnect();return new Response(code,sb.toString());
    }
}
