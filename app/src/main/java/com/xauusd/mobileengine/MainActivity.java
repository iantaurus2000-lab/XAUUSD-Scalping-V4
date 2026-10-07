package com.xauusd.mobileengine;
import android.app.*;import android.os.*;import android.graphics.Color;import android.view.*;import android.widget.*;import java.util.concurrent.*;import java.util.*;
public class MainActivity extends Activity{
 CandleChartView chart;TextView price,status,signal;ScheduledExecutorService ex;
 int bg=Color.rgb(8,12,16),panel=Color.rgb(17,22,29),line=Color.rgb(35,42,50);
 TextView tv(String s,float z){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(z);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(10,0,10,0);return v;}
 Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextSize(11);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setMinHeight(40);b.setMinWidth(0);b.setPadding(4,0,4,0);return b;}
 @Override public void onCreate(Bundle b){super.onCreate(b);buildUI();}
 void buildUI(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
  // compact top bar: menu always visible at right
  LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(8,2,6,2);head.setBackgroundColor(panel);
  LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);TextView t=tv("XAUUSD",17);t.setTypeface(null,1);names.addView(t,new LinearLayout.LayoutParams(-1,25));names.addView(tv("MOBILE TRADING ENGINE",9),new LinearLayout.LayoutParams(-1,20));head.addView(names,new LinearLayout.LayoutParams(0,50,1));
  price=tv("BID --  ASK --",11);head.addView(price,new LinearLayout.LayoutParams(150,50));
  Button menu=btn("☰  MENU");menu.setOnClickListener(v->showMenu(menu));head.addView(menu,new LinearLayout.LayoutParams(82,50));root.addView(head);
  // timeframe row, always visible
  LinearLayout tf=new LinearLayout(this);tf.setPadding(4,0,4,0);tf.setBackgroundColor(Color.rgb(13,18,24));
  for(String s:new String[]{"M1","M5","M15","H1"}){Button q=btn(s);tf.addView(q,new LinearLayout.LayoutParams(0,38,1));}
  Button live=btn("● LIVE");live.setTextColor(Color.rgb(0,220,140));tf.addView(live,new LinearLayout.LayoutParams(0,38,1));live.setOnClickListener(v->{if(chart!=null)chart.goLive();});
  root.addView(tf);
  // chart gets maximum space; no controls are placed over it
  chart=new CandleChartView(this);root.addView(chart,new LinearLayout.LayoutParams(-1,0,1));
  // compact action row
  LinearLayout actions=new LinearLayout(this);actions.setPadding(4,0,4,0);actions.setBackgroundColor(panel);
  Button follow=btn("FOLLOW");follow.setOnClickListener(v->chart.goLive());actions.addView(follow,new LinearLayout.LayoutParams(0,40,1));
  Button cross=btn("CROSSHAIR");cross.setOnClickListener(v->Toast.makeText(this,"Crosshair: sentuh dan geser chart",Toast.LENGTH_SHORT).show());actions.addView(cross,new LinearLayout.LayoutParams(0,40,1));
  Button signalBtn=btn("SIGNAL");signalBtn.setOnClickListener(v->showSignal());actions.addView(signalBtn,new LinearLayout.LayoutParams(0,40,1));root.addView(actions);
  LinearLayout info=new LinearLayout(this);info.setBackgroundColor(Color.rgb(11,16,21));status=tv("● CONNECTING  •  BIQUOTE",10);signal=tv("EMA 9/21/50 • RSI • MACD • ATR",10);info.addView(status,new LinearLayout.LayoutParams(0,34,1));info.addView(signal,new LinearLayout.LayoutParams(0,34,1));root.addView(info);
  // bottom navigation is horizontally scrollable so all menu items remain accessible
  HorizontalScrollView navScroll=new HorizontalScrollView(this);navScroll.setHorizontalScrollBarEnabled(false);LinearLayout nav=new LinearLayout(this);nav.setPadding(2,0,2,0);nav.setBackgroundColor(Color.rgb(15,20,26));
  for(String s:new String[]{"MARKET","CHART","SIGNAL","ORDERS","SETTINGS"}){Button q=btn(s);q.setMinWidth(90);nav.addView(q,new LinearLayout.LayoutParams(90,48));}navScroll.addView(nav);root.addView(navScroll,new LinearLayout.LayoutParams(-1,48));
  setContentView(root);load();
 }
 void showMenu(View anchor){
  final String[] items={"Market","Chart","Signal","Orders","Strategy","Risk Management","Telegram","MT5 / EA","Settings"};
  PopupWindow pop=new PopupWindow(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(6,6,6,6);box.setBackgroundColor(Color.rgb(25,31,39));
  for(String s:items){Button b=btn(s);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setMinWidth(210);box.addView(b,new LinearLayout.LayoutParams(210,42));b.setOnClickListener(v->{pop.dismiss();Toast.makeText(this,s,Toast.LENGTH_SHORT).show();});}
  pop.setContentView(box);pop.setWidth(220);pop.setHeight(WindowManager.LayoutParams.WRAP_CONTENT);pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));pop.setOutsideTouchable(true);pop.setFocusable(true);pop.setElevation(16);pop.showAsDropDown(anchor,-138,0);
 }
 void showSignal(){new AlertDialog.Builder(this).setTitle("Signal Engine").setMessage("Wick Rejection / Jarum\nLiquidity Sweep\nBOS\nM5 Bias → M1 Entry\nEMA 9/21/50 • RSI • MACD • ATR\n\nConfidence: menunggu data").setPositiveButton("OK",null).show();}
 void load(){new Thread(()->{try{Candle[] c=BiquoteClient.candles();runOnUiThread(()->{chart.setData(c);status.setText("● LIVE  •  BIQUOTE  •  M1");});}catch(Exception e){runOnUiThread(()->status.setText("● OFFLINE  •  RETRYING"));}}).start();
  ex=Executors.newSingleThreadScheduledExecutor();ex.scheduleAtFixedRate(()->{try{double[] t=BiquoteClient.tick();runOnUiThread(()->{chart.setTick(t[0],t[1],"LIVE");price.setText(String.format(Locale.US,"BID %.2f  ASK %.2f",t[0],t[1]));signal.setText("SCANNER READY • EMA9/21/50 • RSI • MACD • ATR");});}catch(Exception e){runOnUiThread(()->status.setText("● OFFLINE • retrying"));}},1,1,TimeUnit.SECONDS);
 }
 @Override protected void onDestroy(){if(ex!=null)ex.shutdownNow();super.onDestroy();}
}