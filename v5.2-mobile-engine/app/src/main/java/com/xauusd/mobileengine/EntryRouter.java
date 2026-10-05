package com.xauusd.mobileengine;

import org.json.JSONObject;

/**
 * Entry router:
 * BIQUOTE -> Signal Engine -> FXOpen entry/auth layer -> Exness Public Trader API -> MT5.
 *
 * FXOpen is NOT used as a second broker order destination here; its Web API
 * connection is validated as the entry layer, then the same entry request is
 * forwarded by this app to the configured Exness account. This prevents duplicate
 * live/demo orders at two brokers.
 */
public final class EntryRouter {
    public static final class Result {
        public final boolean ok;
        public final ExnessClient.Response exness;
        public final String fxOpenStatus;
        public final String entryJson;
        Result(boolean ok, ExnessClient.Response exness, String fxOpenStatus, String entryJson) {
            this.ok=ok; this.exness=exness; this.fxOpenStatus=fxOpenStatus; this.entryJson=entryJson;
        }
    }

    private final FxOpenTickTraderClient fxOpen;
    private final ExnessClient exness;

    public EntryRouter(FxOpenTickTraderClient fxOpen, ExnessClient exness) {
        this.fxOpen=fxOpen;
        this.exness=exness;
    }

    public boolean fxOpenConfigured() {
        return fxOpen != null && fxOpen.configured();
    }

    public Result placeLimit(String symbol, String side, String volume, String price,
                             String sl, String tp, String comment) throws Exception {
        String fxStatus="FXOPEN ENTRY NOT CONNECTED";
        if (fxOpenConfigured()) {
            FxOpenTickTraderClient.Response fr=fxOpen.accountInfo();
            if (!fr.ok()) throw new IllegalStateException(
                    "FXOPEN ENTRY ERROR "+fr.code+" • "+trim(fr.body));
            fxStatus="FXOPEN ENTRY READY";
        }

        JSONObject entry=new JSONObject();
        entry.put("source","BIQUOTE");
        entry.put("entry_layer","FXOPEN_WEB_API");
        entry.put("destination","EXNESS_MT5");
        entry.put("instrument",symbol);
        entry.put("side",side);
        entry.put("type","LIMIT");
        entry.put("volume",volume);
        entry.put("price",price);
        entry.put("stop_loss_price",sl);
        entry.put("take_profit_price",tp);
        entry.put("comment",comment==null?"XAUUSD-V5.2":comment);

        ExnessClient.Response er=exness.placeLimit(symbol,side,volume,price,sl,tp,
                comment==null?"XAUUSD-V5.2":comment);
        return new Result(er.ok(),er,fxStatus,entry.toString());
    }

    public String label() {
        if (fxOpenConfigured()) return "FXOPEN ENTRY → EXNESS MT5";
        return "EXNESS MT5";
    }

    private static String trim(String s) {
        return s==null?"":s.length()>300?s.substring(0,300)+"…":s;
    }
}
