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
    private static final String BASE = "https://biquote.io";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static List<StrategyEngine.Candle> cacheM1 = new ArrayList<>();
    private static List<StrategyEngine.Candle> cacheM5 = new ArrayList<>();
    private static List<StrategyEngine.Candle> cacheM15 = new ArrayList<>();
    private static long lastM1 = 0, lastM5 = 0, lastM15 = 0;
    private static volatile String lastSource = "NONE";
    private static volatile String lastError = "";

    public static final class Tick {
        public final double mid, bid, ask, spread, dayDiff;
        Tick(double mid, double bid, double ask, double spread, double dayDiff) {
            this.mid = mid; this.bid = bid; this.ask = ask; this.spread = spread; this.dayDiff = dayDiff;
        }
    }

    public static final class Snapshot {
        public final double mid, bid, ask, spread;
        public final ArrayList<StrategyEngine.Candle> m1, m5, m15;
        Snapshot(double mid, double bid, double ask, double spread,
                 ArrayList<StrategyEngine.Candle> m1,
                 ArrayList<StrategyEngine.Candle> m5,
                 ArrayList<StrategyEngine.Candle> m15) {
            this.mid=mid;this.bid=bid;this.ask=ask;this.spread=spread;
            this.m1=m1;this.m5=m5;this.m15=m15;
        }
    }

    private Market(){}

    public static String sourceStatus(){ return lastSource + (lastError.isEmpty() ? "" : " • "+lastError); }

    public static Tick tick() throws Exception {
        String body = get(BASE + "/api/XAUUSD");
        JSONObject o = new JSONObject(body);
        double mid = o.optDouble("mid", o.optDouble("price", Double.NaN));
        if (Double.isNaN(mid) || mid <= 0) throw new IOException("Biquote: no mid price");
        double bid = o.optDouble("bid", mid);
        double ask = o.optDouble("ask", mid);
        double spread = (!Double.isNaN(bid) && !Double.isNaN(ask))
                ? ask - bid : o.optDouble("spread", 0.30);
        double day = o.optDouble("dayDiff", o.optDouble("dayDiffPercent", 0.0));
        return new Tick(mid,bid,ask,spread,day);
    }

    public static synchronized Snapshot snapshot(double lastPrice) throws Exception {
        return snapshot(lastPrice, null);
    }

    public static synchronized Snapshot snapshot(double lastPrice, FxOpenTickTraderClient fx) throws Exception {
        if (fx != null && fx.configured()) {
            try { lastError=""; Snapshot s=fxSnapshot(fx, lastPrice); lastSource="FXOPEN WS"; return s; } catch (Exception ex) { lastError=ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()); }
        }
        if (lastSource.startsWith("FXOPEN")) lastSource="FXOPEN WS ERROR"; else lastSource="BIQUOTE";
        Tick t = tick();
        long now = System.currentTimeMillis();

        if (cacheM1.size() < 60 || now-lastM1 >= 3000) {
            List<StrategyEngine.Candle> x = fetchOhlc("1m",150);
            if (x.size() >= 20) { cacheM1=x; lastM1=now; }
        }
        if (cacheM5.size() < 30 || now-lastM5 >= 10000) {
            List<StrategyEngine.Candle> x = fetchOhlc("5m",120);
            if (x.size() >= 20) { cacheM5=x; lastM5=now; }
        }
        if (cacheM15.size() < 30 || now-lastM15 >= 15000) {
            List<StrategyEngine.Candle> x = fetchOhlc("15m",100);
            if (x.size() >= 20) { cacheM15=x; lastM15=now; }
        }

        if (cacheM1.isEmpty()) throw new IOException("Biquote: M1 OHLC unavailable");
        ArrayList<StrategyEngine.Candle> m1 = liveCopy(cacheM1,t.mid);
        ArrayList<StrategyEngine.Candle> m5 = liveCopy(cacheM5.isEmpty()?cacheM1:cacheM5,t.mid);
        ArrayList<StrategyEngine.Candle> m15 = liveCopy(cacheM15.isEmpty()?cacheM1:cacheM15,t.mid);
        return new Snapshot(t.mid,t.bid,t.ask,t.spread,m1,m5,m15);
    }

    private static Snapshot fxSnapshot(FxOpenTickTraderClient fx,double lastPrice) throws Exception {
        String probe=fx.wsFeedProbe();
        JSONObject po=new JSONObject(probe);
        JSONObject pr=po.optJSONObject("Result");
        double bid=Double.NaN,ask=Double.NaN,mid=Double.NaN;
        if(pr!=null){
            JSONObject bb=pr.optJSONObject("BestBid"),ba=pr.optJSONObject("BestAsk");
            if(bb!=null) bid=num(bb,"Price","price");
            if(ba!=null) ask=num(ba,"Price","price");
        }
        if(Double.isNaN(bid)||Double.isNaN(ask)){
            JSONObject rr=po.optJSONObject("Result");
            if(rr!=null){
                JSONArray snap=rr.optJSONArray("Snapshot");
                if(snap!=null&&snap.length()>0){
                    JSONObject s=snap.optJSONObject(0);
                    if(s!=null){
                        JSONObject bb=s.optJSONObject("BestBid"),ba=s.optJSONObject("BestAsk");
                        if(bb!=null)bid=num(bb,"Price","price");
                        if(ba!=null)ask=num(ba,"Price","price");
                    }
                }
            }
        }
        if(!Double.isNaN(bid)&&!Double.isNaN(ask)) mid=(bid+ask)/2.0;
        if(Double.isNaN(mid)) throw new IOException("FXOpen WebSocket no XAUUSD price");
        long now=System.currentTimeMillis();
        ArrayList<StrategyEngine.Candle> a1=fxBarsWs(fx,"M1",150,now);
        ArrayList<StrategyEngine.Candle> a5=fxBarsWs(fx,"M5",120,now);
        ArrayList<StrategyEngine.Candle> a15=fxBarsWs(fx,"M15",100,now);
        if(a1.isEmpty()) throw new IOException("FXOpen WebSocket M1 history empty");
        return new Snapshot(mid,bid,ask,Math.max(0,ask-bid),
                liveCopy(a1,mid),liveCopy(a5.isEmpty()?a1:a5,mid),liveCopy(a15.isEmpty()?a1:a15,mid));
    }

    private static ArrayList<StrategyEngine.Candle> fxBarsWs(FxOpenTickTraderClient fx,String tf,int count,long now) throws Exception {
        FxOpenTickTraderClient.Response r=fx.wsQuoteHistory("XAUUSD",tf,"Bid",now,-Math.abs(count));
        if(!r.ok()) throw new IOException("FXOpen WebSocket "+tf+" HTTP "+r.code);
        JSONArray ar=new JSONArray(r.body);
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(int i=0;i<ar.length();i++){
            JSONObject b=ar.optJSONObject(i); if(b==null)continue;
            long t=(long)num(b,"Timestamp","timestamp","Time","time","OpenTime","openTime");
            if(t>0&&t<100000000000L)t*=1000L;
            double o=num(b,"Open","open"),h=num(b,"High","high"),l=num(b,"Low","low"),cl=num(b,"Close","close");
            if(t>0&&!Double.isNaN(o)&&!Double.isNaN(h)&&!Double.isNaN(l)&&!Double.isNaN(cl))
                out.add(new StrategyEngine.Candle(t,o,h,l,cl));
        }
        Collections.sort(out,Comparator.comparingLong(a->a.t));
        return out;
    }

    private static List<StrategyEngine.Candle> fetchOhlc(String interval,int limit) throws Exception {
        String body=get(BASE+"/api/XAUUSD/ohlc?interval="+interval+"&limit="+limit);
        String z=body.trim();
        JSONArray ar=null;
        if(z.startsWith("[")) ar=new JSONArray(z);
        else {
            JSONObject root=new JSONObject(z);
            ar=root.optJSONArray("bars");
            if(ar==null) ar=root.optJSONArray("data");
            if(ar==null) {
                JSONObject result=root.optJSONObject("result");
                if(result!=null) ar=result.optJSONArray("bars");
            }
        }
        if(ar==null) throw new IOException("OHLC bars missing for "+interval);

        ArrayList<StrategyEngine.Candle> out=new ArrayList<>(ar.length());
        for(int i=0;i<ar.length();i++){
            JSONObject b=ar.optJSONObject(i);
            if(b==null)continue;
            long t=b.optLong("openTime",b.optLong("time",b.optLong("timestamp",0)));
            if(t==0){
                String iso=b.optString("openTime",b.optString("timestamp",""));
                if(!iso.isEmpty())try{t=java.time.Instant.parse(iso).toEpochMilli();}catch(Exception ignored){}
            }
            if(t>0 && t<100000000000L)t*=1000L;
            double o=b.optDouble("open",Double.NaN),h=b.optDouble("high",Double.NaN);
            double l=b.optDouble("low",Double.NaN),c=b.optDouble("close",Double.NaN);
            if(Double.isNaN(o)||Double.isNaN(h)||Double.isNaN(l)||Double.isNaN(c))continue;
            out.add(new StrategyEngine.Candle(t,o,h,l,c));
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.t));
        return out;
    }

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
                .header("User-Agent","XAUUSD-Mobile-Trading-Engine/5.2.9-FXOPEN")
                .header("Accept","application/json").build();
        try(Response response=CLIENT.newCall(request).execute()){
            if(!response.isSuccessful()) throw new IOException("HTTP "+response.code());
            if(response.body()==null)throw new IOException("Empty response");
            return response.body().string();
        }
    }
}
