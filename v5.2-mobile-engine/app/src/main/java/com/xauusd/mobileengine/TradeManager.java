package com.xauusd.mobileengine;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class TradeManager {
    private final SecurityStore store;
    private final ExnessClient exness;

    public TradeManager(SecurityStore store, ExnessClient exness) {
        this.store = store;
        this.exness = exness;
    }

    public void manage(String snapshotBody, double mid) {
        if (!store.rawPrefs().getBoolean("manager_enabled", false)) return;
        if (snapshotBody == null || snapshotBody.isEmpty() || mid <= 0) return;

        try {
            JSONObject root = new JSONObject(snapshotBody);
            JSONArray positions = root.optJSONArray("open_positions");
            if (positions == null) positions = root.optJSONArray("positions");
            if (positions == null) return;

            double tp1Pct = clamp(parse(store.rawPrefs().getString("tp1_percent", "50")), 10, 90);
            double tp1R = clamp(parse(store.rawPrefs().getString("tp1_r", "1.0")), 0.5, 3.0);
            boolean be = store.rawPrefs().getBoolean("be_on_tp1", true);

            for (int i = 0; i < positions.length(); i++) {
                JSONObject p = positions.optJSONObject(i);
                if (p == null) continue;
                String instrument = first(p, "instrument", "symbol");
                String configuredSymbol = store.get("symbol","XAUUSD").trim();
                if (configuredSymbol.isEmpty()) configuredSymbol = "XAUUSD";
                if (!configuredSymbol.equalsIgnoreCase(instrument)) continue;

                String id = first(p, "position_id", "id");
                String side = first(p, "side", "direction");
                double volume = num(p, "volume", "current_volume");
                double entry = num(p, "open_price", "entry_price", "price");
                double sl = num(p, "stop_loss_price", "sl");
                if (id.isEmpty() || volume <= 0 || entry <= 0 || sl <= 0) continue;

                double risk = Math.abs(entry - sl);
                if (risk <= 0) continue;
                boolean hit = ("buy".equalsIgnoreCase(side) && mid >= entry + risk * tp1R)
                        || ("sell".equalsIgnoreCase(side) && mid <= entry - risk * tp1R);
                if (!hit) continue;

                String key = "tp1_done_" + id;
                if (!store.rawPrefs().getBoolean(key, false)) {
                    double closeVol = normalizeVolume(volume * (tp1Pct / 100.0));
                    if (closeVol > 0 && closeVol < volume) {
                        ExnessClient.Response close = exness.closePosition(id, formatVolume(closeVol));
                        String op = exness.operationId(close);
                        if (close.ok()) {
                            store.rawPrefs().edit().putBoolean(key, true).apply();
                            if (be) {
                                moveRelatedSltpToBreakeven(root, id, entry);
                            }
                        }
                    } else if (be) {
                        store.rawPrefs().edit().putBoolean(key, true).apply();
                        moveRelatedSltpToBreakeven(root, id, entry);
                    }
                }
            }
        } catch (Exception ignored) {
            // The manager must fail closed: no parse means no trade mutation.
        }
    }

    private void moveRelatedSltpToBreakeven(JSONObject root, String positionId, double entry) {
        try {
            JSONArray orders = root.optJSONArray("open_orders");
            if (orders == null) orders = root.optJSONArray("orders");
            if (orders == null) return;

            for (int i = 0; i < orders.length(); i++) {
                JSONObject o = orders.optJSONObject(i);
                if (o == null) continue;
                String type = first(o, "type", "order_type");
                String pid = first(o, "position_id");
                String oid = first(o, "order_id", "id");
                if (!oid.isEmpty() && pid.equals(positionId) && type.toLowerCase(Locale.US).contains("sltp")) {
                    exness.modifyOrder(oid, null, fmt(entry), null);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private double normalizeVolume(double value) {
        try {
            ExnessClient.Credentials c = exness.loadCredentials();
            String configuredSymbol = store.get("symbol","XAUUSD").trim();
            if (configuredSymbol.isEmpty()) configuredSymbol = "XAUUSD";
            ExnessClient.Response r = exness.instrumentConditions(configuredSymbol);
            if (r.ok()) {
                JSONObject j = new JSONObject(r.body);
                double min = num(j, "volume_min");
                double max = num(j, "volume_max");
                double step = num(j, "volume_step");
                if (step > 0) {
                    double v = Math.floor(value / step + 1e-9) * step;
                    if (v < min) return 0;
                    return Math.min(v, max);
                }
            }
        } catch (Exception ignored) {}
        return value >= 0.01 ? Math.floor(value * 100.0) / 100.0 : 0;
    }

    private static String first(JSONObject j, String... keys) {
        for (String k : keys) {
            String v = j.optString(k, "");
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
        }
        return "";
    }

    private static double num(JSONObject j, String... keys) {
        for (String k : keys) {
            Object v = j.opt(k);
            if (v instanceof Number) return ((Number)v).doubleValue();
            if (v != null) try { return Double.parseDouble(v.toString()); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    private static double parse(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
    private static String fmt(double v) { return String.format(Locale.US, "%.2f", v); }
    private static String formatVolume(double v) { return String.format(Locale.US, "%.4f", v).replaceAll("0+$","").replaceAll("\\.$",""); }
}
