package com.xauusd.mobileengine;

import org.json.*;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.Locale;

public class BiquoteClient {
    public static Candle[] candles() throws Exception { return candles("XAUUSD","1m"); }
    public static Candle[] candles(String interval) throws Exception { return candles("XAUUSD",interval); }

    public static Candle[] candles(String market,String interval) throws Exception {
        String symbol=normalizeMarket(market);
        String tf=(interval==null||interval.isEmpty())?"1m":interval;
        return parse(get("https://biquote.io/api/"+symbol+"/ohlc?interval="+tf+"&limit=120"));
    }

    public static double[] tick() throws Exception { return tick("XAUUSD"); }
    public static double[] tick(String market) throws Exception {
        String symbol=normalizeMarket(market);
        JSONObject j=new JSONObject(get("https://biquote.io/api/"+symbol));
        return new double[]{j.getDouble("bid"),j.getDouble("ask")};
    }

    private static String normalizeMarket(String market) throws Exception {
        String s=market==null?"XAUUSD":market.trim().toUpperCase(Locale.US);
        if(!s.equals("XAUUSD")&&!s.equals("BTCUSD")&&!s.equals("ETHUSD"))
            throw new IOException("Unsupported market: "+s);
        return s;
    }

    static Candle[] parse(String s) throws Exception {
        JSONArray a=new JSONObject(s).getJSONArray("bars");
        Candle[] z=new Candle[a.length()];
        for(int i=0;i<a.length();i++){
            JSONObject j=a.getJSONObject(a.length()-1-i);
            z[i]=new Candle(Instant.parse(j.getString("openTime")).toEpochMilli(),
                j.getDouble("open"),j.getDouble("high"),j.getDouble("low"),j.getDouble("close"));
        }
        return z;
    }

    static String get(String u) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(6000);
        try(BufferedReader r=new BufferedReader(new InputStreamReader(
                (c.getResponseCode()>=400?c.getErrorStream():c.getInputStream())))){
            StringBuilder b=new StringBuilder();String s;
            while((s=r.readLine())!=null)b.append(s);
            if(c.getResponseCode()>=400)throw new IOException("Feed HTTP "+c.getResponseCode()+": "+b);
            return b.toString();
        } finally { c.disconnect(); }
    }
}