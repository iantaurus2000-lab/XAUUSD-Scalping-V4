package com.xauusd.mobileengine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Proven Biquote market/chart adapter.
 *
 * Biquote is the ONLY source for MARKET + CHART.
 * FXOpen/Exness execution code is intentionally not touched here.
 *
 * The request pattern follows the earlier working Biquote implementation:
 * - tick: /api/XAUUSD?allowStale=true
 * - OHLC: /api/XAUUSD/ohlc?interval=1m|5m|15m
 * - live price updates the last real Biquote candle; no synthetic candles are created.
 */
public final class BiquoteChartFeed {
    private static final String BASE="https://biquote.io";
    private static final String TICK_URL=BASE+"/api/XAUUSD?allowStale=true";
    private static final String SYMBOL="XAUUSD";

    private static final OkHttpClient HTTP=new OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final ArrayList<StrategyEngine.Candle> m1=new ArrayList<>();
    private final ArrayList<StrategyEngine.Candle> m5=new ArrayList<>();
    private final ArrayList<StrategyEngine.Candle> m15=new ArrayList<>();

    private long lastM1,lastM5,lastM15;
    private Tick lastTick;
    private String lastError="";

    public static final class Tick{
        public final double mid,bid,ask,spread,dayDiff;
        public final long timeMs;
        Tick(double mid,double bid,double ask,double spread,double dayDiff,long timeMs){
            this.mid=mid;this.bid=bid;this.ask=ask;this.spread=spread;this.dayDiff=dayDiff;this.timeMs=timeMs;
        }
    }

    public static final class Snapshot{
        public final Tick tick;
        public final ArrayList<StrategyEngine.Candle> m1,m5,m15;
        public final String status;
        Snapshot(Tick t,ArrayList<StrategyEngine.Candle>a,ArrayList<StrategyEngine.Candle>b,
                 ArrayList<StrategyEngine.Candle>c,String s){
            tick=t;m1=a;m5=b;m15=c;status=s;
        }
    }

    public synchronized Snapshot read() throws Exception{
        long now=System.currentTimeMillis();

        // Load/refresh real Biquote OHLC first so price can still be shown
        // when the separate tick endpoint is temporarily slow.
        if(m1.size()<60 || now-lastM1>=5000) {
            try {
                List<StrategyEngine.Candle> x=ohlc("1m",180);
                if(x.size()>=20){replace(m1,x);lastM1=now;}
            } catch(Exception ignored) {}
        }
        if(m5.size()<30 || now-lastM5>=15000) {
            try {
                List<StrategyEngine.Candle> x=ohlc("5m",120);
                if(x.size()>=20){replace(m5,x);lastM5=now;}
            } catch(Exception ignored) {}
        }
        if(m15.size()<30 || now-lastM15>=30000) {
            try {
                List<StrategyEngine.Candle> x=ohlc("15m",80);
                if(x.size()>=20){replace(m15,x);lastM15=now;}
            } catch(Exception ignored) {}
        }

        Exception tickError=null;
        try {
            lastTick=readTick();
            lastError="";
        } catch(Exception ex) {
            tickError=ex;
        }

        // If tick endpoint fails, use the latest REAL Biquote M1 close as the
        // temporary market price. This is still Biquote data, not synthetic data.
        if(lastTick==null || lastTick.mid<=0){
            StrategyEngine.Candle last=m1.isEmpty()?null:m1.get(m1.size()-1);
            if(last!=null && last.close>0){
                lastTick=new Tick(last.close,last.close,last.close,0,0,last.t);
                lastError=tickError==null?"":"tick: "+compact(tickError);
            } else {
                if(tickError!=null)throw tickError;
                throw new IOException("BIQUOTE price unavailable");
            }
        }

        // Use the real Biquote bars and only move the LAST real bar to the live
        // price. Do not invent a new candle from device clock buckets.
        ArrayList<StrategyEngine.Candle> a=cloneWithLive(m1,lastTick.mid);
        ArrayList<StrategyEngine.Candle> b=cloneWithLive(m5,lastTick.mid);
        ArrayList<StrategyEngine.Candle> c=cloneWithLive(m15,lastTick.mid);

        if(a.isEmpty())throw new IOException("BIQUOTE M1 history empty");

        String status;
        if(lastTick.spread>0 && lastError.isEmpty()) status="BIQUOTE REAL • CHART";
        else if(!lastError.isEmpty()) status="BIQUOTE REAL • CHART • TICK FALLBACK";
        else status="BIQUOTE REAL • CHART";

        return new Snapshot(lastTick,a,b,c,status);
    }

    public synchronized Tick lastTick(){return lastTick;}

