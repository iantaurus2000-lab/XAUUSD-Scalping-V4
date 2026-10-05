package com.xauusd.mobileengine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FxOpenProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "FXOPEN_PROBE";

    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                String id = intent.getStringExtra("fx_id");
                String key = intent.getStringExtra("fx_key");
                String secret = intent.getStringExtra("fx_secret");
                String host = intent.getStringExtra("fx_host");

                if (id == null || key == null || secret == null || id.trim().isEmpty()
                        || key.trim().isEmpty() || secret.trim().isEmpty()) {
                    Log.e(TAG, "PROBE_RESULT CONFIG_MISSING");
                    return;
                }

                SecurityStore store = new SecurityStore(app);
                FxOpenTickTraderClient client = new FxOpenTickTraderClient(store);
                client.save(id, key, secret, host);

                Log.i(TAG, "PROBE_HOST " + client.host());
                Log.i(TAG, "PROBE_BEGIN GET /api/v2/account");

                FxOpenTickTraderClient.Response r = client.accountInfo();
                Log.i(TAG, "PROBE_HTTP " + r.code);

                if (r.ok()) {
                    String body = r.body == null ? "" : r.body.replaceAll("\s+", " ").trim();
                    if (body.length() > 2500) body = body.substring(0, 2500);
                    Log.i(TAG, "PROBE_RESULT AUTHORIZED");
                    Log.i(TAG, "PROBE_ACCOUNT " + body);
                } else {
                    String body = r.body == null ? "" : r.body.replaceAll("\s+", " ").trim();
                    if (body.length() > 2500) body = body.substring(0, 2500);
                    Log.e(TAG, "PROBE_RESULT AUTH_FAILED_HTTP_" + r.code);
                    Log.e(TAG, "PROBE_BODY " + body);
                }
            } catch (Throwable e) {
                Log.e(TAG, "PROBE_RESULT CLIENT_ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                pending.finish();
                pool.shutdown();
            }
        });
    }
}
