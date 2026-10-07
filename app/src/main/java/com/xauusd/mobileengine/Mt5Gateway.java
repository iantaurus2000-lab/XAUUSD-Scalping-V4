package com.xauusd.mobileengine;
import android.content.Context;
import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets;
public final class Mt5Gateway {
 public interface Callback { void done(boolean ok,String response); }
 public static void send(Context c,String host,int port,String command,Callback cb){
  new Thread(()->{
   boolean ok=false; String r;
   try{
    URL u=new URL("http://"+host+":"+port+"/command");
    HttpURLConnection x=(HttpURLConnection)u.openConnection(); x.setRequestMethod("POST");
    x.setConnectTimeout(5000); x.setReadTimeout(8000); x.setDoOutput(true);
    x.setRequestProperty("Content-Type","text/plain; charset=utf-8");
    try(OutputStream o=x.getOutputStream()){o.write(command.getBytes(StandardCharsets.UTF_8));}
    int code=x.getResponseCode(); InputStream in=code>=200&&code<300?x.getInputStream():x.getErrorStream();
    r=read(in); ok=code>=200&&code<300;
   }catch(Exception e){r=e.getMessage()==null?"Gateway error":e.getMessage();}
   String z=r; boolean q=ok; if(cb!=null)cb.done(q,z);
  }).start();
 }
 static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] z=new byte[2048];int n;while((n=in.read(z))>0)b.write(z,0,n);return b.toString("UTF-8");}
}