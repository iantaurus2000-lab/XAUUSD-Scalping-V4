package com.xauusd.mobileengine;
import android.content.Context;import android.graphics.*;import android.view.*;import java.text.SimpleDateFormat;import java.util.*;
public class CandleChartView extends View{
 Paint p=new Paint(1);ArrayList<Candle> cs=new ArrayList<>();double bid,ask;float cw=16,ox=0,oy=0,lx,ly,ld,cx,cy;boolean follow=true;
 SimpleDateFormat time=new SimpleDateFormat("HH:mm",Locale.US);
 public CandleChartView(Context c){super(c);setBackgroundColor(Color.rgb(9,12,16));}
 public void setData(Candle[] a){cs.clear();if(a!=null)Collections.addAll(cs,a);invalidate();}
 public void setTick(double b,double a,String s){bid=b;ask=a;if(!cs.isEmpty()){Candle z=cs.get(cs.size()-1);double q=(b+a)/2;z.c=q;z.h=Math.max(z.h,q);z.l=Math.min(z.l,q);}invalidate();}
 public void goLive(){follow=true;ox=0;oy=0;invalidate();} public boolean isFollow(){return follow;}
 void text(Canvas c,String s,float x,float y,float z,int col){p.setStyle(Paint.Style.FILL);p.setTextSize(z);p.setColor(col);c.drawText(s,x,y,p);}
 void ln(Canvas c,float a,float b,float d,float e,int col,float sw){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setColor(col);c.drawLine(a,b,d,e,p);p.setStyle(Paint.Style.FILL);}
 String f(double v){return String.format(Locale.US,"%.2f",v);}
 float py(double q,double lo,double hi,float t,float b){return (float)(t+(hi-q)/(hi-lo)*(b-t))+oy;}
 void tag(Canvas c,String s,float y,int col,float x){p.setColor(Color.argb(225,18,22,28));c.drawRect(x,y-13,Math.min(getWidth()-2,x+88),y+4,p);text(c,s,x+3,y,col==0?10:10,col);}
 void ema(Canvas c,int n,int col,float L,float R,float T,float B,double lo,double hi,float step){
   if(cs.size()<n)return;double k=2.0/(n+1),e=cs.get(0).c;float px=0,py=0;boolean first=true;
   for(int i=0;i<cs.size();i++){e=cs.get(i).c*k+e*(1-k);float x=L+i*step+ox+step/2,y=py(e,lo,hi,T,B);if(x<L||x>R){px=x;py=y;continue;}if(!first)ln(c,px,py,x,y,col,2);px=x;py=y;first=false;}
 }
 @Override protected void onDraw(Canvas c){
  float L=54,R=getWidth()-122,T=40,B=getHeight()-92;c.drawColor(Color.rgb(9,12,16));
  // header
  
  for(int i=0;i<=6;i++){float y=T+i*(B-T)/6;ln(c,L,y,R,y,Color.rgb(32,38,46),1);}
  for(int i=0;i<=8;i++){float x=L+i*(R-L)/8;ln(c,x,T,x,B,Color.rgb(24,29,36),1);}
  if(cs.isEmpty()){text(c,"Menunggu candle XAUUSD...",L+15,(T+B)/2,18,Color.LTGRAY);return;}
  double lo=1e99,hi=-1e99;for(Candle z:cs){lo=Math.min(lo,z.l);hi=Math.max(hi,z.h);}double pad=Math.max((hi-lo)*.10,.40);lo-=pad;hi+=pad;
  float step=cw+5;int n=cs.size();int st=Math.max(0,(int)Math.floor((-ox)/step)-2),en=Math.min(n,(int)Math.ceil((R-L-ox)/step)+3);
  for(int i=st;i<en;i++){Candle z=cs.get(i);float x=L+i*step+ox+step/2,yh=py(z.h,lo,hi,T,B),yl=py(z.l,lo,hi,T,B),yo=py(z.o,lo,hi,T,B),yc=py(z.c,lo,hi,T,B);int col=z.c>=z.o?Color.rgb(0,215,145):Color.rgb(245,75,88);ln(c,x,yh,x,yl,col,2);p.setColor(col);c.drawRect(x-cw/2,Math.min(yo,yc),x+cw/2,Math.max(yo,yc)+1,p);}
  ema(c,9,Color.rgb(255,193,7),L,R,T,B,lo,hi,step);ema(c,21,Color.rgb(3,169,244),L,R,T,B,lo,hi,step);ema(c,50,Color.rgb(171,71,188),L,R,T,B,lo,hi,step);
  // S/R + Fibonacci
  double res=hi-pad*.25,sup=lo+pad*.25;ln(c,L,py(res,lo,hi,T,B),R,py(res,lo,hi,T,B),Color.rgb(255,152,0),1);ln(c,L,py(sup,lo,hi,T,B),R,py(sup,lo,hi,T,B),Color.rgb(0,188,212),1);
  text(c,"RES",L+4,py(res,lo,hi,T,B)-4,9,Color.rgb(255,152,0));text(c,"SUP",L+4,py(sup,lo,hi,T,B)-4,9,Color.rgb(0,188,212));
  for(double q:new double[]{.236,.382,.5,.618,.786}){float y=py(hi-(hi-lo)*q,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(70,75,85),1);text(c,String.format(Locale.US,"%.0f%%",q*100),R-34,y-2,9,Color.GRAY);}
  if(bid>0){float y=py(bid,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(0,230,118),2);tag(c,"BID "+f(bid),y,1,R+3);}
  if(ask>0){float y=py(ask,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(255,82,82),2);tag(c,"ASK "+f(ask),y,1,R+3);}
  double mid=(bid+ask)/2;if(mid>0){double[] vals={mid,mid-2,mid+2,mid+4};String[] names={"ENTRY","SL","TP1","TP2"};int[] cols={Color.rgb(255,193,7),Color.rgb(244,67,54),Color.rgb(76,175,80),Color.rgb(0,200,83)};for(int i=0;i<4;i++){float y=py(vals[i],lo,hi,T,B);if(y>=T&&y<=B){ln(c,L,y,R,y,cols[i],1);tag(c,names[i]+" "+f(vals[i]),y,1,R+3);}}}
  Candle z=cs.get(n-1);float mx=L+(n-1)*step+ox+step/2;boolean buy=z.c>=z.o;text(c,buy?"▲ BUY":"▼ SELL",Math.max(L,mx-20),buy?py(z.l,lo,hi,T,B)+18:py(z.h,lo,hi,T,B)-12,11,buy?Color.rgb(0,220,130):Color.rgb(255,80,90));
  // price axis and time axis
  for(int i=0;i<=6;i++){double q=hi-i*(hi-lo)/6;float y=py(q,lo,hi,T,B);text(c,f(q),R+5,y+4,10,Color.LTGRAY);}
  for(int i=0;i<7;i++){int idx=Math.min(n-1,i*(n-1)/6);float x=L+idx*step+ox+step/2;if(x>=L&&x<=R)text(c,time.format(new Date(cs.get(idx).t)),x-14,B+18,9,Color.LTGRAY);}
  // lower indicator area
  text(c,"RSI 14",L,B+38,10,Color.LTGRAY);text(c,"MACD",L+115,B+38,10,Color.LTGRAY);text(c,"ATR",L+230,B+38,10,Color.LTGRAY);ln(c,L,B+50,R,B+50,Color.rgb(32,38,46),1);
  text(c,"Wick • Sweep • BOS",L,B+55,9,Color.LTGRAY);
  if(cx>0){ln(c,cx,T,cx,B,Color.GRAY,1);ln(c,L,cy,R,cy,Color.GRAY,1);}
 }
 @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){
 case MotionEvent.ACTION_DOWN:lx=e.getX();ly=e.getY();cx=lx;cy=ly;return true;
 case MotionEvent.ACTION_POINTER_DOWN:if(e.getPointerCount()>1)ld=dist(e);return true;
 case MotionEvent.ACTION_MOVE:
  if(e.getPointerCount()>1){float d=dist(e);if(ld>0)cw=Math.max(7,Math.min(40,cw+(d-ld)*.06f));ld=d;invalidate();return true;}
  float dx=e.getX()-lx,dy=e.getY()-ly;ox+=dx;oy+=dy;if(Math.abs(dx)>1)follow=false;lx=e.getX();ly=e.getY();cx=lx;cy=ly;invalidate();return true;
 case MotionEvent.ACTION_UP:invalidate();return true;}return true;}
 float dist(MotionEvent e){float x=e.getX(0)-e.getX(1),y=e.getY(0)-e.getY(1);return (float)Math.sqrt(x*x+y*y);}
}