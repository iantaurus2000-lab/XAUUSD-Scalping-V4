package com.xauusd.mobileengine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class EngineRestartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences("xauusd_secure", Context.MODE_PRIVATE)
                .getBoolean("auto", false)) return;
        try {
            Intent service = new Intent(context, TradingService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {}
    }
}
