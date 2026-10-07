package com.xauusd.mobileengine;

public final class SignalResult {
    public final String side, m5Bias, setup;
    public final double entry, sl, tp1, tp2, rr;
    public final int score, confidence;
    public final boolean liquiditySweep, wickRejection, bos, emaFilter, rsiFilter, macdFilter, atrFilter, spreadFilter;

    public SignalResult(String side, String m5Bias, double entry, double sl, double tp1, double tp2,
                        double rr, int score, int confidence, String setup,
                        boolean liquiditySweep, boolean wickRejection, boolean bos,
                        boolean emaFilter, boolean rsiFilter, boolean macdFilter,
                        boolean atrFilter, boolean spreadFilter) {
        this.side=side; this.m5Bias=m5Bias; this.entry=entry; this.sl=sl; this.tp1=tp1; this.tp2=tp2;
        this.rr=rr; this.score=score; this.confidence=confidence; this.setup=setup;
        this.liquiditySweep=liquiditySweep; this.wickRejection=wickRejection; this.bos=bos;
        this.emaFilter=emaFilter; this.rsiFilter=rsiFilter; this.macdFilter=macdFilter;
        this.atrFilter=atrFilter; this.spreadFilter=spreadFilter;
    }
}
