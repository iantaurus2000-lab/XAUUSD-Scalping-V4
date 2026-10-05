package com.xauusd.mobileengine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Single market gateway.
 *
 * Rules:
 * 1) Never blank the UI because an external provider is unavailable.
 * 2) Prefer the selected broker feed when it succeeds.
 * 3) Fall back to Biquote for public price/OHLC.
 * 4) Fall back again to a deterministic local feed so the chart/strategy remain testable.
 *
 * The local feed is only a resilience/test fallback; it is not presented as broker data.
 */
public final class Market {
    private static final String BIQUOTE = "https://biquote.io";
    private static final double DEFAULT_PRICE = 4140.00;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static final ArrayList<StrategyEngine.Candle> m1Cache = new ArrayList<>();
    private static final ArrayList<StrategyEngine.Candle> m5Cache = new ArrayList<>();
    private static final ArrayList<StrategyEngine.Candle> m15Cache = new ArrayList<>();

    private static long m1At, m5At, m15At, lastSyntheticBuild;
    private static Tick lastTick;
    private static String source = "SIM FALLBACK";
    private static String error = "";

    public static final class Tick {
        public final double mid, bid, ask, spread, dayDiff;
        Tick(double mid, double bid, double ask, double spread, double dayDiff) {
            this.mid = mid;
            this.bid = bid;
            this.ask = ask;
            this.spread = spread;
            this.dayDiff = dayDiff;
        }
    }

    public static final class Snapshot {
        public final double mid, bid, ask, spread;
        public final ArrayList<StrategyEngine.Candle> m1, m5, m15;
        Snapshot(double mid, double bid, double ask, double spread,
                 ArrayList<StrategyEngine.Candle> m1,
                 ArrayList<StrategyEngine.Candle> m5,
                 ArrayList<StrategyEngine.Candle> m15) {
            this.mid = mid;
            this.bid = bid;
            this.ask = ask;
            this.spread = spread;
            this.m1 = m1;
            this.m5 = m5;
            this.m15 = m15;
        }
    }

    private Market() {}

    public static synchronized String sourceStatus() {
        return source + (error.isEmpty() ? "" : " • " + error);
    }

    public static synchronized Tick tick() {
        try {
            lastTick = readBiquoteTick();
            source = "BIQUOTE REAL";
            error = "";
            return lastTick;
        } catch (Exception ex) {
            error = compactError(ex);
            lastTick = syntheticTick(lastTick == null ? DEFAULT_PRICE : lastTick.mid);
            source = "SIM FALLBACK";
            return lastTick;
        }
    }

    public static synchronized Snapshot snapshot(double lastPrice) {
        return snapshot(lastPrice, null);
    }

    public static synchronized Snapshot snapshot(double lastPrice, FxOpenTickTraderClient fx) {
        final long now = System.currentTimeMillis();

        // Broker market: only use the connector handed to us by the caller.
        if (fx != null && fx.configured()) {
            try {
                Snapshot s = fxSnapshot(fx, now);
                source = "FXOPEN REAL";
                error = "";
                return s;
            } catch (Exception ex) {
                error = "FXOpen " + compactError(ex);
            }
        }

        // Public Biquote feed.
        Tick t = tick();
        boolean ohlcOk = true;

        try {
            if (m1Cache.size() < 60 || now - m1At >= 5000) {
                List<StrategyEngine.Candle> x = fetchOhlc("1m", 180);
                if (x.size() >= 30) {
                    replace(m1Cache, x);
                    m1At = now;
                } else {
                    ohlcOk = false;
                }
            }
        } catch (Exception ex) {
            ohlcOk = false;
            error = compactError(ex);
        }

        try {
            if (m5Cache.size() < 30 || now - m5At >= 15000) {
                List<StrategyEngine.Candle> x = fetchOhlc("5m", 150);
                if (x.size() >= 20) {
                    replace(m5Cache, x);
                    m5At = now;
                } else {
                    ohlcOk = false;
                }
            }
        } catch (Exception ex) {
            ohlcOk = false;
        }

        try {
            if (m15Cache.size() < 30 || now - m15At >= 30000) {
                List<StrategyEngine.Candle> x = fetchOhlc("15m", 100);
                if (x.size() >= 20) {
                    replace(m15Cache, x);
                    m15At = now;
                } else {
                    ohlcOk = false;
                }
            }
        } catch (Exception ex) {
            ohlcOk = false;
        }

        // Never leave the chart empty. Build a stable local history when needed.
        if (m1Cache.size() < 60 || m5Cache.size() < 30 || m15Cache.size() < 30
                || now - lastSyntheticBuild > 120000) {
            buildSyntheticHistory(Math.max(t.mid, 1.0), now);
        }

        if ("BIQUOTE REAL".equals(source)) {
            if (!ohlcOk) source = "BIQUOTE TICK + SIM OHLC";
            error = "";
        } else if (!source.startsWith("FXOPEN")) {
            source = "SIM FALLBACK";
        }

        return new Snapshot(
                t.mid,
                t.bid,
                t.ask,
                t.spread,
                liveCopy(m1Cache, t.mid),
                liveCopy(m5Cache, t.mid),
                liveCopy(m15Cache, t.mid)
        );
    }

