package com.xauusd.mobileengine;

import java.util.Arrays;
import java.util.Locale;

/**
 * Historical, candle-by-candle validation for Excellent Machine.
 *
 * No gateway, SharedPreferences, Android classes, or order submission are used.
 * The last input candle is treated as still forming and excluded from signal/exit
 * decisions. Signals are evaluated on a prefix ending at each signal candle,
 * then execution is simulated at the next candle open to avoid close-price lookahead.
 */
public final class ExcellentMachineBacktest {
    private static final int FIRST_SIGNAL_INDEX = 178; // evaluator needs >=180 bars including one forming bar
    private static final int MIN_INPUT_BARS = 220;

    private ExcellentMachineBacktest() {}

    public static final class TargetStats {
        public final int targetR;
        public int setupsSeen;
        public int entries;
        public int wins;
        public int losses;
        public int ambiguous;
        public int open;
        public int rejectedGap;
        public double netR;

        private TargetStats(int targetR) { this.targetR=targetR; }

        public int closed() { return wins+losses+ambiguous; }

        /** Ambiguous candles are included in the denominator and never counted as winners. */
        public double winRatePercent() {
            return closed()==0 ? 0.0 : (100.0* wins/closed());
        }

        /** Gross R; same-bar ambiguous exits are conservatively charged as -1R. */
        public double grossNetR() { return netR; }
    }

    public static final class Report {
        public final boolean valid;
        public final String reason, market, timeframe;
        public final int barsReceived, closedBars, evaluatedCandles, readySetups;
        public final long firstTime, lastTime;
        public final TargetStats oneR, twoR, threeR;

        private Report(boolean valid,String reason,String market,String timeframe,
                       int barsReceived,int closedBars,int evaluatedCandles,int readySetups,
                       long firstTime,long lastTime,TargetStats oneR,TargetStats twoR,TargetStats threeR) {
            this.valid=valid;this.reason=reason;this.market=market;this.timeframe=timeframe;
            this.barsReceived=barsReceived;this.closedBars=closedBars;
            this.evaluatedCandles=evaluatedCandles;this.readySetups=readySetups;
            this.firstTime=firstTime;this.lastTime=lastTime;
            this.oneR=oneR;this.twoR=twoR;this.threeR=threeR;
        }
    }

    private static final class Candidate {
        final int signalIndex;
        final SignalData signal;
        Candidate(int signalIndex, SignalData signal) { this.signalIndex=signalIndex;this.signal=signal; }
    }

    private static final class SignalData {
        final String side;
        final double sl;
        SignalData(String side,double sl) { this.side=side;this.sl=sl; }
    }

    public static Report run(String market,String timeframe,Candle[] bars) {
        TargetStats empty1=new TargetStats(1),empty2=new TargetStats(2),empty3=new TargetStats(3);
        String symbol=market==null?"":market.trim().toUpperCase(Locale.US);
        if(!symbol.startsWith("XAUUSD"))
            return report(false,"BACKTEST TAHAP 2 KHUSUS XAUUSD",market,timeframe,bars,0,0,0,empty1,empty2,empty3);
        if(bars==null || bars.length<MIN_INPUT_BARS)
            return report(false,"RIWAYAT KURANG • PERLU MINIMAL "+MIN_INPUT_BARS+" CANDLE",market,timeframe,bars,0,0,0,empty1,empty2,empty3);

        // Treat the latest OHLC item as the live/forming candle.
        int lastClosed=bars.length-2;
        for(int i=0;i<=lastClosed;i++) {
            if(!validCandle(bars[i]))
                return report(false,"OHLC TIDAK VALID PADA INDEX "+i,market,timeframe,bars,0,0,0,empty1,empty2,empty3);
            if(i>0 && bars[i].t<=bars[i-1].t)
                return report(false,"WAKTU CANDLE DUPLIKAT / TIDAK KRONOLOGIS",market,timeframe,bars,0,0,0,empty1,empty2,empty3);
        }
        if(lastClosed<=FIRST_SIGNAL_INDEX)
            return report(false,"CANDLE TERTUTUP UNTUK UJI BELUM CUKUP",market,timeframe,bars,0,0,0,empty1,empty2,empty3);

        Candidate[] candidates=new Candidate[lastClosed+1];
        int evaluated=0, ready=0;
        for(int signalIndex=FIRST_SIGNAL_INDEX; signalIndex<lastClosed; signalIndex++) {
            // Length signalIndex+2 makes the tested candle the last CLOSED one;
            // the next candle exists as the unused/forming last item to match the live engine contract.
            Candle[] prefix=Arrays.copyOf(bars,signalIndex+2);
            ExcellentMachineEngine.Result signal=ExcellentMachineEngine.evaluate(symbol,timeframe,prefix);
            evaluated++;
            if(signal.ready && ("BUY".equals(signal.side)||"SELL".equals(signal.side))
                    && Double.isFinite(signal.sl) && signal.sl>0) {
                candidates[signalIndex]=new Candidate(signalIndex,new SignalData(signal.side,signal.sl));
                ready++;
            }
        }

        simulateTarget(bars,lastClosed,candidates,empty1);
        simulateTarget(bars,lastClosed,candidates,empty2);
        simulateTarget(bars,lastClosed,candidates,empty3);
        return report(true,"UJI SELESAI • HASIL KOTOR BERDASARKAN OHLC",market,timeframe,bars,
                lastClosed+1,evaluated,ready,empty1,empty2,empty3);
    }

