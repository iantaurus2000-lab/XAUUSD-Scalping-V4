package com.xauusd.mobileengine;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.*;

public final class AppLog {
    private static final String PREF="rayyan4_log";
    private static final String KEY="items";
    public static synchronized void add(Context c,String type,String msg){
        android.content.SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String old=p.getString(KEY,"");
        String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date())+" | "+type+" | "+msg.replace("\n"," ");
        String[] a=old.isEmpty()?new String[0]:old.split("\n",-1);
        StringBuilder b=new StringBuilder(line);
        for(int i=0;i<a.length&&i<199;i++)b.append("\n").append(a[i]);
        p.edit().putString(KEY,b.toString()).apply();
    }
    public static String all(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"");}
    public static void clear(Context c){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(KEY).apply();}
}