    private static Snapshot fxSnapshot(FxOpenTickTraderClient fx, long now) throws Exception {
        FxOpenTickTraderClient.Response tr = fx.publicTickV2("XAUUSD");
        if (!tr.ok()) throw new IOException("tick HTTP " + tr.code);

        JSONObject root = new JSONObject(tr.body);
        double bid = readNestedPrice(root, "BestBid");
        double ask = readNestedPrice(root, "BestAsk");

        JSONObject result = root.optJSONObject("Result");
        if (Double.isNaN(bid) && result != null) bid = readNestedPrice(result, "BestBid");
        if (Double.isNaN(ask) && result != null) ask = readNestedPrice(result, "BestAsk");
        if (Double.isNaN(bid) || Double.isNaN(ask)) throw new IOException("Bid/Ask missing");

        ArrayList<StrategyEngine.Candle> a1 = fxBarsRest(fx, "M1", 180, now);
        ArrayList<StrategyEngine.Candle> a5 = fxBarsRest(fx, "M5", 150, now);
        ArrayList<StrategyEngine.Candle> a15 = fxBarsRest(fx, "M15", 100, now);

        if (a1.size() < 20) throw new IOException("M1 history empty");
        if (a5.size() < 20) a5 = a1;
        if (a15.size() < 20) a15 = a1;

        double mid = (bid + ask) / 2.0;
        return new Snapshot(mid, bid, ask, Math.max(0, ask - bid),
                liveCopy(a1, mid), liveCopy(a5, mid), liveCopy(a15, mid));
    }

