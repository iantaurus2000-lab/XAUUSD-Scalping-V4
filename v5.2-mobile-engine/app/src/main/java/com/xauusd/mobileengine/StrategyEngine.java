package com.xauusd.mobileengine;

import java.util.ArrayList;
import java.util.Locale;

public final class StrategyEngine {
    public static final class Candle {
        public long t; public double open,high,low,close;
        public Candle(long t,double open,double high,double low,double close){this.t=t;this.open=open;this.high=high;this.low=low;this.close=close;}
    }

    public static final class Decision {
        public String side="WAIT", reason="", pattern="WAIT";
        public int score,candidateScore; public boolean wick,sweep,bos,bias,earlyReady,entryWatch;
        public String candidateSide="WAIT";
        public double entry,sl,tp1,tp2,atr,ema9,ema21,ema50,rsi,macd,macdSignal,bbMid,bbUpper,bbLower,support,resistance;
        public long candleTime,secondsToClose;
        public String key(){return side+"-"+score+"-"+Long.toString(entry==0?0:(long)(entry*100));}
        public String summary(){return side+" "+score+"/100 | Entry "+fmt(entry)+" SL "+fmt(sl)+" TP1 "+fmt(tp1)+" TP2 "+fmt(tp2)+" | "+pattern;}
        private String fmt(double v){return String.format(Locale.US,"%.2f",v);}
    }

    private StrategyEngine(){}

