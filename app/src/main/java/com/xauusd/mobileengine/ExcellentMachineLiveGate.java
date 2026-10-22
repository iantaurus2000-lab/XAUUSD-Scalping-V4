package com.xauusd.mobileengine;

import java.util.Locale;

/**
 * Live safety gate for Excellent Machine. Validates closed-candle signals against
 * quote freshness, spread, limit geometry, entry distance and risk/target ratios.
 * This class itself never submits or cancels an order.
 */
public final class ExcellentMachineLiveGate {
    private static final int MIN_BARS = 180;
    private static final long MAX_M1_SIGNAL_AGE_MS = 120_000L;
    private static final double MAX_ENTRY_DISTANCE_ATR = 1.25;
    private static final double MIN_RISK_ATR = 0.10;
    private static final double MAX_RISK_ATR = 3.0;

    private ExcellentMachineLiveGate() {}

    public static final class Decision {
        public final boolean allowed, atrValid, spreadValid, quoteFresh;
        public final String reason;
        public final double atr, spread, maxSpread, entryDistance, maxEntryDistance, risk;

        private Decision(boolean allowed, boolean atrValid, boolean spreadValid, boolean quoteFresh,
                         String reason, double atr, double spread, double maxSpread,
                         double entryDistance, double maxEntryDistance, double risk) {
            this.allowed=allowed;this.atrValid=atrValid;this.spreadValid=spreadValid;
            this.quoteFresh=quoteFresh;this.reason=reason;this.atr=atr;this.spread=spread;
            this.maxSpread=maxSpread;this.entryDistance=entryDistance;
            this.maxEntryDistance=maxEntryDistance;this.risk=risk;
        }
    }

