package com.xauusd.mobileengine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class TelegramClient {
    public interface Callback { void done(boolean ok, String message); }

    public static void send(String token, String chatId, String message, Callback cb) {
        new Thread(() -> {
            boolean ok = false; String out;
            try {
                if (token == null || token.trim().isEmpty() || chatId == null || chatId.trim().isEmpty())
                    throw new IllegalArgumentException("Bot Token / Chat ID belum diisi");
                URL u = new URL("https://api.telegram.org/bot" + token.trim() + "/sendMessage");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setDoOutput(true); c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                String body = "chat_id=" + enc(chatId) + "&text=" + enc(message);
                try (OutputStream os=c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                int code=c.getResponseCode();
                InputStream is=code>=200&&code<300?c.getInputStream():c.getErrorStream();
                out=read(is);
                ok=code>=200&&code<300&&out.contains(""ok":true");
                if (!ok) throw new IOException("HTTP "+code);
            } catch(Exception e) { out=e.getMessage()==null?"Telegram error":e.getMessage(); }
            String result=out;
            if (cb != null) cb.done(ok,result);
        }).start();
    }
    private static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }
    private static String read(InputStream in) throws IOException {
        if(in==null)return "";
        ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] x=new byte[2048]; int n;
        while((n=in.read(x))>0)b.write(x,0,n);
        return b.toString("UTF-8");
    }
}