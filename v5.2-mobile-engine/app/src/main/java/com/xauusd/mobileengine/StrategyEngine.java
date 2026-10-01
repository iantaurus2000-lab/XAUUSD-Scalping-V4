package com.xauusd.mobileengine;

import java.util.ArrayList;
import java.util.Locale;

public final class StrategyEngine {
    public static final class Candle {
        public long t; public double open, high, low, close;
        public Candle(long t,double open,double high,double low,double close){
            this.t=t;this.open=open;this.high=high;this.low=low;this.close=close;
        }
    }

    public static final class Decision {
        public String side = "WAIT";
        public int score;
        public String reason = "";
        public double entry, sl, tp1, tp2, atr, ema9, ema21, ema50, rsi, macd, macdSignal, bbMid, bbUpper, bbLower;
        public boolean wick, sweep, bos, bias;
        public String key(){ return side+"-"+score+"-"+Long.toString(entry==0?0:(long)(entry*100)); }
        public String summary(){
            return side+" "+score+"/100 | Entry "+fmt(entry)+" SL "+fmt(sl)+" TP1 "+fmt(tp1)+" TP2 "+fmt(tp2);
        }
        private String fmt(double v){return String.format(Locale.US,"%.2f",v);}
    }

    private StrategyEngine(){}

    public static Decision analyze(ArrayList<Candle> m1, ArrayList<Candle> m5, double mid) {
        Decision d = new Decision();
        if (m1.size() < 60 || m5.size() < 30 || mid <= 0) return d;

        int n = m1.size();
        Candle c = m1.get(n-2), p = m1.get(n-3);
        double atr = atr(m1,14,n-1);
        double ema9 = ema(m1,9,n-2), ema21 = ema(m1,21,n-2), ema50 = ema(m1,50,n-2);
        double rsi = rsi(m1,14,n-2);
        double[] macd = macd(m1,n-2);
        double[] bb = bollinger(m1,20,2,n-2);
        double[] stoch = stochastic(m1,14,3,n-2);
        double support = support(m1,20,n-2);
        double resistance = resistance(m1,20,n-2);

        Candle m = m5.get(m5.size()-2), mp = m5.get(m5.size()-3);
        boolean biasBuy = m.close > ema(m5,9,m5.size()-2) && m.close > ema(m5,21,m5.size()-2) && m.close > mp.high;
        boolean biasSell = m.close < ema(m5,9,m5.size()-2) && m.close < ema(m5,21,m5.size()-2) && m.close < mp.low;

        double body = Math.abs(c.close-c.open);
        double upper = c.high-Math.max(c.open,c.close);
        double lower = Math.min(c.open,c.close)-c.low;
        double range = Math.max(c.high-c.low, 1e-9);

        boolean buyWick = lower >= Math.max(body*1.6, atr*0.18) && lower/range >= 0.35 && c.close > c.open;
        boolean sellWick = upper >= Math.max(body*1.6, atr*0.18) && upper/range >= 0.35 && c.close < c.open;
        boolean buySweep = c.low < p.low && c.close > p.low;
        boolean sellSweep = c.high > p.high && c.close < p.high;
        boolean buyBos = c.close > resistance || c.close > p.high;
        boolean sellBos = c.close < support || c.close < p.low;

        boolean trendBuy = ema9 > ema21 && ema21 > ema50;
        boolean trendSell = ema9 < ema21 && ema21 < ema50;
        boolean momentumBuy = rsi >= 50 && rsi <= 72 && macd[0] > macd[1] && stoch[0] > 45;
        boolean momentumSell = rsi <= 50 && rsi >= 28 && macd[0] < macd[1] && stoch[0] < 55;
        boolean locationBuy = c.close <= bb.get(0) || c.low <= support + atr*0.35;
        boolean locationSell = c.close >= bb.get(0) || c.high >= resistance - atr*0.35;

        int buy = 0;
        buy += buyWick ? 22:0;
        buy += buySweep ? 18:0;
        buy += buyBos ? 14:0;
        buy += biasBuy ? 16:0;
        buy += trendBuy ? 10:0;
        buy += momentumBuy ? 10:0;
        buy += locationBuy ? 10:0;

        int sell = 0;
        sell += sellWick ? 22:0;
        sell += sellSweep ? 18:0;
        sell += sellBos ? 14:0;
        sell += biasSell ? 16:0;
        sell += trendSell ? 10:0;
        sell += momentumSell ? 10:0;
        sell += locationSell ? 10:0;

        d.atr=atr; d.ema9=ema9; d.ema21=ema21; d.ema50=ema50; d.rsi=rsi;
        d.macd=macd[0]; d.macdSignal=macd[1]; d.bbMid=bb[0]; d.bbUpper=bb[1]; d.bbLower=bb[2];
        d.wick=buyWick||sellWick; d.sweep=buySweep||sellSweep; d.bos=buyBos||sellBos;
        d.bias=biasBuy||biasSell;

        if (buy >= 75 && buy > sell && biasBuy) {
            d.side="BUY LIMIT"; d.score=buy;
            d.entry = Math.min(mid-atr*0.20, c.low + atr*0.18);
            d.entry = Math.max(d.entry, c.low + atr*0.05);
            if (d.entry >= mid) d.entry = mid-atr*0.25;
            d.sl = Math.min(c.low-atr*0.25, d.entry-atr*0.9);
            double risk = Math.max(d.entry-d.sl, atr*0.7);
            d.tp1 = d.entry + risk;
            d.tp2 = d.entry + risk*2.0;
            d.reason="M5 bullish bias + liquidity sweep + lower-wick rejection + BOS + trend/momentum filter";
        } else if (sell >= 75 && sell > buy && biasSell) {
            d.side="SELL LIMIT"; d.score=sell;
            d.entry = Math.max(mid+atr*0.20, c.high - atr*0.18);
            d.entry = Math.min(d.entry, c.high - atr*0.05);
            if (d.entry <= mid) d.entry = mid+atr*0.25;
            d.sl = Math.max(c.high+atr*0.25, d.entry+atr*0.9);
            double risk = Math.max(d.sl-d.entry, atr*0.7);
            d.tp1 = d.entry - risk;
            d.tp2 = d.entry - risk*2.0;
            d.reason="M5 bearish bias + liquidity sweep + upper-wick rejection + BOS + trend/momentum filter";
        } else {
            d.side="WAIT";
            d.score=Math.max(buy,sell);
            d.reason="Filter belum lengkap; tunggu konfirmasi M5 + wick/sweep/BOS.";
        }
        return d;
    }

