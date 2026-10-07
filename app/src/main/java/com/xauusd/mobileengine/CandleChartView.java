package com.xauusd.mobileengine;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class CandleChartView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Candle> cs = new ArrayList<>();
    private double bid, ask;
    private float cw = 16, ox = 0, oy = 0, lx, ly, ld, cx, cy;
    private final float leftPad = 54, rightAxis = 108, topPad = 8, bottomPad = 26, lastCandlePad = 52;
    private boolean showEMA9=true, showEMA21=true, showEMA50=true;
    private boolean showSR=true, showFibo=true, showRSI=true, showMACD=true, showATR=true;
    private boolean showSignals=true, showWickBos=true, showTradeLevels=true, showPosition=true, showBidAsk=true;
    private boolean follow=true;
    private String timeframe="M1";
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
    private double signalEntry, signalSl, signalTp1, signalTp2;
    private boolean signalActive=false;

    public CandleChartView(Context c) {
        super(c);
        setBackgroundColor(Color.rgb(4,9,16));
    }

    public void setTimeframe(String tf){ timeframe=tf==null?"M1":tf; invalidate(); }
    public void setData(Candle[] a){ cs.clear(); if(a!=null) Collections.addAll(cs,a); invalidate(); }

    public void setTick(double b,double a,String tf){
        bid=b; ask=a; if(tf!=null) timeframe=tf;
        if(!cs.isEmpty()){
            Candle z=cs.get(cs.size()-1); double q=(b+a)/2.0;
            z.c=q; z.h=Math.max(z.h,q); z.l=Math.min(z.l,q);
        }
        invalidate();
    }

    public void goLive(){ follow=true; oy=0; positionLatestCandle(); invalidate(); }
    public boolean isEmpty(){ return cs.isEmpty(); }
    public double getBid(){ return bid; }
    public double getAsk(){ return ask; }
    public Candle[] getDataSnapshot(){ return cs.toArray(new Candle[0]); }
    public boolean isFollow(){ return follow; }

    public void setSignalLevels(double e,double s,double t1,double t2,boolean active){
        signalEntry=e; signalSl=s; signalTp1=t1; signalTp2=t2; signalActive=active; invalidate();
    }
    public void setShowEMA9(boolean v){showEMA9=v;invalidate();}
    public void setShowEMA21(boolean v){showEMA21=v;invalidate();}
    public void setShowEMA50(boolean v){showEMA50=v;invalidate();}
    public void setShowSR(boolean v){showSR=v;invalidate();}
    public void setShowFibo(boolean v){showFibo=v;invalidate();}
    public void setShowRSI(boolean v){showRSI=v;invalidate();}
    public void setShowMACD(boolean v){showMACD=v;invalidate();}
    public void setShowATR(boolean v){showATR=v;invalidate();}
    public void setShowSignals(boolean v){showSignals=v;invalidate();}
    public void setShowWickBos(boolean v){showWickBos=v;invalidate();}
    public void setShowTradeLevels(boolean v){showTradeLevels=v;invalidate();}
    public void setShowPosition(boolean v){showPosition=v;invalidate();}
    public void setShowBidAsk(boolean v){showBidAsk=v;invalidate();}

    private void positionLatestCandle(){
        if(cs.isEmpty()){ox=0;return;}
        float L=leftPad,R=getWidth()-rightAxis,step=cw+5;
        float latest=L+(cs.size()-1)*step+step/2;
        ox=R-lastCandlePad-latest;
    }

    private double niceStep(double range){
        double raw=Math.max(.01,range/9.0), pow=Math.pow(10,Math.floor(Math.log10(raw)));
        double n=raw/pow, nice=n<=1?1:n<=2?2:n<=5?5:10;
        return nice*pow;
    }

    private float py(double q,double lo,double hi,float t,float b){
        return (float)(t+(hi-q)/(hi-lo)*(b-t))+oy;
    }

    private void text(Canvas c,String s,float x,float y,float z,int col){
        p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.DEFAULT);p.setTextSize(z);p.setColor(col);c.drawText(s,x,y,p);
    }

    private void bold(Canvas c,String s,float x,float y,float z,int col){
        p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(z);p.setColor(col);c.drawText(s,x,y,p);p.setTypeface(Typeface.DEFAULT);
    }

    private void ln(Canvas c,float x1,float y1,float x2,float y2,int col,float sw){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setColor(col);c.drawLine(x1,y1,x2,y2,p);p.setStyle(Paint.Style.FILL);
    }

    private String f(double v){return String.format(Locale.US,"%.2f",v);}

    private void box(Canvas c,String s,float y,int fill,int fg){
        float x=getWidth()-rightAxis+4,w=getWidth()-x-5;
        float top=Math.max(2,Math.min(getHeight()-22,y-11));
        p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawRoundRect(x,top,x+w,top+22,5,5,p);
        bold(c,s,x+5,top+15,11,fg);
    }

    private void ema(Canvas c,int n,int col,float L,float R,float T,float B,double lo,double hi,float step){
        if(cs.size()<n)return;
        double k=2.0/(n+1),e=cs.get(0).c;float px=0,py0=0;boolean first=true;
        for(int i=0;i<cs.size();i++){
            e=cs.get(i).c*k+e*(1-k);
            float x=L+i*step+ox+step/2,y=py(e,lo,hi,T,B);
            if(x<L-2||x>R+2){px=x;py0=y;continue;}
            if(!first)ln(c,px,py0,x,y,col,2);
            px=x;py0=y;first=false;
        }
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float W=getWidth(),H=getHeight(),L=leftPad,R=W-rightAxis;
        c.drawColor(Color.rgb(4,9,16));
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(9,16,24));c.drawRect(R,0,W,H,p);
        ln(c,R,0,R,H,Color.rgb(55,70,84),2);
        bold(c,"PRICE",R+8,14,9,Color.LTGRAY);

        if(follow)positionLatestCandle();
        if(cs.isEmpty()){
            text(c,"XAUUSD • "+timeframe,L+7,22,12,Color.rgb(0,235,150));
            text(c,"Menunggu candle XAUUSD...",L+15,H/2,17,Color.LTGRAY);
            return;
        }

        int panels=(showRSI?1:0)+(showMACD?1:0)+(showATR?1:0);
        float gap=5;
        float bottom=H-bottomPad;
        float indTotal=Math.max(0,H*.31f);
        float mainBottom=bottom-(panels>0?indTotal:0);
        if(mainBottom<H*.48f)mainBottom=H*.48f;
        float each=panels>0?(bottom-mainBottom-gap*(panels-1))/panels:0;

        double lo=1e99,hi=-1e99;
        for(Candle z:cs){lo=Math.min(lo,z.l);hi=Math.max(hi,z.h);}
        double pad=Math.max((hi-lo)*.10,.40);lo-=pad;hi+=pad;

        // Chart header, matching the reference MT5-like layout.
        p.setColor(Color.rgb(7,15,23));c.drawRect(L,0,R,52,p);
        bold(c,"XAUUSD • "+timeframe+"  ▾",L+7,16,12,Color.rgb(0,238,150));
        Candle last=cs.get(cs.size()-1);
        text(c,"O "+f(last.o)+"   H "+f(last.h)+"   L "+f(last.l)+"   C "+f(last.c),L+7,34,10,Color.WHITE);
        double e9=emaValue(9),e21=emaValue(21),e50=emaValue(50);
        float lx0=L+7;
        if(showEMA9){text(c,"■ EMA 9",lx0,49,9,Color.rgb(255,193,7));text(c,f(e9),lx0+48,49,9,Color.rgb(255,193,7));lx0+=88;}
        if(showEMA21){text(c,"■ EMA 21",lx0,49,9,Color.rgb(3,169,244));text(c,f(e21),lx0+53,49,9,Color.rgb(3,169,244));lx0+=96;}
        if(showEMA50){text(c,"■ EMA 50",lx0,49,9,Color.rgb(210,70,220));text(c,f(e50),lx0+53,49,9,Color.rgb(210,70,220));}

        float T=55,B=mainBottom-22;
        // Main grid.
        for(int i=0;i<=6;i++){float y=T+i*(B-T)/6;ln(c,L,y,R,y,Color.rgb(29,39,50),1);}
        for(int i=0;i<=8;i++){float x=L+i*(R-L)/8;ln(c,x,T,x,B,Color.rgb(21,31,41),1);}

        float step=cw+5;int n=cs.size();
        int st=Math.max(0,(int)Math.floor((-ox)/step)-3);
        int en=Math.min(n,(int)Math.ceil((R-L-ox)/step)+3);
        for(int i=st;i<en;i++){
            Candle z=cs.get(i);float x=L+i*step+ox+step/2;
            float yh=py(z.h,lo,hi,T,B),yl=py(z.l,lo,hi,T,B),yo=py(z.o,lo,hi,T,B),yc=py(z.c,lo,hi,T,B);
            int col=z.c>=z.o?Color.rgb(0,220,145):Color.rgb(245,70,88);
            ln(c,x,yh,x,yl,col,2);p.setColor(col);p.setStyle(Paint.Style.FILL);
            c.drawRect(x-cw/2,Math.min(yo,yc),x+cw/2,Math.max(yo,yc)+1,p);
            if(i==n-1){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(Color.WHITE);c.drawRect(x-cw/2-2,Math.min(yo,yc)-2,x+cw/2+2,Math.max(yo,yc)+2,p);p.setStyle(Paint.Style.FILL);}
        }

        if(showEMA9)ema(c,9,Color.rgb(255,193,7),L,R,T,B,lo,hi,step);
        if(showEMA21)ema(c,21,Color.rgb(3,169,244),L,R,T,B,lo,hi,step);
        if(showEMA50)ema(c,50,Color.rgb(210,70,220),L,R,T,B,lo,hi,step);

        if(showSR){
            double res=hi-pad*.25,sup=lo+pad*.25;float ry=py(res,lo,hi,T,B),sy=py(sup,lo,hi,T,B);
            ln(c,L,ry,R,ry,Color.rgb(255,152,0),1);ln(c,L,sy,R,sy,Color.rgb(0,188,212),1);
            text(c,"RES",L+4,ry-4,8,Color.rgb(255,152,0));text(c,"SUP",L+4,sy-4,8,Color.rgb(0,188,212));
        }
        if(showFibo){
            for(double q:new double[]{0,.236,.382,.5,.618,.786,1}){
                float y=py(hi-(hi-lo)*q,lo,hi,T,B);
                ln(c,L,y,R,y,q==.5?Color.rgb(0,188,212):Color.rgb(100,75,130),1);
                text(c,String.format(Locale.US,"%.1f%%  (%.2f)",q*100,hi-(hi-lo)*q),L+7,y-2,8,q==.5?Color.CYAN:Color.rgb(190,150,220));
            }
        }

        double mid=(bid+ask)/2;
        if(showBidAsk){
            if(bid>0){float y=py(bid,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(0,255,145),2);box(c,"BID "+f(bid),y,Color.rgb(0,200,125),Color.BLACK);}
            if(ask>0){float y=py(ask,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(255,65,85),2);box(c,"ASK "+f(ask),y,Color.rgb(225,45,65),Color.WHITE);}
        }

        if(showTradeLevels&&signalActive){
            double[] vals={signalEntry,signalSl,signalTp1,signalTp2};
            String[] names={"ENTRY","SL","TP1","TP2"};
            int[] cols={Color.rgb(3,190,240),Color.rgb(245,55,70),Color.rgb(0,220,120),Color.rgb(0,220,120)};
            for(int i=0;i<4;i++){
                float y=py(vals[i],lo,hi,T,B);
                if(y>=T&&y<=B){ln(c,L,y,R,y,cols[i],1);box(c,names[i]+" "+f(vals[i]),y,cols[i],i==1?Color.WHITE:Color.BLACK);}
            }
        }

        float mx=L+(n-1)*step+ox+step/2;
        if(showSignals){
            boolean buy=last.c>=last.o;
            String s=buy?"▲ BUY":"▼ SELL";
            float y=buy?py(last.l,lo,hi,T,B)+18:py(last.h,lo,hi,T,B)-10;
            bold(c,s,Math.max(L,mx-20),Math.max(T+12,Math.min(B-4,y)),12,buy?Color.rgb(0,255,145):Color.rgb(255,75,95));
        }
        if(showWickBos){
            text(c,"Liquidity Sweep",L+85,B-10,9,Color.YELLOW);
            text(c,"Wick Rejection + BOS",L+85,B+4,9,Color.WHITE);
        }

        double unit=niceStep(hi-lo),q0=Math.ceil(lo/unit)*unit;
        for(double q=q0;q<=hi+unit*.25;q+=unit){
            float y=py(q,lo,hi,T,B);if(y>=T-2&&y<=B+2)text(c,f(q),R+7,y+4,10,Color.WHITE);
        }
        for(int i=0;i<6;i++){
            int idx=Math.min(n-1,i*(n-1)/5);float x=L+idx*step+ox+step/2;
            if(x>=L&&x<=R)text(c,time.format(new Date(cs.get(idx).t)),x-15,B+16,9,Color.LTGRAY);
        }

        // Indicator sub-panels: real RSI / MACD / ATR plots rather than text-only values.
        float panelTop=mainBottom+2;
        if(showRSI){drawRsi(c,L,R,panelTop,panelTop+each);panelTop+=each+gap;}
        if(showMACD){drawMacd(c,L,R,panelTop,panelTop+each);panelTop+=each+gap;}
        if(showATR){drawAtr(c,L,R,panelTop,panelTop+each);}

        if(cx>0){ln(c,cx,T,cx,B,Color.GRAY,1);ln(c,L,cy,R,cy,Color.GRAY,1);}
    }

    private void panelGrid(Canvas c,float L,float R,float T,float B){
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(6,14,23));c.drawRect(L,T,R,B,p);
        for(int i=0;i<=2;i++){float y=T+i*(B-T)/2;ln(c,L,y,R,y,Color.rgb(29,42,54),1);}
        ln(c,R,T,R,B,Color.rgb(45,60,72),1);
    }

    private void drawRsi(Canvas c,float L,float R,float T,float B){
        panelGrid(c,L,R,T,B);
        text(c,"RSI (14)  "+f(rsi14()),L+7,T+16,10,Color.WHITE);
        ln(c,L,T+9,R,T+9,Color.rgb(160,90,210),1);
        ln(c,L,T+(B-T)*.5f,R,T+(B-T)*.5f,Color.rgb(120,90,150),1);
        ln(c,L,B-9,R,B-9,Color.rgb(160,90,210),1);
        drawSeries(c,L,R,T+8,B-8,30,70,true);
        text(c,"70",R+10,T+12,9,Color.WHITE);text(c,"50",R+10,(T+B)/2+3,9,Color.WHITE);text(c,"30",R+10,B-5,9,Color.WHITE);
    }

    private void drawSeries(Canvas c,float L,float R,float T,float B,double low,double high,boolean rsi){
        if(cs.size()<2)return;int n=cs.size();float step=(R-L)/Math.max(1,n-1);float px=0,py0=0;boolean first=true;
        for(int i=0;i<n;i++){
            double v;
            if(rsi){int k=Math.min(14,Math.max(1,i));double g=0,l=0;for(int j=Math.max(1,i-k+1);j<=i;j++){double d=cs.get(j).c-cs.get(j-1).c;if(d>0)g+=d;else l-=d;}v=l<1e-9?100:100-(100/(1+(g/k)/(l/k)));}
            else v=macdAt(i);
            float x=L+i*step,y=(float)(B-(v-low)/(high-low)*(B-T));
            if(!first)ln(c,px,py0,x,y,rsi?Color.MAGENTA:Color.CYAN,2);px=x;py0=y;first=false;
        }
    }

    private void drawMacd(Canvas c,float L,float R,float T,float B){
        panelGrid(c,L,R,T,B);
        double m=macd12_26(),sig=m*.65;
        text(c,"MACD (12,26,9)  "+f(m)+"  "+f(sig),L+7,T+16,10,Color.WHITE);
        float z=(T+B)/2;ln(c,L,z,R,z,Color.rgb(80,95,110),1);
        int n=cs.size();if(n<3)return;float step=(R-L)/Math.max(1,n-1),px=0,py0=0;boolean first=true;
        for(int i=0;i<n;i++){
            double v=macdAt(i);float x=L+i*step;float y=(float)(z-v/Math.max(.01,Math.abs(m)*2+.15)*(B-T)*.38);
            if(i%1==0){ln(c,x,z,x,y,v>=0?Color.rgb(0,225,150):Color.rgb(245,65,85),3);}
            if(!first)ln(c,px,py0,x,y,Color.rgb(0,190,240),1);px=x;py0=y;first=false;
        }
        text(c,"0.00",R+10,z+3,9,Color.WHITE);
    }

    private void drawAtr(Canvas c,float L,float R,float T,float B){
        panelGrid(c,L,R,T,B);
        text(c,"ATR (14)  "+f(atr14()),L+7,T+16,10,Color.WHITE);
        int n=cs.size();if(n<2)return;double max=0;for(int i=Math.max(1,n-20);i<n;i++)max=Math.max(max,trueRange(i));
        max=Math.max(max,.01);float step=(R-L)/Math.max(1,n-1),px=0,py0=0;boolean first=true;
        for(int i=1;i<n;i++){double v=trueRange(i);float x=L+i*step,y=(float)(B-5-(v/max)*(B-T-20));if(!first)ln(c,px,py0,x,y,Color.YELLOW,2);px=x;py0=y;first=false;}
        text(c,f(max),R+10,T+14,9,Color.WHITE);
    }

    private double trueRange(int i){Candle z=cs.get(i),pr=cs.get(i-1);return Math.max(z.h-z.l,Math.max(Math.abs(z.h-pr.c),Math.abs(z.l-pr.c)));}

    private double rsi14(){
        int n=Math.min(14,cs.size()-1);if(n<=0)return 50;double g=0,l=0;
        for(int i=cs.size()-n;i<cs.size();i++){double d=cs.get(i).c-cs.get(i-1).c;if(d>0)g+=d;else l-=d;}
        if(l<1e-9)return 100;double rs=(g/n)/(l/n);return 100-(100/(1+rs));
    }

    private double emaValue(int n){
        if(cs.isEmpty())return 0;double k=2.0/(n+1),e=cs.get(0).c;
        for(int i=1;i<cs.size();i++)e=cs.get(i).c*k+e*(1-k);return e;
    }
    private double emaAt(int idx,int n){
        if(cs.isEmpty())return 0;idx=Math.min(idx,cs.size()-1);double k=2.0/(n+1),e=cs.get(0).c;
        for(int i=1;i<=idx;i++)e=cs.get(i).c*k+e*(1-k);return e;
    }
    private double macdAt(int i){return emaAt(i,12)-emaAt(i,26);}
    private double macd12_26(){return emaValue(12)-emaValue(26);}
    private double atr14(){if(cs.size()<2)return 0;int n=Math.min(14,cs.size()-1);double s=0;for(int i=cs.size()-n;i<cs.size();i++)s+=trueRange(i);return s/n;}

    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:lx=e.getX();ly=e.getY();cx=lx;cy=ly;return true;
            case MotionEvent.ACTION_POINTER_DOWN:if(e.getPointerCount()>1)ld=dist(e);return true;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>1){float d=dist(e);if(ld>0)cw=Math.max(7,Math.min(40,cw+(d-ld)*.06f));ld=d;invalidate();return true;}
                float dx=e.getX()-lx,dy=e.getY()-ly;ox+=dx;oy+=dy;oy=Math.max(-120,Math.min(120,oy));
                if(Math.abs(dx)>1)follow=false;lx=e.getX();ly=e.getY();cx=lx;cy=ly;invalidate();return true;
            case MotionEvent.ACTION_UP:invalidate();return true;
        }
        return true;
    }
    private float dist(MotionEvent e){float x=e.getX(0)-e.getX(1),y=e.getY(0)-e.getY(1);return (float)Math.sqrt(x*x+y*y);}
}