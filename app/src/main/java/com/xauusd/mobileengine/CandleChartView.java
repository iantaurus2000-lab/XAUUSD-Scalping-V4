package com.xauusd.mobileengine;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class CandleChartView extends View {
    Paint p = new Paint(1);
    ArrayList<Candle> cs = new ArrayList<>();
    double bid, ask;
    float cw = 16, ox = 0, oy = 0, lx, ly, ld, cx, cy;
    float leftPad = 54, rightAxis = 108, topPad = 36, bottomPad = 74, lastCandlePad = 52;
    boolean showEMA9 = true, showEMA21 = true, showEMA50 = true;
    boolean showSR = true, showFibo = true, showRSI = true, showMACD = true, showATR = true;
    boolean showSignals = true, showWickBos = true, showTradeLevels = true, showPosition = true, showBidAsk = true;
    boolean follow = true;
    String timeframe = "M1";
    SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);

    public CandleChartView(Context c) {
        super(c);
        setBackgroundColor(Color.rgb(9, 12, 16));
    }

    public void setTimeframe(String tf) {
        timeframe = tf == null ? "M1" : tf;
        invalidate();
    }

    public void setData(Candle[] a) {
        cs.clear();
        if (a != null) Collections.addAll(cs, a);
        invalidate();
    }

    public void setTick(double b, double a, String tf) {
        bid = b;
        ask = a;
        if (tf != null) timeframe = tf;
        if (!cs.isEmpty()) {
            Candle z = cs.get(cs.size() - 1);
            double q = (b + a) / 2.0;
            z.c = q;
            z.h = Math.max(z.h, q);
            z.l = Math.min(z.l, q);
        }
        invalidate();
    }

    public void goLive() {
        follow = true;
        oy = 0;
        positionLatestCandle();
        invalidate();
    }

    void positionLatestCandle() {
        if (cs.isEmpty()) { ox = 0; return; }
        float L = leftPad, R = getWidth() - rightAxis, step = cw + 5;
        float latest = L + (cs.size() - 1) * step + step / 2;
        ox = R - lastCandlePad - latest;
    }

    double signalEntry, signalSl, signalTp1, signalTp2;
    boolean signalActive = false;

    public boolean isEmpty() { return cs.isEmpty(); }
    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public Candle[] getDataSnapshot() { return cs.toArray(new Candle[0]); }

    public void setSignalLevels(double entry, double sl, double tp1, double tp2, boolean active) {
        signalEntry = entry; signalSl = sl; signalTp1 = tp1; signalTp2 = tp2; signalActive = active;
        invalidate();
    }

    public boolean isFollow() { return follow; }

    public void setShowEMA9(boolean v) { showEMA9 = v; invalidate(); }
    public void setShowEMA21(boolean v) { showEMA21 = v; invalidate(); }
    public void setShowEMA50(boolean v) { showEMA50 = v; invalidate(); }
    public void setShowSR(boolean v) { showSR = v; invalidate(); }
    public void setShowFibo(boolean v) { showFibo = v; invalidate(); }
    public void setShowRSI(boolean v) { showRSI = v; invalidate(); }
    public void setShowMACD(boolean v) { showMACD = v; invalidate(); }
    public void setShowATR(boolean v) { showATR = v; invalidate(); }
    public void setShowSignals(boolean v) { showSignals = v; invalidate(); }
    public void setShowWickBos(boolean v) { showWickBos = v; invalidate(); }
    public void setShowTradeLevels(boolean v) { showTradeLevels = v; invalidate(); }
    public void setShowPosition(boolean v) { showPosition = v; invalidate(); }
    public void setShowBidAsk(boolean v) { showBidAsk = v; invalidate(); }

    double niceStep(double range) {
        double raw = Math.max(0.01, range / 10.0);
        double pow = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / pow;
        double nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return nice * pow;
    }

    void boldPriceBox(Canvas c, String s, float centerY, int fill, int fg) {
        float x = getWidth() - rightAxis + 5;
        float w = getWidth() - x - 5;
        float top = Math.max(3, Math.min(getHeight() - 25, centerY - 11));
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRoundRect(x, top, x + w, top + 22, 5, 5, p);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(12);
        p.setColor(fg);
        c.drawText(s, x + 5, top + 15, p);
        p.setTypeface(Typeface.DEFAULT);
    }

    void text(Canvas c, String s, float x, float y, float z, int col) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(z);
        p.setColor(col);
        c.drawText(s, x, y, p);
    }

    void ln(Canvas c, float a, float b, float d, float e, int col, float sw) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(col);
        c.drawLine(a, b, d, e, p);
        p.setStyle(Paint.Style.FILL);
    }

    String f(double v) { return String.format(Locale.US, "%.2f", v); }

    float py(double q, double lo, double hi, float t, float b) {
        return (float)(t + (hi - q) / (hi - lo) * (b - t)) + oy;
    }

    void tag(Canvas c, String s, float y, int col, float x) {
        p.setColor(Color.argb(225, 18, 22, 28));
        c.drawRect(x, y - 13, Math.min(getWidth() - 2, x + 76), y + 4, p);
        text(c, s, x + 3, y, 9, col);
    }

    void ema(Canvas c, int n, int col, float L, float R, float T, float B,
             double lo, double hi, float step) {
        if (cs.size() < n) return;
        double k = 2.0 / (n + 1);
        double e = cs.get(0).c;
        float px = 0, py = 0;
        boolean first = true;

        for (int i = 0; i < cs.size(); i++) {
            e = cs.get(i).c * k + e * (1 - k);
            float x = L + i * step + ox + step / 2;
            float y = py(e, lo, hi, T, B);
            if (x < L || x > R) {
                px = x;
                py = y;
                continue;
            }
            if (!first) ln(c, px, py, x, y, col, 2);
            px = x;
            py = y;
            first = false;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float L = leftPad, R = getWidth() - rightAxis, T = topPad, B = getHeight() - bottomPad;
        if (follow) positionLatestCandle();
        c.drawColor(Color.rgb(9, 12, 16));

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(14, 19, 25));
        c.drawRect(R, 0, getWidth(), getHeight(), p);
        ln(c, R, 0, R, getHeight(), Color.rgb(65, 78, 92), 2);
        text(c, "PRICE", R + 8, 16, 9, Color.LTGRAY);

        for (int i = 0; i <= 6; i++) {
            float y = T + i * (B - T) / 6;
            ln(c, L, y, R, y, Color.rgb(32, 38, 46), 1);
        }
        for (int i = 0; i <= 8; i++) {
            float x = L + i * (R - L) / 8;
            ln(c, x, T, x, B, Color.rgb(24, 29, 36), 1);
        }

        p.setColor(Color.rgb(12, 20, 29));
        c.drawRect(L, 0, R, T - 4, p);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        text(c, "XAUUSD  •  " + timeframe, L + 8, 23, 13, Color.rgb(0, 230, 145));
        p.setTypeface(Typeface.DEFAULT);

        if (cs.isEmpty()) {
            text(c, "Menunggu candle XAUUSD...", L + 15, (T + B) / 2, 18, Color.LTGRAY);
            return;
        }

        double lo = 1e99, hi = -1e99;
        for (Candle z : cs) {
            lo = Math.min(lo, z.l);
            hi = Math.max(hi, z.h);
        }
        double pad = Math.max((hi - lo) * .10, .40);
        lo -= pad;
        hi += pad;

        float step = cw + 5;
        int n = cs.size();
        int st = Math.max(0, (int)Math.floor((-ox) / step) - 3);
        int en = Math.min(n, (int)Math.ceil((R - L - ox) / step) + 3);

        for (int i = st; i < en; i++) {
            Candle z = cs.get(i);
            float x = L + i * step + ox + step / 2;
            float yh = py(z.h, lo, hi, T, B);
            float yl = py(z.l, lo, hi, T, B);
            float yo = py(z.o, lo, hi, T, B);
            float yc = py(z.c, lo, hi, T, B);
            int col = z.c >= z.o ? Color.rgb(0, 215, 145) : Color.rgb(245, 75, 88);
            ln(c, x, yh, x, yl, col, 2);
            p.setColor(col);
            c.drawRect(x - cw / 2, Math.min(yo, yc), x + cw / 2, Math.max(yo, yc) + 1, p);
            if (i == n - 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                p.setColor(Color.WHITE);
                c.drawRect(x - cw / 2 - 2, Math.min(yo, yc) - 2, x + cw / 2 + 2, Math.max(yo, yc) + 2, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        if (showEMA9) ema(c, 9, Color.rgb(255, 193, 7), L, R, T, B, lo, hi, step);
        if (showEMA21) ema(c, 21, Color.rgb(3, 169, 244), L, R, T, B, lo, hi, step);
        if (showEMA50) ema(c, 50, Color.rgb(171, 71, 188), L, R, T, B, lo, hi, step);

        if (showSR) {
            double res = hi - pad * .25, sup = lo + pad * .25;
            ln(c, L, py(res, lo, hi, T, B), R, py(res, lo, hi, T, B), Color.rgb(255, 152, 0), 1);
            ln(c, L, py(sup, lo, hi, T, B), R, py(sup, lo, hi, T, B), Color.rgb(0, 188, 212), 1);
            text(c, "RES", L + 4, py(res, lo, hi, T, B) - 4, 9, Color.rgb(255, 152, 0));
            text(c, "SUP", L + 4, py(sup, lo, hi, T, B) - 4, 9, Color.rgb(0, 188, 212));
        }

        if (showFibo) {
            for (double q : new double[]{.236, .382, .5, .618, .786}) {
                float y = py(hi - (hi - lo) * q, lo, hi, T, B);
                ln(c, L, y, R, y, Color.rgb(70, 75, 85), 1);
                text(c, String.format(Locale.US, "%.0f%%", q * 100), R - 30, y - 2, 8, Color.GRAY);
            }
        }

        double mid = (bid + ask) / 2;
        if (showBidAsk) {
            if (bid > 0) {
                float y = py(bid, lo, hi, T, B);
                ln(c, L, y, R, y, Color.rgb(0, 255, 145), 3);
                boldPriceBox(c, "BID  " + f(bid), y, Color.rgb(0, 205, 125), Color.BLACK);
            }
            if (ask > 0) {
                float y = py(ask, lo, hi, T, B);
                ln(c, L, y, R, y, Color.rgb(255, 75, 95), 3);
                boldPriceBox(c, "ASK  " + f(ask), y, Color.rgb(230, 45, 65), Color.WHITE);
            }
            if (mid > 0) {
                float y = py(mid, lo, hi, T, B);
                ln(c, L, y, R, y, Color.WHITE, 2);
            }
        }

        if (showTradeLevels && signalActive) {
            double[] vals = {signalEntry, signalSl, signalTp1, signalTp2};
            String[] names = {"ENTRY", "SL", "TP1", "TP2"};
            int[] cols = {
                Color.rgb(255, 193, 7), Color.rgb(244, 67, 54),
                Color.rgb(76, 175, 80), Color.rgb(0, 200, 83)
            };
            for (int i = 0; i < 4; i++) {
                float y = py(vals[i], lo, hi, T, B);
                if (y >= T && y <= B) {
                    ln(c, L, y, R, y, cols[i], 1);
                    tag(c, names[i] + " " + f(vals[i]), y, cols[i], R + 3);
                }
            }
        }

        Candle z = cs.get(n - 1);
        float mx = L + (n - 1) * step + ox + step / 2;
        boolean buy = z.c >= z.o;
        if (showSignals) {
            text(c, buy ? "▲ BUY" : "▼ SELL",
                Math.max(L, mx - 20),
                buy ? py(z.l, lo, hi, T, B) + 18 : py(z.h, lo, hi, T, B) - 12,
                11,
                buy ? Color.rgb(0, 255, 145) : Color.rgb(255, 90, 105)
            );
        }

        double unit = niceStep(hi - lo);
        double q0 = Math.ceil(lo / unit) * unit;
        for (double q = q0; q <= hi + unit * 0.25; q += unit) {
            float y = py(q, lo, hi, T, B);
            if (y >= T - 2 && y <= B + 2) {
                ln(c, L, y, R, y, Color.rgb(35, 45, 56), 1);
                text(c, f(q), R + 7, y + 4, 11, Color.WHITE);
            }
        }

        for (int i = 0; i < 7; i++) {
            int idx = Math.min(n - 1, i * (n - 1) / 6);
            float x = L + idx * step + ox + step / 2;
            if (x >= L && x <= R) {
                text(c, time.format(new Date(cs.get(idx).t)), x - 16, B + 18, 10, Color.LTGRAY);
            }
        }

        if (showRSI) text(c, "RSI 14  " + f(rsi14()), L, B + 34, 11, Color.LTGRAY);
        if (showMACD) text(c, "MACD  " + f(macd12_26()), L + 115, B + 34, 11, Color.LTGRAY);
        if (showATR) text(c, "ATR 14  " + f(atr14()), L + 230, B + 34, 11, Color.LTGRAY);
        ln(c, L, B + 47, R, B + 47, Color.rgb(32, 38, 46), 1);
        if (showWickBos) text(c, "Wick • Sweep • BOS", L, B + 62, 9, Color.LTGRAY);
        if (showPosition) text(c, "POSITION LAYER", R - 98, B + 62, 9, Color.LTGRAY);

        if (cx > 0) {
            ln(c, cx, T, cx, B, Color.GRAY, 1);
            ln(c, L, cy, R, cy, Color.GRAY, 1);
        }
    }


    double rsi14() {
        int n = Math.min(14, cs.size() - 1);
        if (n <= 0) return 50;
        double gain = 0, loss = 0;
        for (int i = cs.size() - n; i < cs.size(); i++) {
            double d = cs.get(i).c - cs.get(i - 1).c;
            if (d > 0) gain += d; else loss -= d;
        }
        if (loss < 1e-9) return 100;
        double rs = (gain / n) / (loss / n);
        return 100 - (100 / (1 + rs));
    }

    double emaValue(int n) {
        if (cs.isEmpty()) return 0;
        double k = 2.0 / (n + 1.0), e = cs.get(0).c;
        for (int i = 1; i < cs.size(); i++) e = cs.get(i).c * k + e * (1 - k);
        return e;
    }

    double macd12_26() { return emaValue(12) - emaValue(26); }

    double atr14() {
        if (cs.size() < 2) return 0;
        int n = Math.min(14, cs.size() - 1);
        double sum = 0;
        for (int i = cs.size() - n; i < cs.size(); i++) {
            Candle z = cs.get(i), prev = cs.get(i - 1);
            double tr = Math.max(z.h - z.l, Math.max(Math.abs(z.h - prev.c), Math.abs(z.l - prev.c)));
            sum += tr;
        }
        return sum / n;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lx = e.getX(); ly = e.getY(); cx = lx; cy = ly; return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() > 1) ld = dist(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() > 1) {
                    float d = dist(e);
                    if (ld > 0) cw = Math.max(7, Math.min(40, cw + (d - ld) * .06f));
                    ld = d;
                    invalidate();
                    return true;
                }
                float dx = e.getX() - lx, dy = e.getY() - ly;
                ox += dx; oy += dy;
                oy = Math.max(-120, Math.min(120, oy));
                if (Math.abs(dx) > 1) follow = false;
                lx = e.getX(); ly = e.getY(); cx = lx; cy = ly;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                invalidate(); return true;
        }
        return true;
    }

    float dist(MotionEvent e) {
        float x = e.getX(0) - e.getX(1);
        float y = e.getY(0) - e.getY(1);
        return (float)Math.sqrt(x * x + y * y);
    }
}