    public static double ema(ArrayList<Candle> a,int period,int idx){
        int start=Math.max(0,idx-period*4); double e=a.get(start).close, k=2.0/(period+1);
        for(int i=start+1;i<=idx;i++) e=a.get(i).close*k+e*(1-k);
        return e;
    }

    public static double rsi(ArrayList<Candle> a,int period,int idx){
        int start=Math.max(1,idx-period*6); double gain=0,loss=0;
        for(int i=start;i<=idx;i++){double d=a.get(i).close-a.get(i-1).close; if(d>0) gain+=d; else loss-=d;}
        double rs=loss==0?100:gain/Math.max(loss,1e-9);
        return 100-(100/(1+rs));
    }

    public static double atr(ArrayList<Candle> a,int period,int idx){
        int start=Math.max(1,idx-period*3); double sum=0; int count=0;
        for(int i=start;i<=idx;i++){Candle c=a.get(i),p=a.get(i-1);double tr=Math.max(c.high-c.low,Math.max(Math.abs(c.high-p.close),Math.abs(c.low-p.close)));sum+=tr;count++;}
        return count==0?0:sum/count;
    }

    public static double[] macd(ArrayList<Candle> a,int idx){
        double fast=ema(a,12,idx), slow=ema(a,26,idx);
        double line=fast-slow;
        int s=Math.max(26,idx-8); double avg=0; int n=0;
        for(int i=s;i<=idx;i++){avg += ema(a,12,i)-ema(a,26,i);n++;}
        return new double[]{line,n==0?line:avg/n};
    }

    public static double[] bollinger(ArrayList<Candle> a,int period,double mult,int idx){
        int s=Math.max(0,idx-period+1); double mean=0; int n=0;
        for(int i=s;i<=idx;i++){mean+=a.get(i).close;n++;}
        mean/=Math.max(n,1); double v=0;
        for(int i=s;i<=idx;i++){double x=a.get(i).close-mean;v+=x*x;}
        double sd=Math.sqrt(v/Math.max(n,1));
        return new double[]{mean,mean+sd*mult,mean-sd*mult};
    }

    public static double stochastic(ArrayList<Candle> a,int period,int smooth,int idx){
        int s=Math.max(0,idx-period+1); double hi=-1e99,lo=1e99;
        for(int i=s;i<=idx;i++){hi=Math.max(hi,a.get(i).high);lo=Math.min(lo,a.get(i).low);}
        return (a.get(idx).close-lo)/Math.max(hi-lo,1e-9)*100;
    }

    public static double support(ArrayList<Candle> a,int lookback,int idx){
        int s=Math.max(1,idx-lookback+1); double lo=1e99;
        for(int i=s;i<=idx;i++) lo=Math.min(lo,a.get(i).low);
        return lo;
    }

    public static double resistance(ArrayList<Candle> a,int lookback,int idx){
        int s=Math.max(1,idx-lookback+1); double hi=-1e99;
        for(int i=s;i<=idx;i++) hi=Math.max(hi,a.get(i).high);
        return hi;
    }
}
