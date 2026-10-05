package com.xauusd.mobileengine;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fresh isolated Biquote chart feed. Biquote = MARKET/CHART only. FXOpen = entry/execution web API. */
public final class BiquoteChartFeed {
    private static final String BASE="https://biquote.io", SYMBOL="XAUUSD";
    private static final OkHttpClient HTTP=new OkHttpClient.Builder()
            .connectTimeout(4,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
            .writeTimeout(4,TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private final ArrayList<StrategyEngine.Candle> m1=new ArrayList<>(),m5=new ArrayList<>(),m15=new ArrayList<>();
    private long lastM1,lastM5,lastM15; private Tick lastTick;

    public static final class Tick{
        public final double mid,bid,ask,spread,dayDiff; public final long timeMs;
        Tick(double mid,double bid,double ask,double spread,double dayDiff,long timeMs){
            this.mid=mid;this.bid=bid;this.ask=ask;this.spread=spread;this.dayDiff=dayDiff;this.timeMs=timeMs;
        }
    }
    public static final class Snapshot{
        public final Tick tick; public final ArrayList<StrategyEngine.Candle> m1,m5,m15; public final String status;
        Snapshot(Tick t,ArrayList<StrategyEngine.Candle>a,ArrayList<StrategyEngine.Candle>b,ArrayList<StrategyEngine.Candle>c,String s){
            tick=t;m1=a;m5=b;m15=c;status=s;
        }
    }

    public synchronized Snapshot read() throws Exception{
        Tick t=readTick(); long now=System.currentTimeMillis();
        if(m1.size()<60||now-lastM1>=5000){List<StrategyEngine.Candle>x=ohlc("1m",600);if(x.size()>=30){put(m1,x);lastM1=now;}}
        if(m5.size()<30||now-lastM5>=15000){List<StrategyEngine.Candle>x=ohlc("5m",300);if(x.size()>=20){put(m5,x);lastM5=now;}}
        if(m15.size()<30||now-lastM15>=30000){List<StrategyEngine.Candle>x=ohlc("15m",200);if(x.size()>=20){put(m15,x);lastM15=now;}}
        if(m1.isEmpty()||m5.isEmpty()||m15.isEmpty())throw new IOException("BIQUOTE candle history incomplete");
        lastTick=t;
        return new Snapshot(t,live(m1,t.mid,60000L),live(m5,t.mid,300000L),live(m15,t.mid,900000L),"BIQUOTE REAL • CHART");
    }
    public synchronized Tick lastTick(){return lastTick;}

    private Tick readTick() throws Exception{
        JSONObject o=new JSONObject(get(BASE+"/api/"+SYMBOL));
        double mid=num(o,"mid","price"),bid=num(o,"bid"),ask=num(o,"ask");
        if(Double.isNaN(mid)||mid<=0)throw new IOException("BIQUOTE mid missing");
        if(Double.isNaN(bid))bid=mid;if(Double.isNaN(ask))ask=mid;
        double day=num(o,"dayDiffPercent","dayDiff");if(Double.isNaN(day))day=0;
        long tm=time(o.optString("timestamp",""));if(tm<=0)tm=System.currentTimeMillis();
        return new Tick(mid,bid,ask,Math.max(0,ask-bid),day,tm);
    }
    private List<StrategyEngine.Candle> ohlc(String tf,int limit)throws Exception{
        JSONObject root=new JSONObject(get(BASE+"/api/"+SYMBOL+"/ohlc?interval="+tf+"&limit="+limit));
        JSONArray ar=root.optJSONArray("bars");if(ar==null)throw new IOException("BIQUOTE "+tf+" bars missing");
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(int i=0;i<ar.length();i++){
            JSONObject b=ar.optJSONObject(i);if(b==null)continue;
            long t=time(b.optString("openTime",""));if(t<=0)t=b.optLong("time",b.optLong("timestamp",0));
            if(t>0&&t<100000000000L)t*=1000L;
            double o=num(b,"open"),h=num(b,"high"),l=num(b,"low"),c=num(b,"close");
            if(t>0&&valid(o,h,l,c))out.add(new StrategyEngine.Candle(t,o,h,l,c));
        }
        Collections.sort(out,Comparator.comparingLong(a->a.t));return out;
    }
    private static ArrayList<StrategyEngine.Candle> live(List<StrategyEngine.Candle>src,double p,long step){
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();for(StrategyEngine.Candle c:src)out.add(new StrategyEngine.Candle(c.t,c.open,c.high,c.low,c.close));
        if(out.isEmpty()||p<=0)return out;
        long bucket=System.currentTimeMillis()-(System.currentTimeMillis()%step);StrategyEngine.Candle last=out.get(out.size()-1);
        long lm=last.t<100000000000L?last.t*1000L:last.t;
        if(lm!=bucket){double o=last.close;out.add(new StrategyEngine.Candle(bucket,o,Math.max(o,p),Math.min(o,p),p));}
        else{last.high=Math.max(last.high,p);last.low=Math.min(last.low,p);last.close=p;last.t=bucket;}
        return out;
    }
    private static void put(ArrayList<StrategyEngine.Candle> a,List<StrategyEngine.Candle>b){a.clear();a.addAll(b);}
    private static boolean valid(double o,double h,double l,double c){return !Double.isNaN(o)&&!Double.isNaN(h)&&!Double.isNaN(l)&&!Double.isNaN(c)&&o>0&&h>0&&l>0&&c>0&&h>=Math.max(o,c)&&l<=Math.min(o,c);}
    private static double num(JSONObject o,String...ks){for(String k:ks){Object v=o.opt(k);if(v instanceof Number)return ((Number)v).doubleValue();if(v!=null)try{return Double.parseDouble(String.valueOf(v));}catch(Exception ignored){}}return Double.NaN;}
    private static long time(String s){if(s==null||s.isEmpty())return 0;try{return Instant.parse(s).toEpochMilli();}catch(Exception ignored){}try{long v=Long.parseLong(s);return v<100000000000L?v*1000L:v;}catch(Exception ignored){}return 0;}
    private static String get(String u)throws Exception{Request r=new Request.Builder().url(u).header("User-Agent","XAUUSD-Mobile-Trading-Engine/5.2-BIQUOTE-CHART").header("Accept","application/json").build();try(Response x=HTTP.newCall(r).execute()){if(!x.isSuccessful())throw new IOException("HTTP "+x.code());if(x.body()==null)throw new IOException("empty response");return x.body().string();}}
}