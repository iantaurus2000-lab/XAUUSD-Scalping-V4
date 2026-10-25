package com.xauusd.mobileengine;

/**
 * EXCELLENT has its own closed-candle indicator calculations and entry geometry.
 * It does not call or mutate STANDAR's SignalEngine.
 */
public final class ExcellentSignalEngine {
    private ExcellentSignalEngine() {}

    public static SignalResult evaluate(Candle[] m1,Candle[] m5,double bid,double ask,
                                        double spreadBid,double spreadAsk) {
        if(m1==null||m1.length<30||m5==null||m5.length<60)
            return wait("EXCELLENT: menunggu OHLC M1/M5", "NEUTRAL",false,false);
        if(!QuoteSafety.valid(bid,ask))
            return wait("EXCELLENT: quote MT5 tidak valid","NEUTRAL",false,false);

        int i=m1.length-2, j=m5.length-2;
        Candle c=m1[i], prev=m1[i-1];
        if(!valid(c)||!valid(prev))
            return wait("EXCELLENT: candle tertutup tidak valid","NEUTRAL",false,false);

        double atr=atr(m1,14), atr5=atr(m5,14);
        if(!(atr>0.0)||!(atr5>0.0))
            return wait("EXCELLENT: ATR belum siap","NEUTRAL",false,false);
        boolean spreadOk=QuoteSafety.spreadAcceptable(spreadBid,spreadAsk,atr);
        if(!spreadOk)return wait("EXCELLENT: spread melebihi batas ATR","NEUTRAL",true,false);

        double ema20=ema(m5,20,j),ema50=ema(m5,50,j);
        boolean buyBias=ema20>ema50;
        boolean sellBias=ema20<ema50;
        String bias=buyBias?"BUY":sellBias?"SELL":"NEUTRAL";
        if("NEUTRAL".equals(bias))
            return wait("EXCELLENT: EMA20/50 M5 belum punya arah","NEUTRAL",true,true);

        double body=Math.abs(c.c-c.o), range=Math.max(c.h-c.l,1e-9);
        double lower=Math.max(0.0,Math.min(c.o,c.c)-c.l);
        double upper=Math.max(0.0,c.h-Math.max(c.o,c.c));
        boolean bullWick=lower>=Math.max(body*0.6,atr*0.05)&&c.c>=c.l+range*0.58;
        boolean bearWick=upper>=Math.max(body*0.6,atr*0.05)&&c.c<=c.h-range*0.42;
        boolean bullSweep=c.l<prev.l&&c.c>prev.l;
        boolean bearSweep=c.h>prev.h&&c.c<prev.h;

        boolean buy=buyBias&&bullWick&&bullSweep;
        boolean sell=sellBias&&bearWick&&bearSweep;
        if(!buy&&!sell)
            return wait("EXCELLENT: tunggu EMA20/50 + wick + liquidity sweep selaras",bias,true,true);

        double rsi=rsi(m1,14,i);
        if((buy&&rsi<48.0)||(sell&&rsi>52.0))
            return wait("EXCELLENT: RSI14 belum konfirmasi",bias,true,true);

        double spread=Math.max(0.0,spreadAsk-spreadBid);
        double buffer=Math.max(atr*0.18,spread*1.25);
        double entry=buy?c.l+Math.max(atr*0.04,spread*0.25)
                        :c.h-Math.max(atr*0.04,spread*0.25);
        double sl=buy?c.l-buffer:c.h+buffer;
        if(buy&&entry-sl<atr*0.45)sl=entry-atr*0.75;
        if(sell&&sl-entry<atr*0.45)sl=entry+atr*0.75;
        double risk=Math.abs(entry-sl);
        double tp1=buy?entry+risk:entry-risk;
        double tp2=buy?entry+risk*1.8:entry-risk*1.8;
        boolean geometry=buy?(entry<ask&&sl<entry&&tp1>entry&&tp2>tp1)
                            :(entry>bid&&sl>entry&&tp1<entry&&tp2<tp1);
        if(!geometry)
            return wait("EXCELLENT: LIMIT bukan di sisi harga yang valid",bias,true,true);

        double trend=Math.min(1.0,Math.abs(ema20-ema50)/Math.max(atr5*0.25,1e-9));
        double wickStrength=Math.min(1.0,Math.max(lower,upper)/Math.max(atr,1e-9));
        int score=Math.min(96,80+(int)Math.round(8*trend)+(int)Math.round(6*wickStrength));
        int confidence=score>=90?5:score>=85?4:3;
        return new SignalResult(buy?"BUY":"SELL",bias,entry,sl,tp1,tp2,
            Math.abs(tp1-entry)/Math.max(risk,1e-9),score,confidence,
            "EXCELLENT • EMA20/50 M5 + RSI14 + ATR14 + wick/sweep M1",
            true,true,true,true,true,false,true,true);
    }

    private static boolean valid(Candle c){
        return c!=null&&Double.isFinite(c.o)&&Double.isFinite(c.h)&&Double.isFinite(c.l)
            &&Double.isFinite(c.c)&&c.o>0&&c.h>=Math.max(c.o,c.c)
            &&c.l<=Math.min(c.o,c.c)&&c.h>=c.l;
    }
    private static double ema(Candle[] a,int period,int end){
        if(a==null||period<1||end<0||end>=a.length)return 0.0;
        double k=2.0/(period+1.0),v=a[0].c;
        for(int n=1;n<=end;n++)v=a[n].c*k+v*(1.0-k);
        return v;
    }
    private static double atr(Candle[] a,int period){
        if(a==null||a.length<period+2)return 0.0;
        int end=a.length-2,start=Math.max(1,end-period+1);
        double sum=0;int count=0;
        for(int n=start;n<=end;n++){
            if(!valid(a[n])||!valid(a[n-1]))return 0.0;
            sum+=Math.max(a[n].h-a[n].l,Math.max(Math.abs(a[n].h-a[n-1].c),Math.abs(a[n].l-a[n-1].c)));
            count++;
        }
        return count>0?sum/count:0.0;
    }
    private static double rsi(Candle[] a,int period,int end){
        if(a==null||end<period)return 50.0;
        double gain=0,loss=0;
        for(int n=end-period+1;n<=end;n++){
            double delta=a[n].c-a[n-1].c;
            if(delta>0)gain+=delta;else loss-=delta;
        }
        if(loss<=1e-12)return gain>0?100.0:50.0;
        double rs=(gain/period)/(loss/period);
        return 100.0-(100.0/(1.0+rs));
    }
    private static SignalResult wait(String setup,String bias,boolean atrOk,boolean spreadOk){
        return new SignalResult("WAIT",bias,0,0,0,0,0,0,1,setup,
            false,false,false,false,false,false,atrOk,spreadOk);
    }
}