    private static void simulateTarget(Candle[] bars,int lastClosed,Candidate[] candidates,TargetStats stats) {
        int signalIndex=FIRST_SIGNAL_INDEX;
        while(signalIndex<lastClosed) {
            Candidate candidate=candidates[signalIndex];
            if(candidate==null) { signalIndex++; continue; }
            stats.setupsSeen++;
            int entryIndex=signalIndex+1;
            Candle entryBar=bars[entryIndex];
            double entry=entryBar.o;
            String side=candidate.signal.side;
            double sl=candidate.signal.sl;
            double risk="BUY".equals(side)?entry-sl:sl-entry;
            if(!Double.isFinite(entry)||entry<=0||!Double.isFinite(risk)||risk<=0.0) {
                // If the next candle opens across the stop, a price-entry simulation is invalid.
                stats.rejectedGap++;
                signalIndex++;
                continue;
            }
            double target="BUY".equals(side)?entry+risk*stats.targetR:entry-risk*stats.targetR;
            if(!Double.isFinite(target)||target<=0) {
                stats.rejectedGap++;
                signalIndex++;
                continue;
            }
            stats.entries++;
            boolean exited=false;
            int exitIndex=entryIndex;
            for(int j=entryIndex;j<=lastClosed;j++) {
                Candle b=bars[j];
                String outcome=classifyOutcome(side,b,sl,target);
                if("OPEN".equals(outcome)) continue;
                exitIndex=j;
                exited=true;
                if("WIN".equals(outcome)) {
                    stats.wins++;
                    stats.netR+=stats.targetR;
                } else if("LOSS".equals(outcome)) {
                    stats.losses++;
                    stats.netR-=1.0;
                } else {
                    // OHLC does not identify which level came first in this candle.
                    stats.ambiguous++;
                    stats.netR-=1.0; // conservative worst-case
                }
                break;
            }
            if(!exited) {
                stats.open++;
                // One simulated position at a time; do not add later signals while unresolved.
                break;
            }
            // A new signal can only execute at the open AFTER this exit candle.
            signalIndex=Math.max(signalIndex+1,exitIndex);
        }
    }

    static String classifyOutcome(String side,Candle candle,double sl,double target) {
        if(candle==null) return "OPEN";
        boolean buy="BUY".equals(side);
        boolean hitSl=buy?candle.l<=sl:candle.h>=sl;
        boolean hitTarget=buy?candle.h>=target:candle.l<=target;
        if(hitSl&&hitTarget) return "AMBIGUOUS";
        if(hitSl) return "LOSS";
        if(hitTarget) return "WIN";
        return "OPEN";
    }

    private static boolean validCandle(Candle c) {
        return c!=null && Double.isFinite(c.o)&&Double.isFinite(c.h)
                && Double.isFinite(c.l)&&Double.isFinite(c.c)
                && c.o>0&&c.h>=c.l&&c.h>=Math.max(c.o,c.c)
                && c.l<=Math.min(c.o,c.c);
    }

    private static Report report(boolean valid,String reason,String market,String timeframe,Candle[] bars,
                                int closedBars,int evaluated,int ready,TargetStats one,TargetStats two,TargetStats three) {
        long first=bars!=null&&bars.length>0&&bars[0]!=null?bars[0].t:0L;
        long last=bars!=null&&bars.length>1&&bars[bars.length-2]!=null?bars[bars.length-2].t:0L;
        return new Report(valid,reason,market==null?"":market,timeframe==null?"":timeframe,
                bars==null?0:bars.length,closedBars,evaluated,ready,first,last,one,two,three);
    }
}