    private static ArrayList<StrategyEngine.Candle> fxBarsRest(
            FxOpenTickTraderClient fx, String tf, int count, long now) throws Exception {
        FxOpenTickTraderClient.Response r =
                fx.publicQuoteHistory("XAUUSD", tf, "Bid", now, -Math.abs(count));
        if (!r.ok()) throw new IOException(tf + " HTTP " + r.code);

        JSONObject root = new JSONObject(r.body);
        JSONArray ar = root.optJSONArray("Bars");
        if (ar == null) ar = root.optJSONArray("bars");
        if (ar == null && root.opt("Result") instanceof JSONArray) {
            ar = root.optJSONArray("Result");
        }
        if (ar == null) throw new IOException(tf + " Bars missing");

        ArrayList<StrategyEngine.Candle> out = new ArrayList<>();
        for (int i = 0; i < ar.length(); i++) {
            JSONObject b = ar.optJSONObject(i);
            if (b == null) continue;
            long t = (long) num(b, "Timestamp", "timestamp", "Time", "time", "OpenTime", "openTime");
            if (t > 0 && t < 100000000000L) t *= 1000L;

            double o = num(b, "Open", "open");
            double h = num(b, "High", "high");
            double l = num(b, "Low", "low");
            double c = num(b, "Close", "close");
            if (t > 0 && valid(o, h, l, c)) out.add(new StrategyEngine.Candle(t, o, h, l, c));
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.t));
        return out;
    }

    private static List<StrategyEngine.Candle> fetchOhlc(String interval, int limit) throws Exception {
        String body = get(BIQUOTE + "/api/XAUUSD/ohlc?interval=" + interval + "&limit=" + limit);
        String z = body.trim();
        JSONArray ar = null;

        if (z.startsWith("[")) {
            ar = new JSONArray(z);
        } else {
            JSONObject root = new JSONObject(z);
            ar = root.optJSONArray("bars");
            if (ar == null) ar = root.optJSONArray("data");
            if (ar == null) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) ar = result.optJSONArray("bars");
            }
        }

        if (ar == null) throw new IOException("OHLC missing");

        ArrayList<StrategyEngine.Candle> out = new ArrayList<>(ar.length());
        for (int i = 0; i < ar.length(); i++) {
            JSONObject b = ar.optJSONObject(i);
            if (b == null) continue;

            long t = b.optLong("openTime", b.optLong("time", b.optLong("timestamp", 0)));
            if (t == 0) {
                String iso = b.optString("openTime", b.optString("timestamp", ""));
                if (!iso.isEmpty()) {
                    try { t = java.time.Instant.parse(iso).toEpochMilli(); } catch (Exception ignored) {}
                }
            }
            if (t > 0 && t < 100000000000L) t *= 1000L;

            double o = b.optDouble("open", Double.NaN);
            double h = b.optDouble("high", Double.NaN);
            double l = b.optDouble("low", Double.NaN);
            double c = b.optDouble("close", Double.NaN);
            if (t > 0 && valid(o, h, l, c)) out.add(new StrategyEngine.Candle(t, o, h, l, c));
        }

        Collections.sort(out, Comparator.comparingLong(a -> a.t));
        return out;
    }

    private static Tick readBiquoteTick() throws Exception {
        String body = get(BIQUOTE + "/api/XAUUSD");
        JSONObject o = new JSONObject(body);

        double mid = o.optDouble("mid", o.optDouble("price", Double.NaN));
        if (Double.isNaN(mid) || mid <= 0) throw new IOException("mid missing");

        double bid = o.optDouble("bid", mid);
        double ask = o.optDouble("ask", mid);
        double spread = ask > 0 && bid > 0 ? Math.max(0, ask - bid) : 0.30;
        double day = o.optDouble("dayDiff", o.optDouble("dayDiffPercent", 0.0));
        return new Tick(mid, bid, ask, spread, day);
    }

    private static Tick syntheticTick(double base) {
        long sec = System.currentTimeMillis() / 1000L;
        double wave = Math.sin(sec / 9.0) * 0.65 + Math.sin(sec / 37.0) * 1.35;
        double mid = Math.max(1.0, base + wave);
        double spread = 0.18;
        return new Tick(mid, mid - spread / 2.0, mid + spread / 2.0, spread, 0.0);
    }

    private static void buildSyntheticHistory(double base, long now) {
        // Rebuild only when caches are empty/stale so the chart is visually stable.
        if (now - lastSyntheticBuild < 15000
                && m1Cache.size() >= 60 && m5Cache.size() >= 30 && m15Cache.size() >= 30) {
            return;
        }

        buildOne(m1Cache, base, 180, 60_000L);
        buildOne(m5Cache, base, 150, 300_000L);
        buildOne(m15Cache, base, 100, 900_000L);
        lastSyntheticBuild = now;
    }

    private static void buildOne(ArrayList<StrategyEngine.Candle> out,
                                 double base, int count, long step) {
        out.clear();
        long now = System.currentTimeMillis();
        long aligned = now - (now % step);
        double prev = base;

        for (int i = count - 1; i >= 0; i--) {
            long t = aligned - i * step;
            double drift = Math.sin(i * 0.13) * (step / 60000.0) * 0.20;
            double momentum = Math.sin(i * 0.047) * (step / 60000.0) * 0.08;
            double open = prev;
            double close = Math.max(1.0, base + drift + momentum);
            double wick = 0.10 + Math.abs(Math.sin(i * 0.31)) * 0.16;
            double high = Math.max(open, close) + wick;
            double low = Math.min(open, close) - wick * 0.9;
            out.add(new StrategyEngine.Candle(t, open, high, low, close));
            prev = close;
        }
    }

    private static ArrayList<StrategyEngine.Candle> liveCopy(List<StrategyEngine.Candle> src, double mid) {
        ArrayList<StrategyEngine.Candle> out=new ArrayList<>();
        for(StrategyEngine.Candle c:src)
            out.add(new StrategyEngine.Candle(c.t,c.open,c.high,c.low,c.close));
        if(!out.isEmpty()&&mid>0){
            long step=60_000L;
            if(out.size()>=2){
                long d=out.get(out.size()-1).t-out.get(out.size()-2).t;
                if(d>=1_000L&&d<=3_600_000L)step=d;
            }
            long now=System.currentTimeMillis();
            long bucket=now-(now%step);
            StrategyEngine.Candle last=out.get(out.size()-1);
            long lastMs=last.t<100000000000L?last.t*1000L:last.t;
            if(lastMs<bucket-step/2){
                double open=last.close;
                out.add(new StrategyEngine.Candle(bucket,open,Math.max(open,mid),Math.min(open,mid),mid));
            }else{
                last.high=Math.max(last.high,mid);
                last.low=Math.min(last.low,mid);
                last.close=mid;
                last.t=bucket;
            }
        }
        return out;
    }

    private static void replace(ArrayList<StrategyEngine.Candle> target, List<StrategyEngine.Candle> source) {
        target.clear();
        target.addAll(source);
    }

    private static String compactError(Exception ex) {
        String s = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static String get(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "XAUUSD-Mobile-Trading-Engine/5.2-CLEAN")
                .header("Accept", "application/json")
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (response.body() == null) throw new IOException("empty response");
            return response.body().string();
        }
    }
}
