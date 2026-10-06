package com.xauusd.tradinggateway;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        TextView title = new TextView(this);
        title.setText("XAUUSD Trading Gateway\nV1 Foundation");
        title.setTextSize(22);
        root.addView(title);
        status = new TextView(this);
        status.setText("\nSTATUS: STOPPED\nLocal API: 127.0.0.1:8787");
        status.setTextSize(17);
        root.addView(status);
        Button start = new Button(this);
        start.setText("START GATEWAY");
        start.setOnClickListener(v -> {
            startForegroundService(new Intent(this, GatewayService.class));
            status.setText("\nSTATUS: START REQUESTED\nLocal API: 127.0.0.1:8787");
        });
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("STOP GATEWAY");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, GatewayService.class));
            status.setText("\nSTATUS: STOPPED\nLocal API: 127.0.0.1:8787");
        });
        root.addView(stop);
        setContentView(root);
    }
}
