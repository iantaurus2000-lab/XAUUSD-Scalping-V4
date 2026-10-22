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
        String tf=normalizeInterval(interval);
        int limit="5m".equalsIgnoreCase(tf)?240:120; // Preserve the existing chart window.
        return fetchCandles(symbol,tf,limit);
    }

    /**
     * Dedicated history window for Excellent Machine analysis/backtest.
     * It intentionally does not change the number of candles drawn on the chart.
     */
    public static Candle[] analysisCandles(String market,String interval) throws Exception {
        String symbol=normalizeMarket(market);
        String tf=normalizeInterval(interval);
        return fetchCandles(symbol,tf,360); // EMA150 + 200-bar ML lookback + closed-bar buffer.
    }

    private static String normalizeInterval(String interval) {
        String tf=(interval==null||interval.trim().isEmpty())?"1m":interval.trim().toLowerCase(Locale.US);
        if(!tf.matches("1m|5m|15m|1h|4h|1d")) return "1m";
        return tf;
    }

    private static Candle[] fetchCandles(String symbol,String tf,int limit) throws Exception {
        return parse(get("https://biquote.io/api/"+symbol+"/ohlc?interval="+tf+"&limit="+limit));
    }

    public static double[] tick() throws Exception { return tick("XAUUSD"); }
    public static double[] tick(String market) throws Exception {
        String symbol=normalizeMarket(market);
        JSONObject j=new JSONObject(get("https://biquote.io/api/"+symbol));
        double bid=j.getDouble("bid"), ask=j.getDouble("ask");
        if(!QuoteSafety.valid(bid,ask))throw new IOException("Feed quote invalid for "+symbol);
        return new double[]{bid,ask};
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