    private Tick readTick() throws Exception{
        String body=get(TICK_URL);
        JSONObject o=new JSONObject(body);

        double mid=num(o,"mid","price","last","close");
        double bid=num(o,"bid","bestBid","bidPrice");
        double ask=num(o,"ask","bestAsk","askPrice");
        double spread=num(o,"spread");

        JSONObject result=o.optJSONObject("result");
        if(result!=null){
            if(Double.isNaN(mid))mid=num(result,"mid","price","last","close");
            if(Double.isNaN(bid))bid=num(result,"bid","bestBid","bidPrice");
            if(Double.isNaN(ask))ask=num(result,"ask","bestAsk","askPrice");
            if(Double.isNaN(spread))spread=num(result,"spread");
        }

        if((Double.isNaN(mid)||mid<=0) && !Double.isNaN(bid) && !Double.isNaN(ask))
            mid=(bid+ask)/2.0;
        if(Double.isNaN(mid)||mid<=0)throw new IOException("Biquote returned no mid price");

        if(Double.isNaN(spread)||spread<0) {
            spread=(!Double.isNaN(bid)&&!Double.isNaN(ask))?Math.max(0,ask-bid):0;
        }
        if(Double.isNaN(bid))bid=spread>0?mid-spread/2.0:mid;
        if(Double.isNaN(ask))ask=spread>0?mid+spread/2.0:mid;

        double day=num(o,"dayDiffPercent","dayDiff","changePercent");
        if(Double.isNaN(day))day=0;

        long tm=timeValue(o,"timestamp","time","serverTime");
        if(tm<=0)tm=System.currentTimeMillis();

        return new Tick(mid,bid,ask,Math.max(0,spread),day,tm);
    }

    private List<StrategyEngine.Candle> ohlc(String tf,int limit)throws Exception{
        String url=BASE+"/api/"+SYMBOL+"/ohlc?interval="+tf+"&limit="+limit;
        JSONObject root=new JSONObject(get(url));
        JSONArray ar=root.optJSONArray("bars");
        if(ar==null)ar=root.optJSONArray("Bars");
        if(ar==null)ar=root.optJSONArray("data");
        if(ar==null){
            JSONObject result=root.optJSONObject("result");
            if(result!=null){
                ar=result.optJSONArray("bars");
                if(ar==null)ar=result.optJSONArray("Bars");
                if(ar==null)ar=result.optJSONArray("data");
            }
        }
        if(ar==null)throw new IOException("BIQUOTE "+tf+" bars missing");

        ArrayList<StrategyEngine.Candle> out=new ArrayList<>(ar.length());
        for(int i=0;i<ar.length();i++){
            JSONObject b=ar.optJSONObject(i);
            if(b==null)continue;

            long t=timeValue(b,"openTime","OpenTime","time","Time","timestamp","Timestamp");
            double o=num(b,"open","Open"),h=num(b,"high","High"),
                   l=num(b,"low","Low"),c=num(b,"close","Close");
            if(t>0&&valid(o,h,l,c))out.add(new StrategyEngine.Candle(t,o,h,l,c));
        }
        Collections.sort(out,Comparator.comparingLong(a->a.t));
        return out;
    }

    private static ArrayList<StrategyEngine.Candle> cloneWithLive(
            List<StrategyEngine.Candle>src,double live){
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>(src.size());
        for(StrategyEngine.Candle c:src)
            out.add(new StrategyEngine.Candle(c.t,c.open,c.high,c.low,c.close));

        if(!out.isEmpty() && live>0){
            StrategyEngine.Candle c=out.get(out.size()-1);
            c.high=Math.max(c.high,Math.max(c.open,live));
            c.low=Math.min(c.low,Math.min(c.open,live));
            c.close=live;
        }
        return out;
    }

    private static void replace(ArrayList<StrategyEngine.Candle> target,List<StrategyEngine.Candle> source){
        target.clear();target.addAll(source);
    }

    /** Public HTTPS helper retained for the calendar/news ticker. */
    public static String publicGet(String url)throws Exception{return getStatic(url);}

    private static String getStatic(String u)throws Exception{
        Request r=new Request.Builder().url(u)
                .header("User-Agent","XAUUSD-Mobile-Trading-Engine/5.2-BIQUOTE")
                .header("Accept","application/json").build();
        try(Response x=HTTP.newCall(r).execute()){
            if(!x.isSuccessful())throw new IOException("HTTP "+x.code());
            if(x.body()==null)throw new IOException("empty response");
            return x.body().string();
        }
    }

    private static String get(String u)throws Exception{return getStatic(u);}

    private static boolean valid(double o,double h,double l,double c){
        return !Double.isNaN(o)&&!Double.isNaN(h)&&!Double.isNaN(l)&&!Double.isNaN(c)
                &&o>0&&h>0&&l>0&&c>0
                &&h>=Math.max(o,c)&&l<=Math.min(o,c);
    }

    private static double num(JSONObject o,String...ks){
        if(o==null)return Double.NaN;
        for(String k:ks){
            Object v=o.opt(k);
            if(v instanceof Number)return ((Number)v).doubleValue();
            if(v!=null){
                try{return Double.parseDouble(String.valueOf(v));}catch(Exception ignored){}
            }
        }
        return Double.NaN;
    }

    private static long timeValue(JSONObject o,String...keys){
        if(o==null)return 0;
        for(String k:keys){
            Object v=o.opt(k);
            if(v==null)continue;
            if(v instanceof Number){
                long n=((Number)v).longValue();
                return n<100000000000L?n*1000L:n;
            }
            String s=String.valueOf(v).trim();
            if(s.isEmpty())continue;
            try{return Instant.parse(s).toEpochMilli();}catch(Exception ignored){}
            try{
                long n=Long.parseLong(s);
                return n<100000000000L?n*1000L:n;
            }catch(Exception ignored){}
        }
        return 0;
    }

    private static String compact(Exception e){
        String s=e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage());
        return s.length()>120?s.substring(0,120):s;
    }
}
