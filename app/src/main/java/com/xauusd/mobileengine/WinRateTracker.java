package com.xauusd.mobileengine;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Lightweight local journal for READY signals and user/AUTO queued entries. */
public final class WinRateTracker {
    private static final String PREF="rayyan4_winrate";
    private static final String KEY="records";
    private WinRateTracker(){}

    public static synchronized boolean record(Context c,String cohort,String market,String side,
                                               double entry,double sl,double tp,long startCandle){
        if(c==null||entry<=0||sl<=0||tp<=0||(!"BUY".equals(side)&&!"SELL".equals(side)))return false;
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONArray a=read(p.getString(KEY,"[]"));
        String id=cohort+"|"+market+"|"+side+"|"+startCandle;
        for(int i=0;i<a.length();i++){
            JSONObject old=a.optJSONObject(i);
            if(old!=null&&id.equals(old.optString("id")))return false;
        }
        try{
            JSONObject o=new JSONObject();
            o.put("id",id);o.put("cohort",cohort);o.put("market",market);o.put("side",side);
            o.put("entry",entry);o.put("sl",sl);o.put("tp",tp);o.put("start",startCandle);
            o.put("status","OPEN");o.put("result","");o.put("resultTime",0L);
            a.put(o);
            while(a.length()>500)a.remove(0);
            p.edit().putString(KEY,a.toString()).apply();
            AppLog.add(c,"WINRATE_OPEN",cohort+" "+market+" "+side+" Entry="+fmt(entry)+" SL="+fmt(sl)+" TP1="+fmt(tp));
            return true;
        }catch(Exception e){AppLog.add(c,"WINRATE_ERROR","record "+e.getMessage());return false;}
    }

    public static synchronized void evaluate(Context c,String market,Candle[] bars){
        if(c==null||bars==null||bars.length==0)return;
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONArray a=read(p.getString(KEY,"[]"));boolean changed=false;
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o==null||!"OPEN".equals(o.optString("status"))||!market.equals(o.optString("market")))continue;
            long start=o.optLong("start",0L);
            double sl=o.optDouble("sl",0),tp=o.optDouble("tp",0);
            String side=o.optString("side");
            for(Candle b:bars){
                if(b==null||b.t<=start)continue;
                boolean hitSl="BUY".equals(side)?b.l<=sl:b.h>=sl;
                boolean hitTp="BUY".equals(side)?b.h>=tp:b.l<=tp;
                if(hitSl||hitTp){
                    String result=hitSl&&hitTp?"AMBIGUOUS":hitTp?"WIN":"LOSS";
                    try{o.put("status",result);o.put("result",result);o.put("resultTime",b.t);}catch(Exception ignored){}
                    AppLog.add(c,"WINRATE_RESULT",o.optString("cohort")+" "+side+" "+result+
                        " Entry="+fmt(o.optDouble("entry",0))+" SL="+fmt(sl)+" TP1="+fmt(tp));
                    changed=true;break;
                }
            }
        }
        if(changed)p.edit().putString(KEY,a.toString()).apply();
    }

    public static synchronized Stats stats(Context c,String cohort){
        JSONArray a=read(c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]"));
        Stats s=new Stats();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null||!cohort.equals(o.optString("cohort")))continue;
            s.total++;
            String state=o.optString("status");
            if("WIN".equals(state))s.win++;
            else if("LOSS".equals(state))s.loss++;
            else if("AMBIGUOUS".equals(state))s.ambiguous++;
            else s.open++;
        }
        return s;
    }

    public static synchronized String details(Context c,String cohort){
        JSONArray a=read(c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]"));
        StringBuilder b=new StringBuilder();
        for(int i=a.length()-1;i>=0;i--){
            JSONObject o=a.optJSONObject(i);if(o==null||!cohort.equals(o.optString("cohort")))continue;
            if(b.length()>0)b.append("\\n");
            b.append(o.optString("status")).append(" • ").append(o.optString("side"))
             .append(" • ").append(fmt(o.optDouble("entry",0)))
             .append(" • SL ").append(fmt(o.optDouble("sl",0)))
             .append(" • TP1 ").append(fmt(o.optDouble("tp",0)))
             .append(" • ").append(o.optString("market"));
            if(b.length()>8500){b.append("\\n… riwayat dipotong");break;}
        }
        return b.length()==0?"Belum ada catatan untuk kategori ini.":b.toString();
    }

    private static JSONArray read(String raw){try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}}
    private static String fmt(double n){return String.format(Locale.US,"%.2f",n);}
    public static final class Stats{
        public int total,win,loss,open,ambiguous;
        public double winRate(){return win+loss==0?0:100.0*win/(win+loss);}
    }
}
