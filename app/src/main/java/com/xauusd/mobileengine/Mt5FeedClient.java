package com.xauusd.mobileengine;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Mt5FeedClient {
    public static final class Snapshot {
        public final String symbol;
        public final double bid, ask;
        public final int digits;
        public final long serverTime;
        public final Candle[] m1, m5, m15, h1, h4, d1;
        Snapshot(String s,double b,double a,int d,long t,Candle[] one,Candle[] five){
            symbol=s;bid=b;ask=a;digits=d;serverTime=t;m1=one;m5=five;
        }
    }
    public static Snapshot fetch() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8787/feed").openConnection();
        c.setConnectTimeout(1200);c.setReadTimeout(1800);
        try{
            int code=c.getResponseCode();
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            StringBuilder b=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                String s;while((s=r.readLine())!=null)b.append(s);
            }
            if(code!=200)throw new IOException("MT5 feed HTTP "+code);
            JSONObject j=new JSONObject(b.toString());
            if(!j.optBoolean("ok",false))throw new IOException(j.optString("error","MT5 feed not ready"));
            String symbol=j.getString("symbol");
            return new Snapshot(symbol,j.getDouble("bid"),j.getDouble("ask"),j.optInt("digits",2),
                j.optLong("serverTime"),bars(j.getJSONArray("m1")),bars(j.getJSONArray("m5")),bars(j.getJSONArray("m15")),bars(j.getJSONArray("h1")),bars(j.getJSONArray("h4")),bars(j.getJSONArray("d1")));
        }finally{c.disconnect();}
    }
    private static Candle[] bars(JSONArray a)throws Exception{
        Candle[] out=new Candle[a.length()];
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);
            out[i]=new Candle(x.getLong("t")*1000L,x.getDouble("o"),x.getDouble("h"),x.getDouble("l"),x.getDouble("c"));
        }
        return out;
    }
    public static Candle[] barsFor(Snapshot s,String tf){
        if("M5".equals(tf))return s.m5;
        if("M15".equals(tf))return s.m15;
        if("H1".equals(tf))return s.h1;
        if("H4".equals(tf))return s.h4;
        if("D1".equals(tf))return s.d1;
        return s.m1;
    }
    public static String expectedSymbol(String market){
        String m=market==null?"XAUUSD":market.trim().toUpperCase(Locale.US);
        if("XAUUSD".equals(m))return "XAUUSDm";
        if("BTCUSD".equals(m))return "BTCUSDm";
        if("ETHUSD".equals(m))return "ETHUSDm";
        return m;
    }
}
