package com.xauusd.mobileengine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && context.getSharedPreferences("xauusd_secure", Context.MODE_PRIVATE)
                    .getBoolean("auto", false)) {
            Intent service = new Intent(context, TradingService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        }
    }
}