    public static Decision evaluate(String market, String timeframe, Candle[] bars,
                                    ExcellentMachineEngine.Result signal,
                                    double bid, double ask,
                                    long quoteReceivedAtMs, long brokerTickAtMs, long nowMs,
                                    boolean upstreamQuotesFresh) {
        double atr=signal==null?0.0:signal.atr;
        double spread=QuoteSafety.valid(bid,ask)?ask-bid:0.0;
        double maxSpread=atr>0.0?QuoteSafety.maxAllowedSpread(bid,ask,atr):0.0;
        double distance=0.0,maxDistance=0.0,risk=0.0;
        boolean atrValid=Double.isFinite(atr)&&atr>0.0;
        boolean spreadValid=atrValid&&QuoteSafety.valid(bid,ask)
                &&QuoteSafety.spreadAcceptable(bid,ask,atr);
        boolean quoteFresh=QuoteSafety.isFresh(quoteReceivedAtMs,brokerTickAtMs,nowMs)
                &&upstreamQuotesFresh;

        if(!MarketSupport.isSupportedMarket(market))
            return decision(false,atrValid,spreadValid,quoteFresh,"MARKET TIDAK DIDUKUNG • XAUUSD / BTCUSD / ETHUSD",atr,spread,maxSpread,distance,maxDistance,risk);
        if(!"M1".equalsIgnoreCase(timeframe))
            return decision(false,atrValid,spreadValid,quoteFresh,"AUTO MACHINE HANYA BOLEH ENTRY M1",atr,spread,maxSpread,distance,maxDistance,risk);
        if(bars==null||bars.length<MIN_BARS)
            return decision(false,atrValid,spreadValid,quoteFresh,"RIWAYAT MACHINE KURANG • MINIMAL "+MIN_BARS+" CANDLE",atr,spread,maxSpread,distance,maxDistance,risk);

        int closedIndex=bars.length-2;
        if(closedIndex<1)
            return decision(false,atrValid,spreadValid,quoteFresh,"CANDLE TERTUTUP BELUM ADA",atr,spread,maxSpread,distance,maxDistance,risk);
        for(int i=0;i<bars.length;i++) {
            Candle c=bars[i];
            if(!valid(c))
                return decision(false,atrValid,spreadValid,quoteFresh,"OHLC TIDAK VALID INDEX "+i,atr,spread,maxSpread,distance,maxDistance,risk);
            if(i>0&&c.t<=bars[i-1].t)
                return decision(false,atrValid,spreadValid,quoteFresh,"RIWAYAT CANDLE TIDAK KRONOLOGIS",atr,spread,maxSpread,distance,maxDistance,risk);
        }
        if(signal==null)
            return decision(false,atrValid,spreadValid,quoteFresh,"HASIL ML BELUM TERSEDIA",atr,spread,maxSpread,distance,maxDistance,risk);
        if(signal.candleTime!=bars[closedIndex].t)
            return decision(false,atrValid,spreadValid,quoteFresh,"SIGNAL BUKAN DARI CANDLE TERTUTUP TERBARU",atr,spread,maxSpread,distance,maxDistance,risk);
        if(!QuoteSafety.isFreshBar(bars[closedIndex].t,nowMs,MAX_M1_SIGNAL_AGE_MS)
                ||!QuoteSafety.isFreshBar(bars[bars.length-1].t,nowMs,MAX_M1_SIGNAL_AGE_MS))
            return decision(false,atrValid,spreadValid,quoteFresh,"CANDLE M1 STALE • ENTRY DITAHAN",atr,spread,maxSpread,distance,maxDistance,risk);
        if(!signal.ready||(!"BUY".equals(signal.side)&&!"SELL".equals(signal.side)))
            return decision(false,atrValid,spreadValid,quoteFresh,"ML / TREND BELUM SEARAH • "+signal.reason,atr,spread,maxSpread,distance,maxDistance,risk);
        if(!quoteFresh)
            return decision(false,atrValid,spreadValid,false,"QUOTE MT5 / CHART BELUM SEGAR",atr,spread,maxSpread,distance,maxDistance,risk);
        if(!QuoteSafety.valid(bid,ask))
            return decision(false,atrValid,false,quoteFresh,"BID/ASK MT5 TIDAK VALID",atr,spread,maxSpread,distance,maxDistance,risk);
        if(!atrValid)
            return decision(false,false,false,quoteFresh,"ATR TIDAK VALID",atr,spread,maxSpread,distance,maxDistance,risk);
        if(!spreadValid)
            return decision(false,true,false,quoteFresh,"SPREAD MT5 DI ATAS 75% ATR",atr,spread,maxSpread,distance,maxDistance,risk);

        double entry=signal.entry, sl=signal.sl, tp1=signal.tp1, tp2=signal.tp2, tp3=signal.tp3;
        boolean buy="BUY".equals(signal.side);
        if(!finitePositive(entry)||!finitePositive(sl)||!finitePositive(tp1)||!finitePositive(tp2)||!finitePositive(tp3))
            return decision(false,true,true,quoteFresh,"LEVEL ENTRY/SL/TP TIDAK VALID",atr,spread,maxSpread,distance,maxDistance,risk);
        if(buy?!(entry<ask&&sl<entry&&tp1>entry&&tp2>tp1&&tp3>tp2)
              :!(entry>bid&&sl>entry&&tp1<entry&&tp2<tp1&&tp3<tp2))
            return decision(false,true,true,quoteFresh,buy?"GEOMETRI BUY LIMIT TIDAK VALID":"GEOMETRI SELL LIMIT TIDAK VALID",
                    atr,spread,maxSpread,distance,maxDistance,risk);

        double marketSide=buy?ask:bid;
        distance=Math.abs(marketSide-entry);
        maxDistance=Math.max(atr*MAX_ENTRY_DISTANCE_ATR,spread*2.0);
        if(!Double.isFinite(distance)||distance>maxDistance)
            return decision(false,true,true,quoteFresh,"HARGA SUDAH MENJAUH > "+MAX_ENTRY_DISTANCE_ATR+" ATR",
                    atr,spread,maxSpread,distance,maxDistance,risk);

        risk=Math.abs(entry-sl);
        if(!Double.isFinite(risk)||risk<Math.max(atr*MIN_RISK_ATR,spread*2.0))
            return decision(false,true,true,quoteFresh,"JARAK SL TERLALU DEKAT UNTUK ATR/SPREAD",
                    atr,spread,maxSpread,distance,maxDistance,risk);
        if(risk>atr*MAX_RISK_ATR)
            return decision(false,true,true,quoteFresh,"RISIKO SL > 3 ATR • SKIP SETUP",
                    atr,spread,maxSpread,distance,maxDistance,risk);

        double tolerance=Math.max(risk*0.05,1e-6);
        if(Math.abs(Math.abs(tp1-entry)-risk)>tolerance
                ||Math.abs(Math.abs(tp2-entry)-2.0*risk)>tolerance
                ||Math.abs(Math.abs(tp3-entry)-3.0*risk)>tolerance)
            return decision(false,true,true,quoteFresh,"RASIO TP HARUS 1R / 2R / 3R",
                    atr,spread,maxSpread,distance,maxDistance,risk);
        return decision(true,true,true,true,"READY • QUOTE / CANDLE / TREND / ML / LEVEL VALID",
                atr,spread,maxSpread,distance,maxDistance,risk);
    }

    private static Decision decision(boolean allowed,boolean atrValid,boolean spreadValid,boolean quoteFresh,
                                     String reason,double atr,double spread,double maxSpread,
                                     double distance,double maxDistance,double risk) {
        return new Decision(allowed,atrValid,spreadValid,quoteFresh,reason,atr,spread,maxSpread,distance,maxDistance,risk);
    }

    private static boolean finitePositive(double n) { return Double.isFinite(n)&&n>0.0; }

    private static boolean valid(Candle c) {
        return c!=null&&Double.isFinite(c.o)&&Double.isFinite(c.h)&&Double.isFinite(c.l)&&Double.isFinite(c.c)
                &&c.t>0&&c.o>0&&c.h>=c.l&&c.h>=Math.max(c.o,c.c)&&c.l<=Math.min(c.o,c.c);
    }
}
