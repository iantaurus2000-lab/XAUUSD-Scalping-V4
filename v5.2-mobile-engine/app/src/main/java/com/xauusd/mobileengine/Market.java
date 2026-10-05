package com.xauusd.mobileengine;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Market gateway.
 * Biquote is the chart/market source.
 * FXOpen is intentionally NOT used for chart candles; it remains the entry/execution web API.
 */
public final class Market {
    private static final BiquoteChartFeed CHART=new BiquoteChartFeed();
    private static String status="BIQUOTE OFFLINE";
    private static String error="";

    public static final class Tick{
        public final double mid,bid,ask,spread,dayDiff;
        Tick(double m,double b,double a,double s,double d){mid=m;bid=b;ask=a;spread=s;dayDiff=d;}
    }
    public static final class Snapshot{
        public final double mid,bid,ask,spread;
        public final double monitorMid,monitorBid,monitorAsk,monitorSpread;
        public final String monitorStatus;
        public final ArrayList<StrategyEngine.Candle> m1,m5,m15;
        Snapshot(Tick t,ArrayList<StrategyEngine.Candle>a,ArrayList<StrategyEngine.Candle>b,ArrayList<StrategyEngine.Candle>c){
            mid=t.mid;bid=t.bid;ask=t.ask;spread=t.spread;
            monitorMid=t.mid;monitorBid=t.bid;monitorAsk=t.ask;monitorSpread=t.spread;
            monitorStatus=status;m1=a;m5=b;m15=c;
        }
    }

    private Market(){}

    public static synchronized Tick tick() throws Exception{
        BiquoteChartFeed.Tick t=CHART.read().tick;
        status="BIQUOTE REAL • CHART";error="";
        return new Tick(t.mid,t.bid,t.ask,t.spread,t.dayDiff);
    }

    public static synchronized Snapshot snapshot(double lastPrice)throws Exception{
        return snapshot(lastPrice,null);
    }

    public static synchronized Snapshot snapshot(double lastPrice,FxOpenTickTraderClient fx)throws Exception{
        try{
            BiquoteChartFeed.Snapshot s=CHART.read();
            status=s.status;error="";
            BiquoteChartFeed.Tick t=s.tick;
            return new Snapshot(new Tick(t.mid,t.bid,t.ask,t.spread,t.dayDiff),s.m1,s.m5,s.m15);
        }catch(Exception ex){
            status="BIQUOTE ERROR";error=compact(ex);
            throw new IOException("Biquote chart feed: "+error,ex);
        }
    }

    public static synchronized String sourceStatus(){return status+(error.isEmpty()?"":" • "+error);}
    public static synchronized String monitorStatus(){return sourceStatus();}
    public static String publicGet(String url)throws Exception{return url==null?null:url;}

    private static String compact(Exception e){
        String s=e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage());
        return s.length()>100?s.substring(0,100):s;
    }
}