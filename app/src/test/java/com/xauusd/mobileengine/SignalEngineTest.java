package com.xauusd.mobileengine;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    private Candle[] history(double open,double high,double low,double close) {
        Candle[] bars=new Candle[40];
        for(int i=0;i<bars.length-1;i++){
            double c=100.0+(i%3)*0.01;
            bars[i]=new Candle((i+1)*60_000L,c,c+0.20,c-0.20,c);
        }
        bars[bars.length-1]=new Candle(40*60_000L,open,high,low,close);
        return bars;
    }

    private Candle[] context() {
        Candle[] bars=new Candle[40];
        for(int i=0;i<bars.length;i++){
            double c=100.0+(i%3)*0.01;
            bars[i]=new Candle((i+1)*300_000L,c,c+0.20,c-0.20,c);
        }
        return bars;
    }

    @Test public void recognizesBuyAtFirstSmallReclaimAfterLowSweep() {
        Candle[] m1=history(100.0,100.1,98.8,98.9);
        SignalResult s=SignalEngine.evaluate(m1,context(),98.89,98.91,98.89,98.91);
        assertEquals("BUY",s.side);
        assertTrue(s.liquiditySweep);
        assertTrue(s.entry>0&&s.entry<98.91);
        assertTrue(s.sl<s.entry&&s.tp1>s.entry);
        assertTrue(s.setup.contains("EARLY WICK")||s.setup.contains("NEEDLE WICK"));
    }

    @Test public void recognizesSellSymmetricallyAtFirstRecoilAfterHighSweep() {
        Candle[] m1=history(100.0,101.2,99.9,101.1);
        SignalResult s=SignalEngine.evaluate(m1,context(),101.09,101.11,101.09,101.11);
        assertEquals("SELL",s.side);
        assertTrue(s.liquiditySweep);
        assertTrue(s.entry>101.09);
        assertTrue(s.sl>s.entry&&s.tp1<s.entry);
    }
}