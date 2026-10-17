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
        public final long serverTime, tickAgeSec;
        public final boolean quoteFresh;
        public final Candle[] m1, m5, m15, h1, h4, d1;

        Snapshot(String s,double b,double a,int d,long t,long age,boolean fresh,
                 Candle[] one,Candle[] five,Candle[] fifteen,Candle[] hour,Candle[] four,Candle[] day){
            symbol=s;bid=b;ask=a;digits=d;serverTime=t;tickAgeSec=age;quoteFresh=fresh;
            m1=one;m5=five;m15=fifteen;h1=hour;h4=four;d1=day;
        }
    }

    public static Snapshot fetch() throws Exception { return fetch("XAUUSD"); }

    /** Strict market-data fetch. Existing signal/background consumers must have a fresh quote. */
    public static Snapshot fetch(String market) throws Exception {
        return fetchInternal(market,true);
    }

    /**
     * Chart-only fetch: valid OHLC history is usable while the market quote is stale/closed.
     * The returned quoteFresh flag must be honored before showing Bid/Ask or enabling entries.
     */
    public static Snapshot fetchForChart(String market) throws Exception {
        return fetchInternal(market,false);
    }

    private static Snapshot fetchInternal(String market,boolean requireFreshQuote) throws Exception {
        String expected=expectedSymbol(market);
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8787/feed?symbol="+
            URLEncoder.encode(expected,"UTF-8")).openConnection();
        c.setConnectTimeout(1200);c.setReadTimeout(1800);
        try{
            int code=c.getResponseCode();
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            StringBuilder b=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                String s;while((s=r.readLine())!=null)b.append(s);
            }
            if(code!=200)throw new IOException("MT5 feed HTTP "+code);
            return parseResponse(b.toString(),requireFreshQuote);
        }finally{c.disconnect();}
    }

    static boolean isQuoteFresh(boolean feedOk,long tickAgeSec,double bid,double ask){
        return feedOk&&tickAgeSec>=0&&tickAgeSec<=120&&
            Double.isFinite(bid)&&Double.isFinite(ask)&&bid>0&&ask>=bid;
    }

    /** Package-visible for deterministic unit tests of the wire contract. */
    static Snapshot parseResponse(String raw,boolean requireFreshQuote) throws Exception {
        JSONObject j=new JSONObject(raw);
        String symbol=j.getString("symbol");
        double bid=j.optDouble("bid",0),ask=j.optDouble("ask",0);
        long tickAge=j.optLong("tickAgeSec",Long.MAX_VALUE);
        boolean quoteFresh=isQuoteFresh(j.optBoolean("ok",false),tickAge,bid,ask);

        // Parse OHLC before applying quote freshness: market-closed feeds may still
        // contain valid historical rates which must remain visible in the chart.
        Candle[] one=bars(j.getJSONArray("m1"));
        Candle[] five=bars(j.getJSONArray("m5"));
        Candle[] fifteen=bars(j.getJSONArray("m15"));
        Candle[] hour=bars(j.getJSONArray("h1"));
        Candle[] four=bars(j.getJSONArray("h4"));
        Candle[] day=bars(j.getJSONArray("d1"));

        if(requireFreshQuote&&!quoteFresh){
            throw new IOException(j.optString("error","MT5 quote stale")+" (tickAgeSec="+tickAge+")");
        }
        return new Snapshot(symbol,bid,ask,j.optInt("digits",2),j.optLong("serverTime"),
            tickAge,quoteFresh,one,five,fifteen,hour,four,day);
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