    public static Decision analyze(ArrayList<Candle> m1,ArrayList<Candle> m5,double mid){
        Decision d=new Decision();
        if(m1.size()<60||m5.size()<30||mid<=0)return d;
        int n=m1.size(),m=m5.size();
        Candle c=m1.get(n-1),p=m1.get(n-2),c5=m5.get(m-1);
        double atr=Math.max(atr(m1,14,n-1),0.10);
        double ema9=ema(m1,9,n-1),ema21=ema(m1,21,n-1),ema50=ema(m1,50,n-1);
        double rsi=rsi(m1,14,n-1);
        double[] mac=macd(m1,n-1),bb=bollinger(m1,20,2,n-1);
        double macdLine=mac[0],macdSig=mac[1],stoch=stochastic(m1,14,3,n-1);
        double support=support(m1,20,n-1),resistance=resistance(m1,20,n-1);
        double e59=ema(m5,9,m-1),e521=ema(m5,21,m-1);

        boolean biasBuy=c5.close>e59&&c5.close>e521,biasSell=c5.close<e59&&c5.close<e521;
        double body=Math.abs(c.close-c.open),upper=c.high-Math.max(c.open,c.close);
        double lower=Math.min(c.open,c.close)-c.low,range=Math.max(c.high-c.low,1e-9);
        boolean buyWick=lower>=Math.max(body*1.25,atr*.12)&&lower/range>=.28&&c.close>=c.open;
        boolean sellWick=upper>=Math.max(body*1.25,atr*.12)&&upper/range>=.28&&c.close<=c.open;
        boolean buySweep=c.low<p.low&&c.close>p.low,sellSweep=c.high>p.high&&c.close<p.high;
        boolean buyBos=c.close>p.high||c.close>resistance,sellBos=c.close<p.low||c.close<support;

        double gap=(ema9-ema21)/atr;
        double buyBiasQ=biasBuy?1.0:((c5.close>e59||c5.close>e521)?0.55:0.0);
        double sellBiasQ=biasSell?1.0:((c5.close<e59||c5.close<e521)?0.55:0.0);
        double buyTrendQ=clamp(.5+gap*.8,0,1),sellTrendQ=clamp(.5-gap*.8,0,1);
        double md=macdLine-macdSig;
        double buyMomQ=.55*clamp((rsi-40)/35.0,0,1)+.45*clamp(.5+md/(atr*.05),0,1);
        double sellMomQ=.55*clamp((65-rsi)/35.0,0,1)+.45*clamp(.5-md/(atr*.05),0,1);
        double buyLocQ=1.0-clamp((c.close-support)/(atr*3.0),0,1);
        double sellLocQ=1.0-clamp((resistance-c.close)/(atr*3.0),0,1);
        double buyWickQ=clamp((lower/range)/.60,0,1),sellWickQ=clamp((upper/range)/.60,0,1);

        int buy=(int)Math.round(buyWickQ*20)+(buySweep?18:0)+(buyBos?14:0)+(int)Math.round(buyBiasQ*18)+(int)Math.round(buyTrendQ*10)+(int)Math.round(buyMomQ*10)+(int)Math.round(buyLocQ*10);
        int sell=(int)Math.round(sellWickQ*20)+(sellSweep?18:0)+(sellBos?14:0)+(int)Math.round(sellBiasQ*18)+(int)Math.round(sellTrendQ*10)+(int)Math.round(sellMomQ*10)+(int)Math.round(sellLocQ*10);

        d.atr=atr;d.ema9=ema9;d.ema21=ema21;d.ema50=ema50;d.rsi=rsi;d.macd=macdLine;d.macdSignal=macdSig;
        d.bbMid=bb[0];d.bbUpper=bb[1];d.bbLower=bb[2];d.support=support;d.resistance=resistance;
        d.wick=buyWick||sellWick;d.sweep=buySweep||sellSweep;d.bos=buyBos||sellBos;d.bias=biasBuy||biasSell;
        d.pattern=candlePattern(c,p);d.candleTime=c.t;d.secondsToClose=secondsToClose(c5.t,5);d.earlyReady=d.secondsToClose>0&&d.secondsToClose<=300;
        d.score=Math.max(buy,sell);d.candidateScore=d.score;d.candidateSide=buy>=sell?"BUY LIMIT":"SELL LIMIT";d.entryWatch=d.score>=45;

        if(buy>=sell){
            d.entry=Math.min(mid-atr*.18,c.low+atr*.16);d.entry=Math.max(d.entry,c.low+atr*.05);if(d.entry>=mid)d.entry=mid-atr*.20;
            d.sl=Math.min(c.low-atr*.35,d.entry-atr*.80);double risk=Math.max(d.entry-d.sl,atr*.70);d.tp1=d.entry+risk;d.tp2=d.entry+risk*2;
        }else{
            d.entry=Math.max(mid+atr*.18,c.high-atr*.16);d.entry=Math.min(d.entry,c.high-atr*.05);if(d.entry<=mid)d.entry=mid+atr*.20;
            d.sl=Math.max(c.high+atr*.35,d.entry+atr*.80);double risk=Math.max(d.sl-d.entry,atr*.70);d.tp1=d.entry-risk;d.tp2=d.entry-risk*2;
        }

        if(buy>=75&&buy>sell&&biasBuy){d.side="BUY LIMIT";d.reason="M5 BUY + "+d.pattern+" + Liquidity Sweep + Wick Rejection + BOS";}
        else if(sell>=75&&sell>buy&&biasSell){d.side="SELL LIMIT";d.reason="M5 SELL + "+d.pattern+" + Liquidity Sweep + Wick Rejection + BOS";}
        else{d.side="WAIT";d.reason="Candidate "+d.candidateSide+" • "+d.candidateScore+"/100 • menunggu konfirmasi final.";}
        return d;
    }

    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}

    public static String candlePattern(Candle c,Candle p){
        double body=Math.abs(c.close-c.open),range=Math.max(c.high-c.low,1e-9);
        double up=c.high-Math.max(c.open,c.close),down=Math.min(c.open,c.close)-c.low;
        boolean bull=c.close>c.open,bullP=p.close>p.open;
        if(body/range<.12)return "DOJI";
        if(bull&&!bullP&&c.open<=p.close&&c.close>=p.open)return "BULL ENGULF";
        if(!bull&&bullP&&c.open>=p.close&&c.close<=p.open)return "BEAR ENGULF";
        if(down>=body*2.2&&down/range>.55)return "HAMMER / PIN";
        if(up>=body*2.2&&up/range>.55)return "SHOOTING STAR / PIN";
        if(body/range>.82&&up/range<.08&&down/range<.08)return bull?"BULL MARUBOZU":"BEAR MARUBOZU";
        if(c.high<=p.high&&c.low>=p.low)return "INSIDE BAR";
        if(c.high>=p.high&&c.low<=p.low)return "OUTSIDE BAR";
        return bull?"BULL MOMENTUM":"BEAR MOMENTUM";
    }

    private static long secondsToClose(long t,int minutes){
        long ms=t<100000000000L?t*1000L:t; long end=((ms/(minutes*60000L))+1)*(minutes*60000L);
        return Math.max(0,(end-System.currentTimeMillis())/1000L);
    }

    public static double ema(ArrayList<Candle>a,int period,int idx){int start=Math.max(0,idx-period*4);double e=a.get(start).close,k=2.0/(period+1);for(int i=start+1;i<=idx;i++)e=a.get(i).close*k+e*(1-k);return e;}
    public static double rsi(ArrayList<Candle>a,int period,int idx){int start=Math.max(1,idx-period*6);double gain=0,loss=0;for(int i=start;i<=idx;i++){double d=a.get(i).close-a.get(i-1).close;if(d>0)gain+=d;else loss-=d;}double rs=loss==0?100:gain/Math.max(loss,1e-9);return 100-(100/(1+rs));}
    public static double atr(ArrayList<Candle>a,int period,int idx){int start=Math.max(1,idx-period*3);double sum=0;int count=0;for(int i=start;i<=idx;i++){Candle c=a.get(i),p=a.get(i-1);double tr=Math.max(c.high-c.low,Math.max(Math.abs(c.high-p.close),Math.abs(c.low-p.close)));sum+=tr;count++;}return count==0?0:sum/count;}
    public static double[] macd(ArrayList<Candle>a,int idx){double fast=ema(a,12,idx),slow=ema(a,26,idx),line=fast-slow;int s=Math.max(26,idx-8);double avg=0;int n=0;for(int i=s;i<=idx;i++){avg+=ema(a,12,i)-ema(a,26,i);n++;}return new double[]{line,n==0?line:avg/n};}
    public static double[] bollinger(ArrayList<Candle>a,int period,double mult,int idx){int s=Math.max(0,idx-period+1);double mean=0;int n=0;for(int i=s;i<=idx;i++){mean+=a.get(i).close;n++;}mean/=Math.max(n,1);double v=0;for(int i=s;i<=idx;i++){double x=a.get(i).close-mean;v+=x*x;}double sd=Math.sqrt(v/Math.max(n,1));return new double[]{mean,mean+sd*mult,mean-sd*mult};}
    public static double stochastic(ArrayList<Candle>a,int period,int smooth,int idx){int s=Math.max(0,idx-period+1);double hi=-1e99,lo=1e99;for(int i=s;i<=idx;i++){hi=Math.max(hi,a.get(i).high);lo=Math.min(lo,a.get(i).low);}return(a.get(idx).close-lo)/Math.max(hi-lo,1e-9)*100;}
    public static double support(ArrayList<Candle>a,int lookback,int idx){int s=Math.max(0,idx-lookback+1);double lo=1e99;for(int i=s;i<=idx;i++)lo=Math.min(lo,a.get(i).low);return lo;}
    public static double resistance(ArrayList<Candle>a,int lookback,int idx){int s=Math.max(0,idx-lookback+1);double hi=-1e99;for(int i=s;i<=idx;i++)hi=Math.max(hi,a.get(i).high);return hi;}
}