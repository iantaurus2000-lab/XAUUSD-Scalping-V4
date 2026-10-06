package com.xauusd.tradinggateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class GatewayService extends Service {
    private static final String CHANNEL_ID = "gateway_status";
    private static final int PORT = 8787;
    private volatile boolean running;
    private ServerSocket serverSocket;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Gateway Status", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1001, notification());
        startServer();
        return START_STICKY;
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("XAUUSD Trading Gateway")
                .setContentText("Gateway ACTIVE • local API :8787")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true).build();
    }

    private synchronized void startServer() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    handle(socket);
                }
            } catch (Exception ignored) {
            } finally {
                running = false;
                closeServer();
            }
        }, "gateway-local-api").start();
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            String request = in.readLine();
            if (request == null) return;
            while (true) { String line = in.readLine(); if (line == null || line.isEmpty()) break; }
            boolean health = request.startsWith("GET /health");
            String body = health ? "{\"ok\":true,\"service\":\"xauusd-trading-gateway\",\"stage\":\"foundation\"}" : "{\"ok\":false,\"error\":\"not_found\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + (health ? "200 OK" : "404 Not Found") + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        closeServer();
        super.onDestroy();
    }

    private void closeServer() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
