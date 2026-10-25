package com.xauusd.mobileengine;

/**
 * EXCELLENT is a separate signal policy layered on closed-candle data.
 * It uses stricter confirmations than STANDAR and never changes STANDAR settings.
 */
public final class ExcellentSignalEngine {
    private ExcellentSignalEngine() {}

    public static SignalResult evaluate(Candle[] m1, Candle[] m5, double bid, double ask,
                                        double spreadBid, double spreadAsk) {
        SignalResult base = SignalEngine.evaluate(m1,m5,bid,ask,spreadBid,spreadAsk);
        if (base == null || "WAIT".equals(base.side)) return base;
        if (!base.atrFilter || !base.spreadFilter)
            return wait("EXCELLENT: ATR/SPREAD belum aman",base.m5Bias,base.atrFilter,base.spreadFilter);
        if (!base.wickRejection)
            return wait("EXCELLENT: menunggu rejection wick",base.m5Bias,base.atrFilter,base.spreadFilter);
        if (!base.liquiditySweep)
            return wait("EXCELLENT: menunggu liquidity sweep",base.m5Bias,base.atrFilter,base.spreadFilter);
        if (!base.emaFilter)
            return wait("EXCELLENT: arah belum selaras EMA M5",base.m5Bias,base.atrFilter,base.spreadFilter);
        if (base.score < 82)
            return wait("EXCELLENT: score di bawah 82",base.m5Bias,base.atrFilter,base.spreadFilter);
        return new SignalResult(base.side,base.m5Bias,base.entry,base.sl,base.tp1,base.tp2,
            base.rr,Math.min(99,base.score+2),base.confidence,
            "EXCELLENT • "+base.setup,base.liquiditySweep,base.wickRejection,base.bos,
            base.emaFilter,base.rsiFilter,base.macdFilter,base.atrFilter,base.spreadFilter);
    }

    private static SignalResult wait(String setup,String bias,boolean atrOk,boolean spreadOk){
        return new SignalResult("WAIT",bias,0,0,0,0,0,0,1,setup,
            false,false,false,false,false,false,atrOk,spreadOk);
    }
}
