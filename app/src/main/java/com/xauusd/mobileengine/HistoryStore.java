package com.xauusd.mobileengine;
import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.*;
public final class HistoryStore {
 private static final String P="rayyan4_history",K="items";
 public static synchronized void add(Context c,String type,String side,double entry,double sl,double tp1,double tp2,int confidence){
  String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date())+" | "+type+" | "+side+
   " | Entry="+f(entry)+" | SL="+f(sl)+" | TP1="+f(tp1)+" | TP2="+f(tp2)+" | C="+confidence+"/5";
  android.content.SharedPreferences p=c.getSharedPreferences(P,Context.MODE_PRIVATE); String old=p.getString(K,"");
  String[] a=old.isEmpty()?new String[0]:old.split("\n",-1); StringBuilder b=new StringBuilder(line);
  for(int i=0;i<a.length&&i<299;i++) b.append("\n").append(a[i]); p.edit().putString(K,b.toString()).apply();
 }
 public static String all(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE).getString(K,"");}
 public static void clear(Context c){c.getSharedPreferences(P,Context.MODE_PRIVATE).edit().remove(K).apply();}
 static String f(double v){return String.format(Locale.US,"%.2f",v);}
}