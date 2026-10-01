package com.xauusd.mobileengine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class TelegramClient {
    private final SecurityStore store;
    public TelegramClient(SecurityStore store){this.store=store;}

    public boolean enabled(){
        return !store.get("tg_token","").trim().isEmpty() && !store.get("tg_chat","").trim().isEmpty();
    }

    public void send(String text) throws Exception{
        String token=store.get("tg_token","").trim();
        String chat=store.get("tg_chat","").trim();
        if(token.isEmpty()||chat.isEmpty()) return;

        String endpoint="https://api.telegram.org/bot"+token+"/sendMessage";
        String form="chat_id="+URLEncoder.encode(chat,"UTF-8")+
                "&text="+URLEncoder.encode(text,"UTF-8")+
                "&disable_web_page_preview=true";
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        c.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
        int code=c.getResponseCode();
        InputStream is=code>=400?c.getErrorStream():c.getInputStream();
        if(is!=null){BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));while(r.readLine()!=null){}r.close();}
        c.disconnect();
        if(code<200||code>=300)throw new IOException("Telegram HTTP "+code);
    }
}
