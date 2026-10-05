package com.xauusd.mobileengine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class Market {
    private static final String BIQUOTE_API = "https://biquote.io/api/XAUUSD";
    private static final String BIQUOTE_BASE = "https://biquote.io";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static List<StrategyEngine.Candle> cacheM1 = new ArrayList<>();
    private static List<StrategyEngine.Candle> cacheM5 = new ArrayList<>();
    private static List<StrategyEngine.Candle> cacheM15 = new ArrayList<>();
    private static long lastM1 = 0, lastM5 = 0, lastM15 = 0;
    private static Tick lastMonitor;
    private static String monitorSource = "BIQUOTE OFFLINE";
    private static String monitorError = "";
    private static String source = "FXOPEN OFFLINE";


    public static final class Tick {
        public final double mid, bid, ask, spread, dayDiff;
        Tick(double mid, double bid, double ask, double spread, double dayDiff) {
            this.mid = mid; this.bid = bid; this.ask = ask; this.spread = spread; this.dayDiff = dayDiff;
        }
    }

    public static final class Snapshot {
        public final double mid, bid, ask, spread;
        public final double monitorMid, monitorBid, monitorAsk, monitorSpread;
        public final String monitorStatus;
        public final ArrayList<StrategyEngine.Candle> m1, m5, m15;
        Snapshot(double mid, double bid, double ask, double spread,
                 double monitorMid, double monitorBid, double monitorAsk, double monitorSpread,
                 String monitorStatus,
                 ArrayList<StrategyEngine.Candle> m1,
                 ArrayList<StrategyEngine.Candle> m5,
                 ArrayList<StrategyEngine.Candle> m15) {
            this.mid=mid;this.bid=bid;this.ask=ask;this.spread=spread;
            this.monitorMid=monitorMid;this.monitorBid=monitorBid;this.monitorAsk=monitorAsk;this.monitorSpread=monitorSpread;
            this.monitorStatus=monitorStatus;
            this.m1=m1;this.m5=m5;this.m15=m15;
        }
    }

    private Market(){}

    public static Tick tick() throws Exception {
        String body = get(BIQUOTE_API);
        JSONObject o = new JSONObject(body);
        double mid = o.optDouble("mid", o.optDouble("price", Double.NaN));
        if (Double.isNaN(mid) || mid <= 0) throw new IOException("Biquote: no mid price");
        double bid = o.optDouble("bid", mid);
        double ask = o.optDouble("ask", mid);
        double spread = (!Double.isNaN(bid) && !Double.isNaN(ask))
                ? ask - bid : o.optDouble("spread", 0.30);
        double day = o.optDouble("dayDiff", o.optDouble("dayDiffPercent", 0.0));
        lastMonitor = new Tick(mid,bid,ask,spread,day);
        monitorSource = "BIQUOTE REAL"; monitorError = "";
        return lastMonitor;
    }

    public static synchronized Snapshot snapshot(double lastPrice) throws Exception {
        return snapshot(lastPrice, null);
    }

    /**
     * CLEAN FEED ARCHITECTURE:
     * FXOpen = entry/strategy/chart source.
     * Biquote = realtime market monitor only.
     * Biquote OHLC is deliberately NOT used by the chart or signal engine.
     */
    public static synchronized Snapshot snapshot(double lastPrice, FxOpenTickTraderClient fx) throws Exception {
        Tick monitor = null;
        try {
            monitor = tick();
        } catch (Exception ex) {
            monitor = lastMonitor;
            monitorSource = "BIQUOTE OFFLINE";
            monitorError = compact(ex);
        }

        if (fx == null || !fx.configured()) {
            source = "FXOPEN OFFLINE";
            throw new IOException("FXOpen ENTRY feed not configured");
        }

        Snapshot exec;
        try {
            exec = fxSnapshot(fx, lastPrice);
        } catch (Exception ex) {
            source = "FXOPEN ERROR";
            throw new IOException("FXOpen chart feed: " + ex.getMessage(), ex);
        }

        double mp = monitor == null ? 0 : monitor.mid;
        double mb = monitor == null ? 0 : monitor.bid;
        double ma = monitor == null ? 0 : monitor.ask;
        double ms = monitor == null ? 0 : monitor.spread;

        return new Snapshot(exec.mid, exec.bid, exec.ask, exec.spread,
                mp, mb, ma, ms, monitorStatus(),
                exec.m1, exec.m5, exec.m15);
    }

    private static Snapshot fxSnapshot(FxOpenTickTraderClient fx,double lastPrice) throws Exception {
        FxOpenTickTraderClient.Response tr=fx.publicTickV2("XAUUSD");
        if(!tr.ok()) throw new IOException("FXOpen tick HTTP "+tr.code);
        JSONObject q=new JSONObject(tr.body);
        double mid=num(q,"Mid","mid","Last","last","Price","price");
        double bid=num(q,"Bid","bid"), ask=num(q,"Ask","ask");
        if(Double.isNaN(mid)){
            if(!Double.isNaN(bid)&&!Double.isNaN(ask))mid=(bid+ask)/2.0;
            else throw new IOException("FXOpen no price");
        }
        if(Double.isNaN(bid))bid=mid;if(Double.isNaN(ask))ask=mid;

        long now=System.currentTimeMillis();
        if(cacheM1.size()<60 || now-lastM1>=2000){
            ArrayList<StrategyEngine.Candle> x=fxBars(fx,"M1",180,now);
            if(!x.isEmpty()){cacheM1=x;lastM1=now;}
        }
        if(cacheM5.size()<30 || now-lastM5>=5000){
            ArrayList<StrategyEngine.Candle> x=fxBars(fx,"M5",150,now);
            if(!x.isEmpty()){cacheM5=x;lastM5=now;}
        }
        if(cacheM15.size()<30 || now-lastM15>=15000){
            ArrayList<StrategyEngine.Candle> x=fxBars(fx,"M15",100,now);
            if(!x.isEmpty()){cacheM15=x;lastM15=now;}
        }

        if(cacheM1.isEmpty())throw new IOException("FXOpen M1 history empty");
        source = "FXOPEN REAL";
        double spread=Math.max(0,ask-bid);
        return new Snapshot(mid,bid,ask,spread,0,0,0,0,"BIQUOTE OFFLINE",
                liveCopy(cacheM1,mid),
                liveCopy(cacheM5.isEmpty()?cacheM1:cacheM5,mid),
                liveCopy(cacheM15.isEmpty()?cacheM1:cacheM15,mid));
    }

    private static ArrayList<StrategyEngine.Candle> fxBars(FxOpenTickTraderClient fx,String tf,int count,long now) throws Exception {
        FxOpenTickTraderClient.Response r=fx.quoteHistory("XAUUSD",tf,"Bid",now,count);
        if(!r.ok()) r=fx.publicQuoteHistory("XAUUSD",tf,"Bid",now,count);
        if(!r.ok()) throw new IOException("FXOpen "+tf+" HTTP "+r.code);
        String z=r.body.trim();
        JSONArray ar=null;
        if(z.startsWith("[")) ar=new JSONArray(z);
        else {
            JSONObject root=new JSONObject(z);
            ar=root.optJSONArray("Bars");
            if(ar==null)ar=root.optJSONArray("bars");
        }
        if(ar==null)return new ArrayList<>();
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(int i=0;i<ar.length();i++){
            JSONObject b=ar.optJSONObject(i);if(b==null)continue;
            long t=(long)num(b,"Timestamp","timestamp","Time","time","OpenTime","openTime");
            if(t>0&&t<100000000000L)t*=1000L;
            double o=num(b,"Open","open"),h=num(b,"High","high"),l=num(b,"Low","low"),cc=num(b,"Close","close");
            if(t>0&&!Double.isNaN(o)&&!Double.isNaN(h)&&!Double.isNaN(l)&&!Double.isNaN(cc))
                out.add(new StrategyEngine.Candle(t,o,h,l,cc));
        }
        Collections.sort(out,Comparator.comparingLong(a->a.t));
        return out;
    }

    public static synchronized String sourceStatus() {
        return source;
    }

    public static synchronized String monitorStatus() {
        return monitorSource + (monitorError.isEmpty() ? "" : " • " + monitorError);
    }

    public static String publicGet(String url)throws Exception{return get(url);}

    private static String compact(Exception e){
        String s=e==null?"" : e.getMessage();
        if(s==null||s.isEmpty())s=e==null?"error":e.getClass().getSimpleName();
        return s.length()>42?s.substring(0,42):s;
    }

    private static double num(JSONObject o,String...keys){for(String k:keys){if(o.has(k)){double v=o.optDouble(k,Double.NaN);if(!Double.isNaN(v))return v;}}return Double.NaN;}

    private static ArrayList<StrategyEngine.Candle> liveCopy(List<StrategyEngine.Candle> src,double mid){
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(StrategyEngine.Candle c:src)
            out.add(new StrategyEngine.Candle(c.t,c.open,c.high,c.low,c.close));
        if(!out.isEmpty()){
            StrategyEngine.Candle c=out.get(out.size()-1);
            c.close=mid;c.high=Math.max(c.high,Math.max(c.open,mid));c.low=Math.min(c.low,Math.min(c.open,mid));
        }
        return out;
    }

    private static String get(String url)throws Exception{
        Request request=new Request.Builder().url(url)
                .header("User-Agent","XAUUSD-Mobile-Trading-Engine/5.2.7")
                .header("Accept","application/json").build();
        try(Response response=CLIENT.newCall(request).execute()){
            if(!response.isSuccessful()) throw new IOException("HTTP "+response.code());
            if(response.body()==null)throw new IOException("Empty response");
            return response.body().string();
        }
    }
}
