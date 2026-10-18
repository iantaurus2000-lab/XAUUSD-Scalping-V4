package com.xauusd.mobileengine;

public final class SignalEngine {
    private SignalEngine() {}

    public static SignalResult evaluate(Candle[] m1,Candle[] m5,double bid,double ask){
        return evaluate(m1,m5,bid,ask,bid,ask);
    }

    /** Candle-feed prices define levels; independent broker quotes define spread safety. */
    public static SignalResult evaluate(Candle[] m1,Candle[] m5,double bid,double ask,
                                        double spreadBid,double spreadAsk) {
        if (m1 == null || m1.length < 30 || m5 == null || m5.length < 30)
            return new SignalResult("WAIT", "NEUTRAL", 0, 0, 0, 0, 0, 0, 1,
                "Menunggu data M1/M5", false, false, false, false, false, false, false, false);

        Candle z = m1[m1.length - 1], p = m1[m1.length - 2];
        double atr = atr(m1, 14);
        double body = Math.abs(z.c - z.o);
        double upper = z.h - Math.max(z.o, z.c);
        double lower = Math.min(z.o, z.c) - z.l;
        double e9 = ema(m1, 9), e21 = ema(m1, 21), e50 = ema(m1, 50);
        double e9_5 = ema(m5, 9), e21_5 = ema(m5, 21), e50_5 = ema(m5, 50);
        boolean biasBuy = e9_5 > e21_5 && e21_5 > e50_5;
        boolean biasSell = e9_5 < e21_5 && e21_5 < e50_5;
        String bias = biasBuy ? "BUY" : biasSell ? "SELL" : "NEUTRAL";

        // Only the latest M1 candle can produce a setup. If its geometry
        // qualifies as both BUY and SELL rejection, direction is ambiguous: WAIT.
        int n = m1.length;
        int bestIndex = n - 1;
        Candle wick = z;
        double wickBody = Math.max(body, Math.max(atr * 0.035, 0.00001));
        double localRange = Math.max(z.h - z.l, atr);
        double priorLow = Math.min(m1[n-2].l, m1[n-3].l);
        double priorHigh = Math.max(m1[n-2].h, m1[n-3].h);
        boolean swBuy = z.l < priorLow && z.c > priorLow;
        boolean swSell = z.h > priorHigh && z.c < priorHigh;
        boolean rejectBuy = lower >= wickBody * 1.8
            && lower >= localRange * 0.38
            && z.c >= z.l + (z.h-z.l)*0.48;
        boolean rejectSell = upper >= wickBody * 1.8
            && upper >= localRange * 0.38
            && z.c <= z.l + (z.h-z.l)*0.52;
        boolean needleBuy = rejectBuy && !rejectSell;
        boolean needleSell = rejectSell && !rejectBuy;
        boolean sweepBuy = needleBuy && swBuy;
        boolean sweepSell = needleSell && swSell;

        double rsi = rsi(m1,14), macd = ema(m1,12)-ema(m1,26);
        boolean rsiBuy=rsi>=50&&rsi<=72, rsiSell=rsi<=50&&rsi>=28;
        boolean macdBuy=macd>0, macdSell=macd<0;
        boolean emaBuy=e9>e21&&e21>=e50, emaSell=e9<e21&&e21<=e50;
        // Spread uses the actual MT5 quote. Avoid a fixed $0.50 floor for all
        // instruments; allow at most 20% ATR or a tiny price-relative minimum.
        boolean spreadOk=QuoteSafety.spreadAcceptable(spreadBid,spreadAsk,atr), atrOk=atr>0;
        boolean bosBuy=z.c>p.h, bosSell=z.c<p.l;

        // A pronounced rejection wick is the primary trigger. Indicators contribute to
        // confidence, not a veto; spread and valid pending-order geometry remain safety gates.
        String side="WAIT";
        // Wick rejection is the sole entry trigger; indicators provide context only.
        if (needleBuy && !needleSell) side="BUY";
        else if (needleSell && !needleBuy) side="SELL";

        double entry=0, sl=0, tp1=0, tp2=0, rr=0;
        if ("BUY".equals(side)) {
            double bodyLow=Math.min(wick.o,wick.c);
            entry=wick.l+(bodyLow-wick.l)*0.38;
            if(ask>0) entry=Math.min(entry,ask-Math.max(atr*0.04,0.02));
            sl=wick.l-Math.max(atr*0.12,spread*2);
            double risk=entry-sl;
            if(risk>0){tp1=entry+risk*2.0;tp2=entry+risk*3.5;rr=3.5;}
        } else if ("SELL".equals(side)) {
            double bodyHigh=Math.max(wick.o,wick.c);
            entry=wick.h-(wick.h-bodyHigh)*0.38;
            if(bid>0) entry=Math.max(entry,bid+Math.max(atr*0.04,0.02));
            sl=wick.h+Math.max(atr*0.12,spread*2);
            double risk=sl-entry;
            if(risk>0){tp1=entry-risk*2.0;tp2=entry-risk*3.5;rr=3.5;}
        }
        // A wick that is already far behind live price is stale; do not chase it.
        double live= "BUY".equals(side)?ask:bid;
        if(bestIndex>=0 && live>0 && atr>0 && Math.abs(live-wick.c)>atr*1.35) {
            side="WAIT"; entry=sl=tp1=tp2=rr=0;
        }
        int points=(needleBuy||needleSell?6:0)+(sweepBuy||sweepSell?2:0)
            +(biasBuy||biasSell?1:0)+(emaBuy||emaSell?1:0)+(rsiBuy||rsiSell?1:0)
            +(macdBuy||macdSell?1:0)+(bosBuy||bosSell?1:0)+(spreadOk?1:0)+(atrOk?1:0);
        int score=Math.min(99,45+points*4);
        int confidence=needleBuy||needleSell?(sweepBuy||sweepSell?5:4):(points>=7?3:points>=5?2:1);
        String setup="WAIT".equals(side)
            ? (rejectBuy && rejectSell ? "WICK AMBIGU • WAIT" : "Menunggu wick valid")
            : "NEEDLE WICK + REJECTION"+((sweepBuy||sweepSell)?" + LIQUIDITY SWEEP":"");
        return new SignalResult(side,bias,entry,sl,tp1,tp2,rr,score,confidence,setup,
            sweepBuy||sweepSell,needleBuy||needleSell,bosBuy||bosSell,
            emaBuy||emaSell,rsiBuy||rsiSell,macdBuy||macdSell,atrOk,spreadOk);
    }

    static double ema(Candle[] a,int n){double k=2.0/(n+1.0),e=a[0].c;for(int i=1;i<a.length;i++)e=a[i].c*k+e*(1-k);return e;}
    static double atr(Candle[] a,int n){int k=Math.min(n,a.length-1);if(k<=0)return 0;double sum=0;for(int i=a.length-k;i<a.length;i++){Candle x=a[i],p=a[i-1];sum+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-p.c),Math.abs(x.l-p.c)));}return sum/k;}
    static double rsi(Candle[] a,int n){int k=Math.min(n,a.length-1);double gain=0,loss=0;for(int i=a.length-k;i<a.length;i++){double d=a[i].c-a[i-1].c;if(d>0)gain+=d;else loss-=d;}if(loss<1e-9)return 100;double rs=(gain/k)/(loss/k);return 100-100/(1+rs);}
}
