package com.xauusd.mobileengine;

public final class SignalEngine {
    private SignalEngine() {}

    public static SignalResult evaluate(Candle[] m1,Candle[] m5,double bid,double ask){
        return evaluate(m1,m5,bid,ask,bid,ask);
    }

    /** Candle-feed prices define entry levels; independent MT5 quotes define spread safety. */
    public static SignalResult evaluate(Candle[] m1,Candle[] m5,double bid,double ask,
                                        double spreadBid,double spreadAsk) {
        if (m1 == null || m1.length < 30 || m5 == null || m5.length < 30)
            return new SignalResult("WAIT", "NEUTRAL", 0, 0, 0, 0, 0, 0, 1,
                "Menunggu data M1/M5", false, false, false, false, false, false, false, false);

        Candle z = m1[m1.length - 1], p = m1[m1.length - 2];
        double atr = atr(m1, 14);
        double body = Math.abs(z.c - z.o);
        double upper = Math.max(0.0, z.h - Math.max(z.o, z.c));
        double lower = Math.max(0.0, Math.min(z.o, z.c) - z.l);
        double candleRange = Math.max(0.0, z.h - z.l);
        double rangeAtrRatio = atr > 0.0 ? candleRange / atr : 0.0;
        // Adaptive wick sensor: short candles need smaller absolute wicks; long candles
        // still need a meaningful rejection rather than a body-dominated spike.
        double wickBodyMultiplier = rangeAtrRatio >= 1.25 ? 1.15
            : rangeAtrRatio <= 0.55 ? 0.90 : 1.00;
        double wickRangeFraction = rangeAtrRatio >= 1.25 ? 0.22
            : rangeAtrRatio <= 0.55 ? 0.14 : 0.18;
        double minWickAtrFraction = rangeAtrRatio >= 1.25 ? 0.08
            : rangeAtrRatio <= 0.55 ? 0.035 : 0.055;
        double closeRetrace = rangeAtrRatio >= 1.25 ? 0.30
            : rangeAtrRatio <= 0.55 ? 0.20 : 0.25;
        double wickBody = Math.max(body, Math.max(atr * 0.015, Math.abs(z.c) * 1e-7));
        double minWickLength = Math.max(atr * minWickAtrFraction, candleRange * wickRangeFraction);
        double e9 = ema(m1, 9), e21 = ema(m1, 21), e50 = ema(m1, 50);
        double e9_5 = ema(m5, 9), e21_5 = ema(m5, 21), e50_5 = ema(m5, 50);
        boolean biasBuy = e9_5 > e21_5 && e21_5 > e50_5;
        boolean biasSell = e9_5 < e21_5 && e21_5 < e50_5;
        String bias = biasBuy ? "BUY" : biasSell ? "SELL" : "NEUTRAL";

        // Evaluate the live M1 bar from one-second feed updates, not a 15-second-old snapshot.
        int n = m1.length;
        Candle wick = z;
        double wickBody = Math.max(body, Math.max(atr * 0.025, Math.abs(z.c) * 1e-7));
        double priorLow = Double.POSITIVE_INFINITY;
        double priorHigh = Double.NEGATIVE_INFINITY;
        // Use a short recent swing window, excluding the still-forming candle.
        for (int i = Math.max(0, n - 6); i < n - 1; i++) {
            priorLow = Math.min(priorLow, m1[i].l);
            priorHigh = Math.max(priorHigh, m1[i].h);
        }
        // Accept a near-sweep/reclaim within 0.08 ATR instead of demanding a strict
        // one-tick break of the previous swing. This lowers missed wick setups without
        // removing the requirement for a directional wick/rejection.
        double sweepTolerance = atr > 0.0 ? Math.max(atr * 0.08, Math.abs(z.c) * 1e-7) : 0.0;
        boolean swBuy = Double.isFinite(priorLow) && z.l <= priorLow + sweepTolerance;
        boolean swSell = Double.isFinite(priorHigh) && z.h >= priorHigh - sweepTolerance;

        // Early precursor: a swing is swept and price starts recoiling. This can become READY
        // on the first small rebound, before a large finished wick is visible.
        double recoveryFromLow = z.c - z.l;
        double recoveryFromHigh = z.h - z.c;
        double earlyRecoilMin = Math.max(atr * 0.015, candleRange * 0.015);
        boolean earlyBuy = swBuy && atr > 0.0
            && recoveryFromLow >= earlyRecoilMin
            && z.c <= priorLow + atr * 0.45;
        boolean earlySell = swSell && atr > 0.0
            && recoveryFromHigh >= earlyRecoilMin
            && z.c >= priorHigh - atr * 0.45;

        // Candle-size-aware wick thresholds reduce missed short/medium candle rejections.
        boolean rejectBuy = lower >= wickBody * wickBodyMultiplier
            && lower >= minWickLength
            && (candleRange <= 0.0 || z.c >= z.l + candleRange * closeRetrace);
        boolean rejectSell = upper >= wickBody * wickBodyMultiplier
            && upper >= minWickLength
            && (candleRange <= 0.0 || z.c <= z.h - candleRange * closeRetrace);
        boolean needleBuy = rejectBuy || earlyBuy;
        boolean needleSell = rejectSell || earlySell;
        boolean sweepBuy = needleBuy && swBuy;
        boolean sweepSell = needleSell && swSell;

        double rsi = rsi(m1,14), macd = ema(m1,12)-ema(m1,26);
        boolean rsiBuy=rsi>=50&&rsi<=72, rsiSell=rsi<=50&&rsi>=28;
        boolean macdBuy=macd>0, macdSell=macd<0;
        boolean emaBuy=e9>e21&&e21>=e50, emaSell=e9<e21&&e21<=e50;
        double spread=QuoteSafety.valid(spreadBid,spreadAsk)?spreadAsk-spreadBid:0.0;
        boolean spreadOk=QuoteSafety.spreadAcceptable(spreadBid,spreadAsk,atr), atrOk=atr>0;
        boolean bosBuy=z.c>p.h, bosSell=z.c<p.l;

        // Score BUY and SELL wick evidence independently. If both shapes exist on one
        // forming candle, select the clearer rejection instead of forcing WAIT or defaulting
        // toward SELL. The formulas are mirrored so BUY isn't disadvantaged by M5 bias.
        double buyWickRatio=lower/Math.max(wickBody,1e-12);
        double sellWickRatio=upper/Math.max(wickBody,1e-12);
        double buyEvidence=(rejectBuy?2.0:0.0)+(earlyBuy?1.5:0.0)+(swBuy?1.25:0.0)
            +Math.min(2.0,buyWickRatio*0.35)
            +Math.max(0.0,Math.min(1.0,recoveryFromLow/Math.max(atr*0.25,1e-12)));
        double sellEvidence=(rejectSell?2.0:0.0)+(earlySell?1.5:0.0)+(swSell?1.25:0.0)
            +Math.min(2.0,sellWickRatio*0.35)
            +Math.max(0.0,Math.min(1.0,recoveryFromHigh/Math.max(atr*0.25,1e-12)));
        String side="WAIT";
        if(needleBuy&&(!needleSell||buyEvidence>sellEvidence+0.10))side="BUY";
        else if(needleSell&&(!needleBuy||sellEvidence>buyEvidence+0.10))side="SELL";

        double entry=0, sl=0, tp1=0, tp2=0, rr=0;
        if ("BUY".equals(side)) {
            double wickOffset=OpenActionEntryPolicy.wickTipOffset(
                lower,atr,Math.max(Math.abs(wick.l)*1e-7,Math.abs(ask)*1e-7));
            // Place the LIMIT in the outer 10% of the lower wick, independent of candle body size.
            entry=wick.l+Math.max(0.0,wickOffset);
            sl=wick.l-Math.max(atr*0.12,spread*2);
            double risk=entry-sl;
            if(risk>0){tp1=entry+risk*2.0;tp2=entry+risk*3.5;rr=3.5;}
        } else if ("SELL".equals(side)) {
            double wickOffset=OpenActionEntryPolicy.wickTipOffset(
                upper,atr,Math.max(Math.abs(wick.h)*1e-7,Math.abs(bid)*1e-7));
            // Sell LIMIT mirrors BUY: keep the price within the outer 10% of the upper wick.
            entry=wick.h-Math.max(0.0,wickOffset);
            sl=wick.h+Math.max(atr*0.12,spread*2);
            double risk=sl-entry;
            if(risk>0){tp1=entry-risk*2.0;tp2=entry-risk*3.5;rr=3.5;}
        }
        // Never chase a feed setup that is far from the current executable quote.
        double live= "BUY".equals(side)?ask:bid;
        if(live>0 && atr>0 && Math.abs(live-wick.c)>atr*1.35) {
            side="WAIT"; entry=sl=tp1=tp2=rr=0;
        }
        int points=(needleBuy||needleSell?6:0)+(sweepBuy||sweepSell?2:0)
            +(biasBuy||biasSell?1:0)+(emaBuy||emaSell?1:0)+(rsiBuy||rsiSell?1:0)
            +(macdBuy||macdSell?1:0)+(bosBuy||bosSell?1:0)+(spreadOk?1:0)+(atrOk?1:0);
        int score=Math.min(99,45+points*4);
        boolean selectedEarly=("BUY".equals(side)&&earlyBuy&&!rejectBuy)
            ||("SELL".equals(side)&&earlySell&&!rejectSell);
        boolean selectedSweep=("BUY".equals(side)&&sweepBuy)||("SELL".equals(side)&&sweepSell);
        int confidence="WAIT".equals(side)?(points>=7?3:points>=5?2:1)
            :(selectedEarly?3:(selectedSweep?5:4));
        String setup="WAIT".equals(side)
            ? (needleBuy && needleSell ? "WICK AMBIGU • WAIT" : "Menunggu wick / sweep valid")
            : (selectedEarly?"EARLY WICK PRECURSOR + LIQUIDITY SWEEP"
                :"NEEDLE WICK + REJECTION"+(selectedSweep?" + LIQUIDITY SWEEP":""));
        return new SignalResult(side,bias,entry,sl,tp1,tp2,rr,score,confidence,setup,
            sweepBuy||sweepSell,needleBuy||needleSell,bosBuy||bosSell,
            emaBuy||emaSell,rsiBuy||rsiSell,macdBuy||macdSell,atrOk,spreadOk);
    }

    static double ema(Candle[] a,int n){double k=2.0/(n+1.0),e=a[0].c;for(int i=1;i<a.length;i++)e=a[i].c*k+e*(1-k);return e;}
    static double atr(Candle[] a,int n){int k=Math.min(n,a.length-1);if(k<=0)return 0;double sum=0;for(int i=a.length-k;i<a.length;i++){Candle x=a[i],p=a[i-1];sum+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-p.c),Math.abs(x.l-p.c)));}return sum/k;}
    static double rsi(Candle[] a,int n){int k=Math.min(n,a.length-1);double gain=0,loss=0;for(int i=a.length-k;i<a.length;i++){double d=a[i].c-a[i-1].c;if(d>0)gain+=d;else loss-=d;}if(loss<1e-9)return 100;double rs=(gain/k)/(loss/k);return 100-100/(1+rs);}
}
