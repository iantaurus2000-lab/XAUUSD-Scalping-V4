package com.xauusd.mobileengine;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.*;

public class CandleChartView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Candle> cs = new ArrayList<>();
    private final ArrayList<Candle> indicatorCs = new ArrayList<>();
    private float chartZoom = 1.0f;
    private double bid, ask;
    private float cw = 3.2f, ox = 0, oy = 0, lx, ly, ld, cx, cy;
    private final float leftPad = 18, rightAxis = 82, topPad = 8, bottomPad = 26, lastCandlePad = 12;
    private boolean showEMA50=true, showEMA200=true;
    private boolean showSR=true, showFibo=true, showRSI=true, showMACD=true, showATR=true;
    private boolean showSignals=true, showWickBos=true, showTradeLevels=true, showPosition=true, showBidAsk=true;
    private boolean follow=true;
    private String timeframe="M1";
    private String marketName="XAUUSD";
    private String visualTheme="DEFAULT";
    private volatile long mt5ServerTimeMs=0L;
    private volatile long mt5ServerReceivedElapsedMs=0L;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
    private double signalEntry, signalSl, signalTp1, signalTp2;
    private double[] fibonacciLevels = new double[]{-0.27,0,.236,.382,.5,.618,.786,1};
    private String signalSide="BUY";
    private double positionEntry;
    private boolean signalActive=false;
    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            invalidate();
            if (isAttachedToWindow()) countdownHandler.postDelayed(this, 1000L);
        }
    };

    public CandleChartView(Context c) {
        super(c);
        setBackgroundColor(Color.rgb(4,9,16));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        countdownHandler.removeCallbacks(countdownTick);
        countdownHandler.post(countdownTick);
    }

    @Override protected void onDetachedFromWindow() {
        countdownHandler.removeCallbacks(countdownTick);
        super.onDetachedFromWindow();
    }

    public void setVisualTheme(String theme){ visualTheme=RayyanTheme.normalize(theme); invalidate(); }
    private boolean isLightTheme(){ return "LIGHT".equals(visualTheme); }
    private boolean isDarkTheme(){ return "DARK".equals(visualTheme); }
    private int chartTextColor(){ return isLightTheme()?Color.rgb(78,93,109):Color.WHITE; }
    private int chartMutedTextColor(){ return isLightTheme()?Color.rgb(108,122,137):(isDarkTheme()?Color.rgb(177,195,215):Color.LTGRAY); }
    public void setTimeframe(String tf){ timeframe=tf==null?"M1":tf; invalidate(); }
    /** Anchor MT5 server epoch time to Android's monotonic clock, immune to phone clock changes. */
    public void setServerTime(long serverEpochMs,long receivedElapsedMs){
        if(serverEpochMs<=0L||receivedElapsedMs<=0L)return;
        mt5ServerTimeMs=serverEpochMs;
        mt5ServerReceivedElapsedMs=receivedElapsedMs;
        invalidate();
    }
    public void setMarket(String market){
        String next=(market==null||market.trim().isEmpty())?"XAUUSD":market.trim().toUpperCase(Locale.US);
        if(!next.equals(marketName)){bid=0;ask=0;positionEntry=0;signalActive=false;}
        marketName=next;invalidate();
    }
    public void setData(Candle[] a){
        cs.clear();
        indicatorCs.clear();
        if(a!=null){
            for(Candle z:a){
                if(z!=null&&z.t>0L&&z.o>0&&z.h>=Math.max(z.o,z.c)&&z.l<=Math.min(z.o,z.c)&&z.l>0&&z.h>=z.l)
                    indicatorCs.add(new Candle(z.t,z.o,z.h,z.l,z.c));
            }
        }
        // Keep indicator history for EMA200; render only the newest 50 candles.
        if(indicatorCs.size()>250)indicatorCs.subList(0,indicatorCs.size()-250).clear();
        int start=Math.max(0,indicatorCs.size()-50);
        for(int i=start;i<indicatorCs.size();i++){
            Candle z=indicatorCs.get(i);
            cs.add(new Candle(z.t,z.o,z.h,z.l,z.c));
        }
        if(follow)positionLatestCandle();
        invalidate();
    }

    /** Broker quotes update only quote lines, never the candle-feed OHLC history. */
    public void setQuote(double b,double a,String tf){
        if(!QuoteSafety.valid(b,a))return;
        bid=b; ask=a; if(tf!=null) timeframe=tf;
        invalidate();
    }

    /**
     * Update only the live Bid/Ask overlay. Candle OHLC is authoritative MT5 CopyRates data,
     * supplied through setData(); a tick must never synthesize or mutate an OHLC candle.
     * This prevents midpoint quotes, phone-clock boundaries, and stale ticks from drifting
     * the APK chart away from the selected MT5 symbol/timeframe.
     */
    public void updateLiveBar(double b,double a,long nowMs,String tf){
        if(!QuoteSafety.valid(b,a)||nowMs<=0L)return;
        if(tf!=null) timeframe=tf;
        bid=b;
        ask=a;
        invalidate();
    }

    /** Backwards-compatible tick API; updates price overlays only, never candle OHLC. */
    public void setTick(double b,double a,String tf){
        if(!QuoteSafety.valid(b,a))return;
        setQuote(b,a,tf);
    }
    public void clearQuote(){bid=0;ask=0;invalidate();}

    public void goLive(){ follow=true; oy=0; positionLatestCandle(); invalidate(); }
    public boolean isEmpty(){ return cs.isEmpty(); }
    public double getBid(){ return bid; }
    public double getAsk(){ return ask; }
    public Candle[] getDataSnapshot(){ return cs.toArray(new Candle[0]); }
    public boolean isFollow(){ return follow; }

    public void setSignalLevels(double e,double s,double t1,double t2,boolean active){
        setSignalLevels("BUY",e,s,t1,t2,active);
    }
    public void setSignalLevels(String side,double e,double s,double t1,double t2,boolean active){
        signalSide="SELL".equals(side)?"SELL":"BUY";
        signalEntry=e; signalSl=s; signalTp1=t1; signalTp2=t2; signalActive=active
            &&e>0&&s>0&&t1>0&&t2>0;
        invalidate();
    }
    /** Fixed entry price captured when READY/Manual/Smart Auto is recorded. */
    public void setPositionEntryLevel(double entry){positionEntry=entry>0?entry:0;invalidate();}
    public double getPositionEntryLevel(){return positionEntry;}
    public void setShowEMA50(boolean v){showEMA50=v;invalidate();}
    public void setShowEMA200(boolean v){showEMA200=v;invalidate();}
    public void setShowSR(boolean v){showSR=v;invalidate();}
    public void setShowFibo(boolean v){showFibo=v;invalidate();}
    /** Levels are ratios: 23.6% is 0.236, and -27% is -0.27. */
    public void setFibonacciLevels(double[] levels){
        if(levels==null||levels.length==0)return;
        TreeSet<Double> clean=new TreeSet<>();
        for(double level:levels)if(!Double.isNaN(level)&&!Double.isInfinite(level)&&level>=-5&&level<=5)clean.add(level);
        if(!clean.isEmpty()){fibonacciLevels=new double[clean.size()];int i=0;for(Double level:clean)fibonacciLevels[i++]=level;invalidate();}
    }
    public void setShowRSI(boolean v){showRSI=v;invalidate();}
    public void setShowMACD(boolean v){showMACD=v;invalidate();}
    public void setShowATR(boolean v){showATR=v;invalidate();}
    public void setShowSignals(boolean v){showSignals=v;invalidate();}
    public void setShowWickBos(boolean v){showWickBos=v;invalidate();}
    public void setShowTradeLevels(boolean v){showTradeLevels=v;invalidate();}
    public void setShowPosition(boolean v){showPosition=v;invalidate();}
    public void setShowBidAsk(boolean v){showBidAsk=v;invalidate();}

    private float chartStep(){
        float available=Math.max(150f,getWidth()-leftPad-rightAxis-lastCandlePad);
        float step=Math.max(4.0f,Math.min(9.0f,available/48.0f))*chartZoom;
        cw=Math.max(2.6f,step*0.68f);
        return step;
    }

    private void positionLatestCandle(){
        if(cs.isEmpty()){ox=0;return;}
        float L=leftPad,R=getWidth()-rightAxis,step=chartStep();
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
        if(indicatorCs.size()<n||cs.isEmpty())return;
        double k=2.0/(n+1),e=indicatorCs.get(0).c;
        long firstVisible=cs.get(0).t;
        float px=0,py0=0;boolean first=true;int visibleIndex=0;
        for(Candle bar:indicatorCs){
            e=bar.c*k+e*(1-k);
            if(bar.t<firstVisible)continue;
            float x=L+visibleIndex*step+ox+step/2,y=py(e,lo,hi,T,B);
            visibleIndex++;
            if(x<L-2||x>R+2){px=x;py0=y;continue;}
            if(!first)ln(c,px,py0,x,y,col,2);
            px=x;py0=y;first=false;
        }
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float W=getWidth(),H=getHeight(),L=leftPad,R=W-rightAxis;
        int chartBg=isLightTheme()?Color.rgb(250,251,253):(isDarkTheme()?Color.rgb(5,9,17):Color.rgb(4,9,16));
        int axisBg=isLightTheme()?Color.rgb(245,247,250):(isDarkTheme()?Color.rgb(9,17,29):Color.rgb(9,16,24));
        int axisLine=isLightTheme()?Color.rgb(204,214,225):(isDarkTheme()?Color.rgb(44,62,85):Color.rgb(55,70,84));
        int chartHeaderBg=isLightTheme()?Color.rgb(255,255,255):(isDarkTheme()?Color.rgb(10,20,36):Color.rgb(7,15,23));
        int horizontalGrid=isLightTheme()?Color.rgb(226,232,239):(isDarkTheme()?Color.rgb(29,43,61):Color.rgb(29,39,50));
        int verticalGrid=isLightTheme()?Color.rgb(235,239,245):(isDarkTheme()?Color.rgb(21,35,52):Color.rgb(21,31,41));
        int chartCrosshair=isLightTheme()?Color.rgb(150,163,177):(isDarkTheme()?Color.rgb(100,120,146):Color.GRAY);
        c.drawColor(chartBg);
        p.setStyle(Paint.Style.FILL);p.setColor(axisBg);c.drawRect(R,0,W,H,p);
        ln(c,R,0,R,H,axisLine,2);
        bold(c,"PRICE",R+8,14,9,chartMutedTextColor());
        chartStep();
        if(follow)positionLatestCandle();
        if(cs.isEmpty()){
            text(c,marketName+" • "+timeframe,L+7,22,12,Color.rgb(0,235,150));
            text(c,"Menunggu candle MT5...",L+15,H/2,17,chartMutedTextColor());
            // Render after the empty-state background/text so the countdown cannot be covered.
            drawCandleCountdown(c, L + 34f, 86f, 28f, null);
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
        // Keep the real MT5 Bid/Ask inside the visible scale between OHLC uploads.
        if(bid>0){lo=Math.min(lo,bid);hi=Math.max(hi,bid);}
        if(ask>0){lo=Math.min(lo,ask);hi=Math.max(hi,ask);}
        double pad=Math.max((hi-lo)*.10,.40);lo-=pad;hi+=pad;

        Candle last=cs.get(cs.size()-1);
        // Chart header, matching the reference MT5-like layout.
        p.setColor(chartHeaderBg);c.drawRect(L,0,R,52,p);
        // Draw after the chart header fill so the timer stays visible.
        drawCandleCountdown(c, L + 34f, 86f, 28f, last);
        bold(c,marketName+" • "+timeframe+"  ▾",L+7,16,12,Color.rgb(0,238,150));
        text(c,"O "+f(last.o)+"   H "+f(last.h)+"   L "+f(last.l)+"   C "+f(last.c),L+7,34,10,chartTextColor());
        double e50=emaValue(50),e200=emaValue(200);
        float lx0=L+7;
        if(showEMA50){text(c,"■ EMA 50",lx0,49,9,Color.rgb(255,176,0));text(c,indicatorCs.size()>=50?f(e50):"--",lx0+53,49,9,Color.rgb(255,176,0));lx0+=96;}
        if(showEMA200){text(c,"■ EMA 200",lx0,49,9,Color.rgb(186,85,211));text(c,indicatorCs.size()>=200?f(e200):"--",lx0+60,49,9,Color.rgb(186,85,211));}

        float T=55,B=mainBottom-22;
        // Main grid.
        for(int i=0;i<=6;i++){float y=T+i*(B-T)/6;ln(c,L,y,R,y,horizontalGrid,1);}
        for(int i=0;i<=8;i++){float x=L+i*(R-L)/8;ln(c,x,T,x,B,verticalGrid,1);}

        float step=chartStep();int n=cs.size();
        int st=Math.max(0,(int)Math.floor((-ox)/step)-3);
        int en=Math.min(n,(int)Math.ceil((R-L-ox)/step)+3);
        for(int i=st;i<en;i++){
            Candle z=cs.get(i);float x=L+i*step+ox+step/2;
            float yh=py(z.h,lo,hi,T,B),yl=py(z.l,lo,hi,T,B),yo=py(z.o,lo,hi,T,B),yc=py(z.c,lo,hi,T,B);
            int col=z.c>=z.o?Color.rgb(34,197,94):Color.rgb(239,68,68);
            ln(c,x,yh,x,yl,col,2);p.setColor(col);p.setStyle(Paint.Style.FILL);
            c.drawRect(x-cw/2,Math.min(yo,yc),x+cw/2,Math.max(yo,yc)+1,p);
            if(i==n-1){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(chartTextColor());c.drawRect(x-cw/2-2,Math.min(yo,yc)-2,x+cw/2+2,Math.max(yo,yc)+2,p);p.setStyle(Paint.Style.FILL);}
        }

        if(showEMA50)ema(c,50,Color.rgb(255,176,0),L,R,T,B,lo,hi,step);
        if(showEMA200)ema(c,200,Color.rgb(186,85,211),L,R,T,B,lo,hi,step);

        if(showSR){
            double res=hi-pad*.25,sup=lo+pad*.25;float ry=py(res,lo,hi,T,B),sy=py(sup,lo,hi,T,B);
            ln(c,L,ry,R,ry,Color.rgb(255,152,0),1);ln(c,L,sy,R,sy,Color.rgb(0,188,212),1);
            text(c,"RES",L+4,ry-4,8,Color.rgb(255,152,0));text(c,"SUP",L+4,sy-4,8,Color.rgb(0,188,212));
        }
        if(showFibo){
            for(double q:fibonacciLevels){
                float y=py(hi-(hi-lo)*q,lo,hi,T,B);
                ln(c,L,y,R,y,q==.5?Color.rgb(0,188,212):Color.rgb(100,75,130),1);
                text(c,String.format(Locale.US,"%.1f%%  (%.2f)",q*100,hi-(hi-lo)*q),L+7,y-2,8,q==.5?Color.CYAN:Color.rgb(190,150,220));
            }
        }

        if(showBidAsk){
            if(bid>0){float y=py(bid,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(0,255,145),2);box(c,"BID "+f(bid),y,Color.rgb(0,200,125),Color.BLACK);}
            if(ask>0){float y=py(ask,lo,hi,T,B);ln(c,L,y,R,y,Color.rgb(255,65,85),2);box(c,"ASK "+f(ask),y,Color.rgb(225,45,65),Color.WHITE);}
        }

        if(showPosition&&positionEntry>0){
            float y=py(positionEntry,lo,hi,T,B);
            if(y>=T&&y<=B){
                p.setPathEffect(new DashPathEffect(new float[]{9,5},0));
                ln(c,L,y,R,y,Color.rgb(0,210,255),2);
                p.setPathEffect(null);
                box(c,"OPEN ENTRY "+f(positionEntry),y,Color.rgb(0,170,220),Color.BLACK);
            }
        }

        if(showTradeLevels&&signalActive){
            // Shaded risk/reward blocks make the active Entry Ready setup readable at a glance.
            float ye=py(signalEntry,lo,hi,T,B), ys=py(signalSl,lo,hi,T,B);
            float y1=py(signalTp1,lo,hi,T,B), y2=py(signalTp2,lo,hi,T,B);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(48,245,55,70));
            c.drawRect(L,Math.min(ye,ys),R,Math.max(ye,ys),p);
            p.setColor(Color.argb(38,0,210,120));
            c.drawRect(L,Math.min(ye,y1),R,Math.max(ye,y1),p);
            p.setColor(Color.argb(24,0,155,100));
            c.drawRect(L,Math.min(y1,y2),R,Math.max(y1,y2),p);

            // Trade-specific Fibonacci ladder: TP1 marks 0.0% (the prior swing),
            // while TP2 marks the -27.0% extension used as the second target.
            double fibRange=Math.abs(signalTp2-signalTp1)/0.27;
            double fib0=signalTp1;
            double[] fib=fibonacciLevels;
            for(int i=0;i<fib.length;i++){
                int fibColor=fib[i]<0?Color.rgb(245,145,65):(Math.abs(fib[i]-.5)<.0001?Color.rgb(0,188,212):Color.rgb(160,130,210));
                double level="BUY".equals(signalSide)?fib0-fibRange*fib[i]:fib0+fibRange*fib[i];
                float fy=py(level,lo,hi,T,B);
                if(fy>=T&&fy<=B){
                    p.setPathEffect(new DashPathEffect(new float[]{4,4},0));
                    ln(c,L,fy,R,fy,fibColor,1);
                    p.setPathEffect(null);
                    text(c,String.format(Locale.US,"F%.1f %.2f",fib[i]*100,level),L+4,fy-3,8,fibColor);
                }
            }

            double[] vals={signalEntry,signalSl,signalTp1,signalTp2};
            String[] names={"ENTRY","SL","TP1","TP2"};
            int[] cols={Color.rgb(3,190,240),Color.rgb(245,55,70),Color.rgb(0,220,120),Color.rgb(0,220,120)};
            for(int i=0;i<4;i++){
                float y=py(vals[i],lo,hi,T,B);
                if(y>=T&&y<=B){ln(c,L,y,R,y,cols[i],i==0?2:1);box(c,names[i]+" "+f(vals[i]),y,cols[i],i==1?Color.WHITE:Color.BLACK);}
            }
        }

        float mx=L+(n-1)*step+ox+step/2;
        if(showSignals){
            boolean buy=last.c>=last.o;
            String s=buy?"▲ BUY":"▼ SELL";
            float y=buy?py(last.l,lo,hi,T,B)+18:py(last.h,lo,hi,T,B)-10;
            bold(c,s,Math.max(L,mx-20),Math.max(T+12,Math.min(B-4,y)),12,buy?Color.rgb(34,197,94):Color.rgb(239,68,68));
        }
        if(showWickBos){
            text(c,"Liquidity Sweep",L+85,B-10,9,Color.YELLOW);
            text(c,"Wick Rejection + BOS",L+85,B+4,9,chartTextColor());
        }

        double unit=niceStep(hi-lo),q0=Math.ceil(lo/unit)*unit;
        for(double q=q0;q<=hi+unit*.25;q+=unit){
            float y=py(q,lo,hi,T,B);if(y>=T-2&&y<=B+2)text(c,f(q),R+7,y+4,10,chartTextColor());
        }
        for(int i=0;i<6;i++){
            int idx=Math.min(n-1,i*(n-1)/5);float x=L+idx*step+ox+step/2;
            if(x>=L&&x<=R)text(c,time.format(new Date(cs.get(idx).t)),x-15,B+16,9,chartMutedTextColor());
        }

        // Indicator sub-panels: real RSI / MACD / ATR plots rather than text-only values.
        float panelTop=mainBottom+2;
        if(showRSI){drawRsi(c,L,R,panelTop,panelTop+each);panelTop+=each+gap;}
        if(showMACD){drawMacd(c,L,R,panelTop,panelTop+each);panelTop+=each+gap;}
        if(showATR){drawAtr(c,L,R,panelTop,panelTop+each);}

        if(cx>0){ln(c,cx,T,cx,B,chartCrosshair,1);ln(c,L,cy,R,cy,chartCrosshair,1);}
    }

    private long periodForTimeframe() {
        String tf = timeframe == null ? "M1" : timeframe.toUpperCase(Locale.US);
        switch (tf) {
            case "M1": return 60_000L;
            case "M5": return 5 * 60_000L;
            case "M15": return 15 * 60_000L;
            case "M30": return 30 * 60_000L;
            case "H1": return 60 * 60_000L;
            case "H4": return 4 * 60 * 60_000L;
            case "D1": return 24 * 60 * 60_000L;
            default: return 60_000L;
        }
    }

    private void drawCandleCountdown(Canvas c, float cx, float cy, float radius, Candle latest) {
        long period = periodForTimeframe();
        long elapsedNow=SystemClock.elapsedRealtime();
        long serverAnchor=mt5ServerTimeMs;
        long receivedAnchor=mt5ServerReceivedElapsedMs;
        boolean serverClockValid=serverAnchor>0L&&receivedAnchor>0L&&elapsedNow>=receivedAnchor;
        long remaining=0L;
        if(serverClockValid&&latest!=null&&latest.t>0L){
            long estimatedServerNow=serverAnchor+(elapsedNow-receivedAnchor);
            long elapsedInBar=Math.floorMod(estimatedServerNow-latest.t,period);
            remaining=period-elapsedInBar;
            if(remaining<=0L||remaining>period)remaining=period;
        }

        int ring = isLightTheme() ? Color.rgb(35, 112, 174) : Color.rgb(0, 215, 155);
        int face = isLightTheme() ? Color.rgb(255,255,255) : Color.rgb(8,22,34);
        int fg = chartTextColor();
        p.setStyle(Paint.Style.FILL);
        p.setColor(face);
        c.drawCircle(cx, cy, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.2f);
        p.setColor(isLightTheme() ? Color.rgb(218,226,235) : Color.rgb(45,65,80));
        c.drawCircle(cx, cy, radius-1.6f, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(ring);
        float progress = serverClockValid&&latest!=null&&latest.t>0L
            ? (float)(remaining / (double)period) : 0f;
        c.drawArc(cx-radius+2,cy-radius+2,cx+radius-2,cy+radius-2,
            -90f,360f*progress,false,p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        String label;
        if(!serverClockValid||latest==null||latest.t<=0L){
            label="--";
        } else if (period <= 60_000L) {
            label = String.format(Locale.US, "%02d", Math.max(0L, (remaining+999L)/1000L));
        } else if (period < 60*60_000L) {
            long totalSeconds=(remaining+999L)/1000L;
            label=String.format(Locale.US,"%02d:%02d",totalSeconds/60,totalSeconds%60);
        } else {
            long totalMinutes=(remaining+59_999L)/60_000L;
            label=String.format(Locale.US,"%02d:%02d",totalMinutes/60,totalMinutes%60);
        }
        float size=label.length()>4?9f:label.length()>2?11f:16f;
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(size);
        p.setColor(fg);
        float tw=p.measureText(label);
        c.drawText(label,cx-tw/2f,cy+size*.34f,p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(8f);
        p.setColor(chartMutedTextColor());
        String tf=timeframe==null?"M1":timeframe.toUpperCase(Locale.US);
        float tfw=p.measureText(tf);
        // TF is drawn just below the circle, still inside the reserved price-axis gutter.
        c.drawText(tf,cx-tfw/2f,cy+radius+10f,p);
    }

    private void panelGrid(Canvas c,float L,float R,float T,float B){
        p.setStyle(Paint.Style.FILL);p.setColor(isLightTheme()?Color.rgb(249,251,253):(isDarkTheme()?Color.rgb(9,16,27):Color.rgb(6,14,23)));c.drawRect(L,T,R,B,p);
        for(int i=0;i<=2;i++){float y=T+i*(B-T)/2;ln(c,L,y,R,y,isLightTheme()?Color.rgb(226,232,239):(isDarkTheme()?Color.rgb(29,43,61):Color.rgb(29,42,54)),1);}
        ln(c,R,T,R,B,isLightTheme()?Color.rgb(204,214,225):(isDarkTheme()?Color.rgb(44,62,85):Color.rgb(45,60,72)),1);
    }

    private void drawRsi(Canvas c,float L,float R,float T,float B){
        panelGrid(c,L,R,T,B);
        text(c,"RSI (14)  "+f(rsi14()),L+7,T+16,10,chartTextColor());
        ln(c,L,T+9,R,T+9,Color.rgb(160,90,210),1);
        ln(c,L,T+(B-T)*.5f,R,T+(B-T)*.5f,Color.rgb(120,90,150),1);
        ln(c,L,B-9,R,B-9,Color.rgb(160,90,210),1);
        drawSeries(c,L,R,T+8,B-8,30,70,true);
        text(c,"70",R+10,T+12,9,chartTextColor());text(c,"50",R+10,(T+B)/2+3,9,chartTextColor());text(c,"30",R+10,B-5,9,chartTextColor());
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
        text(c,"MACD (12,26,9)  "+f(m)+"  "+f(sig),L+7,T+16,10,chartTextColor());
        float z=(T+B)/2;ln(c,L,z,R,z,Color.rgb(80,95,110),1);
        int n=cs.size();if(n<3)return;float step=(R-L)/Math.max(1,n-1),px=0,py0=0;boolean first=true;
        for(int i=0;i<n;i++){
            double v=macdAt(i);float x=L+i*step;float y=(float)(z-v/Math.max(.01,Math.abs(m)*2+.15)*(B-T)*.38);
            if(i%1==0){ln(c,x,z,x,y,v>=0?Color.rgb(0,225,150):Color.rgb(245,65,85),3);}
            if(!first)ln(c,px,py0,x,y,Color.rgb(0,190,240),1);px=x;py0=y;first=false;
        }
        text(c,"0.00",R+10,z+3,9,chartTextColor());
    }

    private void drawAtr(Canvas c,float L,float R,float T,float B){
        panelGrid(c,L,R,T,B);
        text(c,"ATR (14)  "+f(atr14()),L+7,T+16,10,chartTextColor());
        int n=cs.size();if(n<2)return;double max=0;for(int i=Math.max(1,n-20);i<n;i++)max=Math.max(max,trueRange(i));
        max=Math.max(max,.01);float step=(R-L)/Math.max(1,n-1),px=0,py0=0;boolean first=true;
        for(int i=1;i<n;i++){double v=trueRange(i);float x=L+i*step,y=(float)(B-5-(v/max)*(B-T-20));if(!first)ln(c,px,py0,x,y,Color.YELLOW,2);px=x;py0=y;first=false;}
        text(c,f(max),R+10,T+14,9,chartTextColor());
    }

    private double trueRange(int i){Candle z=cs.get(i),pr=cs.get(i-1);return Math.max(z.h-z.l,Math.max(Math.abs(z.h-pr.c),Math.abs(z.l-pr.c)));}

    private double rsi14(){
        int n=Math.min(14,cs.size()-1);if(n<=0)return 50;double g=0,l=0;
        for(int i=cs.size()-n;i<cs.size();i++){double d=cs.get(i).c-cs.get(i-1).c;if(d>0)g+=d;else l-=d;}
        if(l<1e-9)return 100;double rs=(g/n)/(l/n);return 100-(100/(1+rs));
    }

    private double emaValue(int n){
        if(indicatorCs.isEmpty())return 0;double k=2.0/(n+1),e=indicatorCs.get(0).c;
        for(int i=1;i<indicatorCs.size();i++)e=indicatorCs.get(i).c*k+e*(1-k);return e;
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
                if(e.getPointerCount()>1){float d=dist(e);if(ld>0)chartZoom=Math.max(.70f,Math.min(1.8f,chartZoom+(d-ld)*.003f));ld=d;invalidate();return true;}
                float dx=e.getX()-lx,dy=e.getY()-ly;ox+=dx;oy+=dy;oy=Math.max(-120,Math.min(120,oy));
                if(Math.abs(dx)>1)follow=false;lx=e.getX();ly=e.getY();cx=lx;cy=ly;invalidate();return true;
            case MotionEvent.ACTION_UP:invalidate();return true;
        }
        return true;
    }
    private float dist(MotionEvent e){float x=e.getX(0)-e.getX(1),y=e.getY(0)-e.getY(1);return (float)Math.sqrt(x*x+y*y);}
}
