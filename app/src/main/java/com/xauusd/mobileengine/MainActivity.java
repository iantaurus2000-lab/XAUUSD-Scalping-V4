package com.xauusd.mobileengine;
import android.app.*;import android.os.*;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;import java.util.concurrent.*;import java.util.*;
public class MainActivity extends Activity{
 CandleChartView chart;TextView price,status,signal;ScheduledExecutorService ex;
 int green=Color.rgb(0,210,140),bg=Color.rgb(9,12,16),panel=Color.rgb(18,23,30);
 TextView tv(String s,float z){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(z);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(12,4,12,4);return v;}
 Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextSize(11);b.setTextColor(Color.WHITE);b.setAllCaps(false);return b;}
 @Override public void onCreate(Bundle b){super.onCreate(b);buildUI();}
 void buildUI(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
  LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(10,4,10,4);head.setBackgroundColor(Color.rgb(14,19,25));
  TextView title=tv("XAUUSD  •  MOBILE TRADING ENGINE",16);title.setTypeface(null,1);head.addView(title,new LinearLayout.LayoutParams(0,52,1));
  price=tv("BID --  |  ASK --",13);head.addView(price,new LinearLayout.LayoutParams(-2,52));root.addView(head);
  LinearLayout tf=new LinearLayout(this);tf.setPadding(6,2,6,2);tf.setBackgroundColor(panel);
  for(String s:new String[]{"M1","M5","M15","H1"}){Button q=btn(s);tf.addView(q,new LinearLayout.LayoutParams(0,40,1));}Button live=btn("● LIVE");tf.addView(live,new LinearLayout.LayoutParams(0,40,1));root.addView(tf);
  LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setBackgroundColor(bg);
  LinearLayout chartBox=new LinearLayout(this);chartBox.setOrientation(LinearLayout.VERTICAL);chartBox.setBackgroundColor(bg);
  chart=new CandleChartView(this);chartBox.addView(chart,new LinearLayout.LayoutParams(-1,0,1));body.addView(chartBox,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout actions=new LinearLayout(this);actions.setPadding(6,2,6,2);actions.setBackgroundColor(panel);
  Button auto=btn("AUTO FOLLOW");auto.setOnClickListener(v->{chart.goLive();auto.setText("FOLLOW ON");});actions.addView(auto,new LinearLayout.LayoutParams(0,42,1));
  Button cross=btn("CROSSHAIR");cross.setOnClickListener(v->Toast.makeText(this,"Crosshair aktif saat menyentuh chart",Toast.LENGTH_SHORT).show());actions.addView(cross,new LinearLayout.LayoutParams(0,42,1));
  Button sig=btn("SIGNAL");sig.setOnClickListener(v->Toast.makeText(this,"Signal Engine • Wick / Sweep / BOS",Toast.LENGTH_SHORT).show());actions.addView(sig,new LinearLayout.LayoutParams(0,42,1));body.addView(actions);
  LinearLayout info=new LinearLayout(this);info.setPadding(8,3,8,3);info.setBackgroundColor(Color.rgb(13,17,22));
  status=tv("● CONNECTING  •  BIQUOTE",11);info.addView(status,new LinearLayout.LayoutParams(0,38,1));
  signal=tv("WAITING  |  EMA 9/21/50  •  RSI  •  MACD  •  ATR",10);info.addView(signal,new LinearLayout.LayoutParams(0,38,1));body.addView(info);
  root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.rgb(16,21,27));String[] items={"MARKET","CHART","SIGNAL","ORDERS","SETTINGS"};for(String s:items){Button q=btn(s);nav.addView(q,new LinearLayout.LayoutParams(0,50,1));}root.addView(nav);
  live.setOnClickListener(v->chart.goLive());
  setContentView(root);load();
 }
 void load(){new Thread(()->{try{Candle[] c=BiquoteClient.candles();runOnUiThread(()->{chart.setData(c);status.setText("● LIVE  •  BIQUOTE  •  M1");});}catch(Exception e){runOnUiThread(()->status.setText("● OFFLINE  •  RETRYING"));}}).start();
  ex=Executors.newSingleThreadScheduledExecutor();ex.scheduleAtFixedRate(()->{try{double[] t=BiquoteClient.tick();runOnUiThread(()->{chart.setTick(t[0],t[1],"LIVE");price.setText(String.format(Locale.US,"BID %.2f   ASK %.2f",t[0],t[1]));signal.setText("SCANNER READY  |  EMA 9/21/50  •  RSI  •  MACD  •  ATR");});}catch(Exception e){runOnUiThread(()->status.setText("● OFFLINE  •  retrying..."));}},1,1,TimeUnit.SECONDS);
 }
 @Override protected void onDestroy(){if(ex!=null)ex.shutdownNow();super.onDestroy();}
}