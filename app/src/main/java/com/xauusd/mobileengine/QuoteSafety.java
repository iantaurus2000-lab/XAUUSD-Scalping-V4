package com.xauusd.mobileengine;

/**
 * Fail-closed checks shared by the signal filter, display and order queue.
 * Broker tick age is separate from HTTP receive age so a closed market cannot
 * become tradable just because the EA keeps polling the LAN gateway.
 */
public final class QuoteSafety {
    private QuoteSafety() {}

    public static boolean valid(double bid,double ask){
        return Double.isFinite(bid)&&Double.isFinite(ask)&&bid>0.0&&ask>0.0&&ask>=bid;
    }

    public static boolean isFresh(long receivedAtMs,long tickAtMs,long nowMs){
        if(receivedAtMs<=0L||tickAtMs<=0L||nowMs<receivedAtMs||nowMs-receivedAtMs>5000L)return false;
        long age=nowMs-tickAtMs;
        return age>=-5000L&&age<=15000L;
    }

    /** Candle open time must be plausible and inside its freshness window. */
    public static boolean isFreshBar(long candleOpenMs,long nowMs,long maxAgeMs){
        if(candleOpenMs<=0L||nowMs<candleOpenMs||maxAgeMs<=0L)return false;
        long age=nowMs-candleOpenMs;
        return age<=maxAgeMs;
    }

    /**
     * Stage 1 light spread gate: allow up to 50% of current M1 ATR.
     * It reduces false blocks versus the previous 20% threshold, without disabling
     * spread protection. The price-relative floor only handles very small ATR values.
     */
    public static double maxAllowedSpread(double bid,double ask,double atr){
        if(!valid(bid,ask)||!Double.isFinite(atr)||atr<=0.0)return 0.0;
        double mid=(bid+ask)/2.0;
        return Math.max(atr*0.50,mid*0.000001);
    }

    public static boolean spreadAcceptable(double bid,double ask,double atr){
        if(!valid(bid,ask)||!Double.isFinite(atr)||atr<=0.0)return false;
        double spread=ask-bid;
        double maximum=maxAllowedSpread(bid,ask,atr);
        return Double.isFinite(spread)&&maximum>0.0&&spread<=maximum;
    }
}
