package com.xauusd.mobileengine;

public final class SignalEngine {
    private SignalEngine() {}

    public static SignalResult evaluate(Candle[] m1, Candle[] m5, double bid, double ask) {
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

        // Scan recent candles for a needle wick instead of demanding every indicator agree.
        // Prefer the latest qualifying wick; older candidates are allowed only if price
        // has not already run far away from the rejection area.
        int n = m1.length;
        int bestIndex = -1;
        boolean bestBuy = false;
        double bestStrength = 0;
        // Only the newest M1 candle may create a READY setup; older wick candles expire.
        for (int i = n - 1; i < n; i++) {
            Candle c = m1[i];
            double b = Math.max(Math.abs(c.c - c.o), Math.max(atr * 0.035, 0.00001));
            double up = c.h - Math.max(c.o, c.c);
            double down = Math.min(c.o, c.c) - c.l;
            double localRange = Math.max(c.h - c.l, atr);
            double priorLow = Math.min(m1[i-1].l, m1[i-2].l);
            double priorHigh = Math.max(m1[i-1].h, m1[i-2].h);
            boolean swBuy = c.l < priorLow && c.c > priorLow;
            boolean swSell = c.h > priorHigh && c.c < priorHigh;
            boolean rejectBuy = down >= b * 1.8 && down >= localRange * 0.38 && c.c >= c.l + (c.h-c.l)*0.48;
            boolean rejectSell = up >= b * 1.8 && up >= localRange * 0.38 && c.c <= c.l + (c.h-c.l)*0.52;
            double buyStrength = down / b + (swBuy ? 1.6 : 0) + (i == n-1 ? 0.25 : 0);
            double sellStrength = up / b + (swSell ? 1.6 : 0) + (i == n-1 ? 0.25 : 0);
            if (rejectBuy && buyStrength > bestStrength) {
                bestIndex=i; bestBuy=true; bestStrength=buyStrength;
            }
            if (rejectSell && sellStrength > bestStrength) {
                bestIndex=i; bestBuy=false; bestStrength=sellStrength;
            }
        }

        boolean needleBuy = bestIndex >= 0 && bestBuy;
        boolean needleSell = bestIndex >= 0 && !bestBuy;
        Candle wick = bestIndex >= 0 ? m1[bestIndex] : z;
        double prevLow = Math.min(p.l, m1[n-3].l);
        double prevHigh = Math.max(p.h, m1[n-3].h);
        boolean sweepBuy = bestIndex >= 0 && wick.l < Math.min(m1[bestIndex-1].l, m1[bestIndex-2].l) && wick.c > Math.min(m1[bestIndex-1].l, m1[bestIndex-2].l);
        boolean sweepSell = bestIndex >= 0 && wick.h > Math.max(m1[bestIndex-1].h, m1[bestIndex-2].h) && wick.c < Math.max(m1[bestIndex-1].h, m1[bestIndex-2].h);

        double rsi = rsi(m1,14), macd = ema(m1,12)-ema(m1,26);
        boolean rsiBuy=rsi>=50&&rsi<=72, rsiSell=rsi<=50&&rsi>=28;
        boolean macdBuy=macd>0, macdSell=macd<0;
        boolean emaBuy=e9>e21&&e21>=e50, emaSell=e9<e21&&e21<=e50;
        double spread=ask>0&&bid>0?ask-bid:0;
        boolean spreadOk=spread<=Math.max(0.50,atr*0.20), atrOk=atr>0;
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
        String setup="WAIT".equals(side)?"Menunggu wick valid":(needleBuy||needleSell)
            ?"NEEDLE WICK + REJECTION"+((sweepBuy||sweepSell)?" + LIQUIDITY SWEEP":"")
            :"Momentum fallback";
        return new SignalResult(side,bias,entry,sl,tp1,tp2,rr,score,confidence,setup,
            sweepBuy||sweepSell,needleBuy||needleSell,bosBuy||bosSell,
            emaBuy||emaSell,rsiBuy||rsiSell,macdBuy||macdSell,atrOk,spreadOk);
    }

    static double ema(Candle[] a,int n){double k=2.0/(n+1.0),e=a[0].c;for(int i=1;i<a.length;i++)e=a[i].c*k+e*(1-k);return e;}
    static double atr(Candle[] a,int n){int k=Math.min(n,a.length-1);if(k<=0)return 0;double sum=0;for(int i=a.length-k;i<a.length;i++){Candle x=a[i],p=a[i-1];sum+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-p.c),Math.abs(x.l-p.c)));}return sum/k;}
    static double rsi(Candle[] a,int n){int k=Math.min(n,a.length-1);double gain=0,loss=0;for(int i=a.length-k;i<a.length;i++){double d=a[i].c-a[i-1].c;if(d>0)gain+=d;else loss-=d;}if(loss<1e-9)return 100;double rs=(gain/k)/(loss/k);return 100-100/(1+rs);}
}
