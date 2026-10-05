package com.xauusd.mobileengine

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

class CandleChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var candles: List<StrategyEngine.Candle> = emptyList()
    private var timeframe = "M1"
    private var currentPrice: Double? = null
    private var bid: Double? = null
    private var ask: Double? = null
    private var decision: StrategyEngine.Decision? = null
    private var showEma = true
    private var showLevels = true
    private var showFib = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val candleBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val candleWickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var offset = 0
    private var autoScroll = true
    private var dragX = 0f
    private var dragY = 0f
    private var scaleY = 1f
    private var visibleCount = 48
    private var crossX = -1f
    private var crossY = -1f
    private var showCross = false
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleY = (scaleY * d.scaleFactor).coerceIn(0.35f, 4f)
                visibleCount = (visibleCount / d.scaleFactor).toInt().coerceIn(15, 120)
                invalidate(); return true
            }
        })

    fun setData(data: List<StrategyEngine.Candle>, tf: String, live: Double?,
                sig: StrategyEngine.Decision?, bidP: Double? = null, askP: Double? = null) {
        candles = data.takeLast(200); timeframe = tf
        currentPrice = live ?: candles.lastOrNull()?.close
        bid = bidP; ask = askP; decision = sig
        if (autoScroll) offset = 0
        invalidate()
    }

    fun setLayers(ema: Boolean, levels: Boolean, fib: Boolean = true) {
        showEma = ema; showLevels = levels; showFib = fib; invalidate()
    }

    fun resetView() {
        offset = 0; autoScroll = true; scaleY = 1f; visibleCount = 48; invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragX=e.x; dragY=e.y; crossX=e.x; crossY=e.y; showCross=true; invalidate(); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx=e.x-dragX; val dy=e.y-dragY
                    if(abs(dx)>6) { autoScroll=false; offset=(offset+if(dx>0)-1 else 1).coerceIn(0,max(0,candles.size-10)); dragX=e.x }
                    if(abs(dy)>10) { scaleY=(scaleY+dy*.002f).coerceIn(.35f,4f); dragY=e.y }
                    crossX=e.x; crossY=e.y; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if(e.x>width*.85f) resetView() else {showCross=false;invalidate()}; return true }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(8,12,18))
        if(candles.isEmpty()) { paint.color=Color.LTGRAY;paint.textSize=15f;canvas.drawText("Menunggu data Biquote 1s...",24f,height/2f,paint);return }
        val priceColW=92f;val left=10f;val right=width-priceColW;val top=40f;val bottom=height-25f
        val w=max(1f,right-left);val h=max(1f,bottom-top)
        val end=candles.size-offset;val start=max(0,end-visibleCount)
        val visible=if(start<end&&end<=candles.size)candles.subList(start,end) else candles.takeLast(visibleCount)
        if(visible.isEmpty())return
        var hi=visible.maxOf{it.high};var lo=visible.minOf{it.low}
        currentPrice?.let{hi=max(hi,it);lo=min(lo,it)};bid?.let{hi=max(hi,it);lo=min(lo,it)};ask?.let{hi=max(hi,it);lo=min(lo,it)}
        decision?.let{
            if(it.entry>0) hi=max(hi,it.entry); if(it.tp1>0) hi=max(hi,it.tp1); if(it.tp2>0) hi=max(hi,it.tp2)
            if(it.sl>0) lo=min(lo,it.sl)
        }
        val pad=max((hi-lo)*.12,.5);val mid=(hi+lo)/2.0;val range=max(((hi-lo)+pad*2)/scaleY,1.0)
        val maxP=mid+range/2;val minP=mid-range/2
        fun y(p:Double):Float { val t=((p-minP)/(maxP-minP)).coerceIn(.02,.98);return (bottom-t*h).toFloat() }

        paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=12f;paint.color=Color.WHITE
        canvas.drawText("XAU/USD  "+timeframe+" • LIVE 1s",left,16f,paint)
        paint.typeface=Typeface.DEFAULT;paint.textSize=10f;paint.color=Color.rgb(129,199,132)
        canvas.drawText(String.format(Locale.US,"H %.2f",hi),left+110f,16f,paint)
        paint.color=Color.rgb(239,83,80);canvas.drawText(String.format(Locale.US,"L %.2f",lo),left+190f,16f,paint)
        paint.color=Color.GRAY;canvas.drawText(if(autoScroll)"AUTO" else "SCROLL",left+270f,16f,paint)

        paint.textSize=10f
        for(i in 0..6){val gy=top+h*i/6f;paint.color=Color.rgb(28,34,44);paint.strokeWidth=1f;canvas.drawLine(left,gy,right,gy,paint);val pv=maxP-(maxP-minP)*i/6.0;paint.color=Color.rgb(170,180,190);canvas.drawText(String.format(Locale.US,"%.2f",pv),right+4f,gy+3f,paint)}
        val n=visible.size;val step=w/(n+1f);val bw=max(6.0f,step*.72f)
        visible.forEachIndexed{i,c->{
            val x=left+step*i+step/2
            val up=c.close>=c.open
            val bodyColor=if(up)Color.rgb(38,198,120) else Color.rgb(239,83,80)
            candleWickPaint.style=Paint.Style.STROKE; candleWickPaint.strokeWidth=2.2f; candleWickPaint.color=bodyColor
            canvas.drawLine(x,y(c.high),x,y(c.low),candleWickPaint)
            val a=y(max(c.open,c.close)); val b=y(min(c.open,c.close))
            candleBodyPaint.style=Paint.Style.FILL; candleBodyPaint.color=bodyColor
            canvas.drawRect(x-bw/2,a,x+bw/2,max(b,a+7f),candleBodyPaint)
        }}

        if(showEma){drawEma(canvas,visible,9,Color.rgb(255,193,7),left,step,::y);drawEma(canvas,visible,21,Color.rgb(66,165,245),left,step,::y);drawEma(canvas,visible,50,Color.rgb(186,104,200),left,step,::y)}
        if(showLevels){
            val lb=max(2,min(45,visible.size-1));val sr=StrategyEngine.resistance(ArrayList(visible),lb,visible.size-1);val ss=StrategyEngine.support(ArrayList(visible),lb,visible.size-1)
            level(canvas,sr,::y,"R",Color.rgb(239,83,80),left,right);level(canvas,ss,::y,"S",Color.rgb(66,165,245),left,right)
            if(showFib){val fh=visible.maxOf{it.high};val fl=visible.minOf{it.low};val d=fh-fl;listOf("38.2" to fh-d*.382,"50.0" to fh-d*.50,"61.8" to fh-d*.618).forEach{q->paint.color=Color.argb(150,186,104,200);paint.strokeWidth=1f;canvas.drawLine(left,y(q.second),right,y(q.second),paint);paint.textSize=8f;canvas.drawText(q.first,right-28f,y(q.second)-2f,paint)}}
        }
        // Dedicated foreground candle pass: real green/red bodies remain visible
        // even when EMA/Fibonacci layers overlap the price action.
        visible.forEachIndexed{i,c->{
            val x=left+step*i+step/2
            val up=c.close>=c.open
            val bodyColor=if(up)Color.rgb(38,198,120) else Color.rgb(239,83,80)
            candleBodyPaint.style=Paint.Style.FILL; candleBodyPaint.color=bodyColor
            val a=y(max(c.open,c.close)); val b=y(min(c.open,c.close))
            canvas.drawRect(x-bw/2,a,x+bw/2,max(b,a+7f),candleBodyPaint)
        }}
        currentPrice?.let{lineBadge(canvas,y(it),String.format(Locale.US,"LIVE %.2f",it),Color.rgb(38,50,56),right)}
        bid?.let{lineBadge(canvas,y(it),String.format(Locale.US,"B %.2f",it),Color.rgb(30,80,50),right)}
        ask?.let{lineBadge(canvas,y(it),String.format(Locale.US,"A %.2f",it),Color.rgb(90,40,40),right)}
        decision?.let{if(it.entry>0)level(canvas,it.entry,::y,"ENTRY",if(it.side.startsWith("BUY"))Color.rgb(0,230,118) else Color.rgb(255,82,82),left,right);if(it.sl>0)level(canvas,it.sl,::y,"SL",Color.RED,left,right);if(it.tp1>0)level(canvas,it.tp1,::y,"TP1",Color.CYAN,left,right);if(it.tp2>0)level(canvas,it.tp2,::y,"TP2",Color.GREEN,left,right)}
        if(showCross&&crossX in left..right&&crossY in top..bottom){paint.color=Color.argb(170,210,210,210);paint.strokeWidth=1f;canvas.drawLine(crossX,top,crossX,bottom,paint);canvas.drawLine(left,crossY,right,crossY,paint);val pv=minP+(1.0-((crossY-top)/h))*(maxP-minP);paint.color=Color.YELLOW;paint.textSize=10f;canvas.drawText(String.format(Locale.US,"%.2f",pv),crossX+6,crossY-6,paint)}
        // Time axis + small signal marker
        paint.color=Color.rgb(145,155,165);paint.textSize=10f
        val labels=min(5,n)
        for(k in 0 until labels){
            val idx=if(labels==1)0 else k*(n-1)/(labels-1)
            val cc=visible[idx]
            val tm=if(cc.t<100000000000L)cc.t*1000L else cc.t
            val s=java.text.SimpleDateFormat("HH:mm",Locale.US).format(java.util.Date(tm))
            val xx=left+step*idx+step/2f
            canvas.drawText(s,xx-14f,bottom+18f,paint)
        }
        decision?.let{d->
            if(visible.isNotEmpty()&&(d.wick||d.sweep||d.bos)){
                val last=visible.last();val x=left+step*(visible.size-1)+step/2f
                val yy=if(d.side.startsWith("BUY"))y(last.low)-7f else y(last.high)+12f
                paint.color=if(d.side.startsWith("BUY"))Color.rgb(0,230,118) else Color.rgb(255,82,82)
                paint.textSize=9f;canvas.drawText(if(d.side.startsWith("BUY")) "▲ BUY" else "▼ SELL",x-20f,yy,paint)
            }
        }
        paint.color=Color.GRAY;paint.textSize=10f;canvas.drawText("← drag →   ↑↓ move   pinch/zoom   tap RIGHT = LIVE",left,bottom+32f,paint)
    }

    private fun drawEma(c:Canvas,a:List<StrategyEngine.Candle>,period:Int,color:Int,left:Float,step:Float,y:(Double)->Float){if(a.size<period)return;val vals=ArrayList(a);var prev:PointF?=null;for(i in a.indices){val v=StrategyEngine.ema(vals,period,i);val pt=PointF(left+step*i+step/2,y(v));if(prev!=null){paint.color=color;paint.strokeWidth=1.7f;c.drawLine(prev!!.x,prev!!.y,pt.x,pt.y,paint)};prev=pt}}
    private fun level(c:Canvas,v:Double,y:(Double)->Float,label:String,color:Int,left:Float,right:Float){val py=y(v);if(py<28||py>height-22)return;paint.color=color;paint.strokeWidth=1.3f;c.drawLine(left,py,right,py,paint);paint.textSize=9f;c.drawText(label+" "+String.format(Locale.US,"%.2f",v),right-70f,py-3f,paint)}
    private fun lineBadge(c:Canvas,py:Float,label:String,bg:Int,right:Float){paint.textSize=10f;val tw=paint.measureText(label)+10f;paint.color=bg;c.drawRoundRect(right+2f,py-9f,right+2f+tw,py+9f,3f,3f,paint);paint.color=Color.WHITE;c.drawText(label,right+6f,py+3f,paint)}
}