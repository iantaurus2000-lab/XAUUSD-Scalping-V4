package com.xauusd.mobileengine;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    LinearLayout root; TextView price, status, signal, details; ChartView chart;
    Handler h = new Handler(Looper.getMainLooper());
    ArrayList<Candle> m1 = new ArrayList<>(), m5 = new ArrayList<>();
    final String BASE="https://biquote.io/api/XAUUSD";
    Runnable poll = new Runnable(){ public void run(){ load(); h.postDelayed(this,10000); }};

    @Override public void onCreate(Bundle b){ super.onCreate(b); buildUi(); load(); h.postDelayed(poll,10000); }
    @Override protected void onDestroy(){ h.removeCallbacksAndMessages(null); super.onDestroy(); }

    TextView tv(String s,int sp){ TextView t=new TextView(this); t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(sp); t.setPadding(14,8,14,8); return t; }
    GradientDrawable bg(String c,float r){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(c)); g.setCornerRadius(r); return g; }

    void buildUi(){
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12,12,12,8); root.setBackgroundColor(Color.rgb(8,11,16));
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=tv("XAUUSD  •  MOBILE ENGINE",18); title.setTypeface(null,1);
        price=tv("--.--",25); price.setGravity(Gravity.RIGHT); head.addView(title,new LinearLayout.LayoutParams(0,60,1)); head.addView(price,new LinearLayout.LayoutParams(0,60,1)); root.addView(head);
        status=tv("● CONNECTING",12); status.setTextColor(Color.LTGRAY); root.addView(status);
        chart=new ChartView(); root.addView(chart,new LinearLayout.LayoutParams(-1,0,1));
        signal=tv("WAITING FOR SETUP",20); signal.setGravity(Gravity.CENTER); signal.setTypeface(null,1); signal.setBackground(bg("#151B23",20)); root.addView(signal,new LinearLayout.LayoutParams(-1,64));
        details=tv("M5 Bias: --   |   Wick: --   |   Sweep: --   |   BOS: --",12); details.setGravity(Gravity.CENTER); root.addView(details);
        TextView note=tv("V5.2-A  •  Signal only / NO automatic orders",11); note.setGravity(Gravity.CENTER); note.setTextColor(Color.GRAY); root.addView(note);
        setContentView(root);
    }

    void load(){
        new Thread(()->{
            try{
                String tick=get(BASE);
                String j1=get(BASE+"/ohlc?interval=1m&limit=80");
                String j5=get(BASE+"/ohlc?interval=5m&limit=50");
                JSONObject t=new JSONObject(tick);
                ArrayList<Candle> a=parse(j1), b=parse(j5);
                runOnUiThread(()->{
                    m1=a; m5=b; price.setText(String.format(Locale.US,"%.2f",t.optDouble("mid",0)));
                    boolean stale=t.optBoolean("stale",false);
                    status.setText("● "+(stale?"STALE / MARKET CLOSED":"LIVE")+"   Spread: "+String.format(Locale.US,"%.2f",t.optDouble("spread",0)));
                    analyze(); chart.invalidate();
                });
            }catch(Exception e){ runOnUiThread(()->status.setText("● CONNECTION ERROR — retrying")); }
        }).start();
    }

    String get(String u)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(7000); c.setReadTimeout(7000);
        c.setRequestMethod("GET"); c.setRequestProperty("Accept","application/json");
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream())); StringBuilder s=new StringBuilder(); String x;
        while((x=r.readLine())!=null)s.append(x); r.close(); c.disconnect(); return s.toString();
    }

    ArrayList<Candle> parse(String s)throws Exception{
        JSONObject env=new JSONObject(s); JSONArray ar=env.getJSONArray("bars"); ArrayList<Candle> out=new ArrayList<>();
        for(int i=ar.length()-1;i>=0;i--){ JSONObject o=ar.getJSONObject(i); out.add(new Candle(o.optLong("openTime",0),o.getDouble("open"),o.getDouble("high"),o.getDouble("low"),o.getDouble("close"))); }
        return out;
    }

    void analyze(){
        if(m1.size()<8 || m5.size()<8)return;
        int n=m1.size(); Candle c=m1.get(n-2), p=m1.get(n-3);
        double body=Math.abs(c.close-c.open), upper=c.high-Math.max(c.open,c.close), lower=Math.min(c.open,c.close)-c.low;
        boolean buyW=lower>Math.max(body*1.5,0.05) && c.close>c.open;
        boolean sellW=upper>Math.max(body*1.5,0.05) && c.close<c.open;
        boolean buySweep=c.low<p.low && c.close>p.low, sellSweep=c.high>p.high && c.close<p.high;
        boolean buyBos=c.close>p.high, sellBos=c.close<p.low;
        Candle m=m5.get(m5.size()-2); Candle mp=m5.get(m5.size()-3);
        boolean biasBuy=m.close>mp.high, biasSell=m.close<mp.low;
        int buy=(buyW?1:0)+(buySweep?1:0)+(buyBos?1:0)+(biasBuy?1:0);
        int sell=(sellW?1:0)+(sellSweep?1:0)+(sellBos?1:0)+(biasSell?1:0);
        if(buy>=3 && biasBuy){ signal.setText("🟢 BUY  •  CONFIDENCE "+buy+"/4"); signal.setTextColor(Color.rgb(0,230,118)); }
        else if(sell>=3 && biasSell){ signal.setText("🔴 SELL  •  CONFIDENCE "+sell+"/4"); signal.setTextColor(Color.rgb(255,82,82)); }
        else { signal.setText("WAITING FOR SETUP"); signal.setTextColor(Color.WHITE); }
        details.setText("M5 Bias: "+(biasBuy?"BUY":biasSell?"SELL":"NEUTRAL")+"   |   Wick: "+(buyW||sellW?"YES":"--")+"   |   Sweep: "+(buySweep||sellSweep?"YES":"--")+"   |   BOS: "+(buyBos||sellBos?"YES":"--"));
    }

    class Candle { long t; double open,high,low,close; Candle(long t,double o,double h,double l,double c){this.t=t;open=o;high=h;low=l;close=c;} }

    class ChartView extends View {
        Paint p=new Paint(3);
        ChartView(){super(MainActivity.this); p.setStrokeWidth(2); setBackgroundColor(Color.rgb(11,15,21));}
        protected void onDraw(Canvas c){
            super.onDraw(c); if(m1.size()<2)return; int left=12, right=getWidth()-12, top=18, bottom=getHeight()-18;
            int start=Math.max(0,m1.size()-55), count=m1.size()-start; double hi=-1e9,lo=1e9;
            for(int i=start;i<m1.size();i++){hi=Math.max(hi,m1.get(i).high);lo=Math.min(lo,m1.get(i).low);}
            double pad=(hi-lo)*0.08; hi+=pad;lo-=pad; float w=(right-left)/(float)count;
            p.setTextSize(22);p.setColor(Color.GRAY);c.drawText("M1",left,top+20,p);
            for(int i=start;i<m1.size();i++){ Candle x=m1.get(i); float cx=left+(i-start+.5f)*w;
                float yH=bottom-(float)((x.high-lo)/(hi-lo))*(bottom-top), yL=bottom-(float)((x.low-lo)/(hi-lo))*(bottom-top);
                float yO=bottom-(float)((x.open-lo)/(hi-lo))*(bottom-top), yC=bottom-(float)((x.close-lo)/(hi-lo))*(bottom-top);
                p.setColor(x.close>=x.open?Color.rgb(0,200,83):Color.rgb(255,70,70)); p.setStrokeWidth(2); c.drawLine(cx,yH,cx,yL,p);
                float bw=Math.max(3,w*.62f); c.drawRect(cx-bw/2,Math.min(yO,yC),cx+bw/2,Math.max(yO,yC)+1,p);
            }
        }
    }
}
