package com.xauusd.mobileengine;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class GatewayService extends Service {
    private ServerSocket server;
    private ExecutorService serverPool;
    private ScheduledExecutorService engine;
    private volatile boolean running;
    private volatile String lastBgStatus = "";
    private volatile long lastBgHeartbeat = 0L;
    private volatile long lastNextPollLog = 0L;
    private volatile long nextBgAttemptAt = 0L;
    private volatile int bgFailureCount = 0;

    public static boolean isEaRecentlyOnline(Context c){
        long last=c.getSharedPreferences("rayyan4_gateway",Context.MODE_PRIVATE).getLong("ea_last_poll_ms",0L);
        long age=System.currentTimeMillis()-last;
        return last>0L && age>=0L && age<=90000L;
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(77,notification("Background Engine berjalan • menyiapkan LAN Gateway"));
        if(!running){ running=true; startServer(); startBackgroundEngine(); }
        return START_STICKY;
    }

    private void startServer(){
        serverPool=Executors.newCachedThreadPool();
        serverPool.execute(()->{
            try{
                server=new ServerSocket(8787);
                AppLog.add(this,"GATEWAY","LAN gateway listening on port 8787");
                getSystemService(NotificationManager.class).notify(77,notification("MT5 LAN Gateway aktif • port 8787 | Background Engine berjalan"));
                while(!server.isClosed()){
                    final Socket s=server.accept();
                    serverPool.execute(()->handle(s));
                }
            }catch(Exception e){
                AppLog.add(this,"GATEWAY","server stopped: "+e.getMessage());
                getSystemService(NotificationManager.class).notify(77,notification("LAN Gateway gagal aktif • periksa port 8787"));
            }
        });
    }

    private void handle(Socket s){
        try(Socket socket=s){
            socket.setSoTimeout(5000);
            InputStream in=socket.getInputStream();
            String requestLine=readHttpLine(in);
            if(requestLine==null||requestLine.isEmpty())return;
            String method=requestLine.split(" ")[0];
            String path=first(requestLine);
            int contentLength=0;
            String line;
            while((line=readHttpLine(in))!=null&&!line.isEmpty()){
                int colon=line.indexOf(':');
                if(colon>0&&"content-length".equalsIgnoreCase(line.substring(0,colon).trim())){
                    try{contentLength=Integer.parseInt(line.substring(colon+1).trim());}catch(Exception ignored){}
                }
            }
            if(path.startsWith("/bars/upload")&&"POST".equalsIgnoreCase(method)){
                if(contentLength<20||contentLength>250000){
                    writeResponse(socket,400,"text/plain; charset=utf-8","BARS ERROR SIZE".getBytes(StandardCharsets.UTF_8));return;
                }
                byte[] raw=readExact(in,contentLength);
                if(raw==null){writeResponse(socket,400,"text/plain; charset=utf-8","BARS ERROR BODY".getBytes(StandardCharsets.UTF_8));return;}
                String payload=new String(raw,StandardCharsets.UTF_8);
                String result=receiveBarsUpload(payload);
                int code=result.startsWith("BARS OK")?200:400;
                writeResponse(socket,code,"text/plain; charset=utf-8",result.getBytes(StandardCharsets.UTF_8));return;
            }
            if(path.startsWith("/quote/upload")&&"POST".equalsIgnoreCase(method)){
                if(contentLength<20||contentLength>4096){
                    writeResponse(socket,400,"text/plain; charset=utf-8","QUOTE ERROR SIZE".getBytes(StandardCharsets.UTF_8));return;
                }
                byte[] raw=readExact(in,contentLength);
                if(raw==null){writeResponse(socket,400,"text/plain; charset=utf-8","QUOTE ERROR BODY".getBytes(StandardCharsets.UTF_8));return;}
                String result=receiveQuoteUpload(new String(raw,StandardCharsets.UTF_8));
                int code=result.startsWith("QUOTE OK")?200:400;
                writeResponse(socket,code,"text/plain; charset=utf-8",result.getBytes(StandardCharsets.UTF_8));return;
            }
            if(path.startsWith("/chart/upload")&&"POST".equalsIgnoreCase(method)){
                if(contentLength<8||contentLength>2000000){
                    writeResponse(socket,400,"text/plain; charset=utf-8","CHART ERROR SIZE".getBytes(StandardCharsets.UTF_8));return;
                }
                byte[] png=readExact(in,contentLength);
                if(png==null||png.length<8||png[0]!=(byte)0x89||png[1]!=0x50||png[2]!=0x4e||png[3]!=0x47){
                    writeResponse(socket,400,"text/plain; charset=utf-8","CHART ERROR PNG".getBytes(StandardCharsets.UTF_8));return;
                }
                // Write to a temporary file first so the UI never decodes a half-written PNG.
                File temp=getFileStreamPath("mt5_chart.tmp");
                File target=getFileStreamPath("mt5_chart.png");
                try(FileOutputStream file=new FileOutputStream(temp,false)){
                    file.write(png);file.flush();file.getFD().sync();
                }
                if(!temp.renameTo(target)){
                    // Some Android filesystems do not replace an existing target on rename.
                    if(target.exists()&&!target.delete()){
                        temp.delete();
                        writeResponse(socket,500,"text/plain; charset=utf-8","CHART ERROR REPLACE".getBytes(StandardCharsets.UTF_8));return;
                    }
                    if(!temp.renameTo(target)){
                        temp.delete();
                        writeResponse(socket,500,"text/plain; charset=utf-8","CHART ERROR COMMIT".getBytes(StandardCharsets.UTF_8));return;
                    }
                }
                long receivedAt=System.currentTimeMillis();
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit()
                    .putLong("mt5_chart_received_ms",receivedAt).putInt("mt5_chart_bytes",png.length).apply();
                writeResponse(socket,200,"text/plain; charset=utf-8","CHART OK".getBytes(StandardCharsets.UTF_8));return;
            }
            if(path.startsWith("/chart/latest")){
                File f=getFileStreamPath("mt5_chart.png");
                if(!f.isFile()||f.length()<8){
                    writeResponse(socket,404,"text/plain; charset=utf-8","CHART NOT READY".getBytes(StandardCharsets.UTF_8));return;
                }
                byte[] png=new byte[(int)Math.min(f.length(),2000000L)];
                try(FileInputStream file=new FileInputStream(f)){
                    int offset=0,n;
                    while(offset<png.length&&(n=file.read(png,offset,png.length-offset))>0)offset+=n;
                    if(offset!=png.length)png=Arrays.copyOf(png,offset);
                }
                writeResponse(socket,200,"image/png",png);return;
            }
            String out;
            if(path.startsWith("/next")){
                long now=System.currentTimeMillis();
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putLong("ea_last_poll_ms",now).apply();
                if(now-lastNextPollLog>=30000L){lastNextPollLog=now;AppLog.add(this,"MT5 GATEWAY","EA polling /next received");}
                out=getPending();
            }else if(path.startsWith("/ack")){
                out=ack(query(path,"id"),query(path,"result"),query(path,"detail"));
            }else if(path.startsWith("/indicator_next")){
                getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit().putLong("indicator_ea_last_poll_ms",System.currentTimeMillis()).apply();
                out=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).getString("indicator_next","");
            }else if(path.startsWith("/indicator_ack")){
                String id=query(path,"id");SharedPreferences ip=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
                String queued=ip.getString("indicator_next","");
                if(id!=null&&!id.isEmpty()&&id.equals(field(queued,"id"))){
                    ip.edit().remove("indicator_next").putString("indicator_ack_id",id).putLong("indicator_ack_at",System.currentTimeMillis()).apply();out="INDICATOR ACK OK";
                }else out="INDICATOR ACK STALE";
            }else if(path.startsWith("/power_config"))out=powerConfig(query(path,"symbol"));
            else if(path.startsWith("/power_state"))out=receivePowerState(path);
            else if(path.startsWith("/state"))out=receiveState(path);
            else if(path.startsWith("/quote"))out=receiveQuote(path);
            else out="Rayyan4 gateway OK";
            writeResponse(socket,200,"text/plain; charset=utf-8",out.getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){AppLog.add(this,"GATEWAY","request error: "+e.getMessage());}
    }

    private String readHttpLine(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c,previous=-1;
        while((c=in.read())!=-1){
            if(previous==13&&c==10){
                byte[] a=b.toByteArray();int n=a.length;
                return new String(a,0,n>0&&a[n-1]==13?n-1:n,StandardCharsets.US_ASCII);
            }
            b.write(c);previous=c;if(b.size()>8192)throw new IOException("HTTP header too large");
        }
        return b.size()==0?null:new String(b.toByteArray(),StandardCharsets.US_ASCII);
    }
    private byte[] readExact(InputStream in,int length)throws IOException{
        byte[] data=new byte[length];int offset=0;
        while(offset<length){int n=in.read(data,offset,length-offset);if(n<0)return null;offset+=n;}
        return data;
    }
    private void writeResponse(Socket socket,int status,String contentType,byte[] body)throws IOException{
        String reason=status==200?"OK":status==404?"Not Found":"Bad Request";
        String h="HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+contentType+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o=socket.getOutputStream();o.write(h.getBytes(StandardCharsets.US_ASCII));o.write(body);o.flush();
    }

    /**
     * Accept compact OHLC bars produced by CopyRates() in the connected MT5 terminal.
     * Payload: symbol=XAUUSDm;tf=M1;bars=epochMs,o,h,l,c|epochMs,o,h,l,c...
     */
    private synchronized String receiveBarsUpload(String payload){
        try{
            if(payload==null||payload.isEmpty()||payload.length()>250000)return "BARS ERROR SIZE";
            String[] records=payload.split("\\r?\\n");
            if(records.length<1||records.length>18)return "BARS ERROR BATCH";
            SharedPreferences prefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
            SharedPreferences.Editor edit=prefs.edit();
            long now=System.currentTimeMillis();
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            int total=0;
            for(String record:records){
                if(record==null||record.trim().isEmpty())continue;
                String symbol="",tf="",bars="";
                long serverTimeMs=0L;
                for(String part:record.split(";")){
                    int eq=part.indexOf('=');
                    if(eq<=0)continue;
                    String key=part.substring(0,eq).trim();
                    String value=part.substring(eq+1).trim();
                    if("symbol".equals(key))symbol=value;
                    else if("tf".equals(key))tf=value;
                    else if("bars".equals(key))bars=value;
                    else if("serverTime".equals(key))serverTimeMs=Long.parseLong(value);
                }
                if(!("XAUUSDm".equals(symbol)||"BTCUSDm".equals(symbol)||"ETHUSDm".equals(symbol)))
                    return "BARS ERROR SYMBOL";
                if(!("M1".equals(tf)||"M5".equals(tf)||"M15".equals(tf)||"H1".equals(tf)||"H4".equals(tf)||"D1".equals(tf)))
                    return "BARS ERROR TIMEFRAME";
                String key=symbol+"_"+tf;
                if(!seen.add(key))return "BARS ERROR DUPLICATE "+key;
                String[] rows=bars.split("\\|");
                int max=("M1".equals(tf)||"M5".equals(tf))?250:60;
                if(rows.length<2||rows.length>max)return "BARS ERROR COUNT "+key;
                long previous=0L;
                for(String row:rows){
                    String[] v=row.split(",");
                    if(v.length!=5)return "BARS ERROR FORMAT "+key;
                    long t=Long.parseLong(v[0]);
                    double o=Double.parseDouble(v[1]),h=Double.parseDouble(v[2]);
                    double l=Double.parseDouble(v[3]),c=Double.parseDouble(v[4]);
                    if(t<=previous||!finitePositive(o)||!finitePositive(h)||!finitePositive(l)||!finitePositive(c)
                        ||h<Math.max(o,c)||l>Math.min(o,c)||h<l)return "BARS ERROR VALUES "+key;
                    previous=t;
                }
                edit.putString("mt5_bars_"+symbol+"_"+tf,bars)
                    .putLong("mt5_bars_at_"+symbol+"_"+tf,now)
                    .putInt("mt5_bars_count_"+symbol+"_"+tf,rows.length);
                if(serverTimeMs>0L){
                    edit.putLong("mt5_server_time_ms",serverTimeMs)
                        .putLong("mt5_server_time_received_elapsed",SystemClock.elapsedRealtime());
                }
                total+=rows.length;
            }
            if(seen.isEmpty())return "BARS ERROR EMPTY";
            edit.apply();
            return "BARS OK SERIES="+seen.size()+" CANDLES="+total;
        }catch(Exception e){return "BARS ERROR "+e.getClass().getSimpleName();}
    }
    private boolean finitePositive(double v){
        return v>0.0&&!Double.isNaN(v)&&!Double.isInfinite(v);
    }

    /**
     * Read-only EA order-state heartbeat. This endpoint never queues or sends trades.
     * The APK uses it to distinguish a pending order from an open position before
     * considering cancellation/re-entry.
     */
    private synchronized String receiveState(String path){
        String id=query(path,"id");
        String state=query(path,"state");
        String ticket=query(path,"ticket");
        String symbol=query(path,"symbol");
        String side=query(path,"side");
        String price=query(path,"price");
        if(id==null||id.trim().isEmpty())return "STATE ERROR";
        if(!"NONE".equals(state)&&!"PENDING".equals(state)&&!"POSITION".equals(state))
            return "STATE ERROR";
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String snapshot=id+"|"+state+"|"+ticket+"|"+symbol+"|"+side+"|"+price;
        String previous=p.getString("ea_order_state_snapshot","");
        p.edit().putString("ea_order_state_id",id)
            .putString("ea_order_state",state)
            .putString("ea_order_state_ticket",ticket==null?"":ticket)
            .putString("ea_order_state_symbol",symbol==null?"":symbol)
            .putString("ea_order_state_side",side==null?"":side)
            .putString("ea_order_state_price",price==null?"":price)
            .putString("ea_order_state_snapshot",snapshot)
            .putLong("ea_order_state_at",System.currentTimeMillis()).apply();
        if(!snapshot.equals(previous)){
            AppLog.add(this,"MT5 STATE","id="+id+" state="+state+
                (ticket==null||ticket.isEmpty()?"":" ticket="+ticket)+
                (symbol==null||symbol.isEmpty()?"":" symbol="+symbol));
        }
        return "STATE OK";
    }

    /** Persist real MT5 Bid/Ask, SYMBOL_POINT and terminal tick time. */
    private synchronized String receiveQuote(String path){
        String symbol=query(path,"symbol");
        if(!"XAUUSDm".equals(symbol)&&!"BTCUSDm".equals(symbol)&&!"ETHUSDm".equals(symbol))
            return "QUOTE ERROR SYMBOL";
        try{
            double bid=Double.parseDouble(query(path,"bid"));
            double ask=Double.parseDouble(query(path,"ask"));
            double point=Double.parseDouble(query(path,"point"));
            int digits=Integer.parseInt(query(path,"digits"));
            long tickMs=Long.parseLong(query(path,"tickms"));
            long now=System.currentTimeMillis();
            if(!QuoteSafety.valid(bid,ask)||point<=0.0||digits<0||digits>8||tickMs<=0L)
                return "QUOTE ERROR VALUES";
            getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit()
                .putString("broker_quote_bid_"+symbol,Double.toString(bid))
                .putString("broker_quote_ask_"+symbol,Double.toString(ask))
                .putString("broker_quote_point_"+symbol,Double.toString(point))
                .putInt("broker_quote_digits_"+symbol,digits)
                .putLong("broker_quote_tick_ms_"+symbol,tickMs)
                .putLong("broker_quote_received_ms_"+symbol,now)
                .apply();
            return "QUOTE OK";
        }catch(Exception e){return "QUOTE ERROR FORMAT";}
    }

    /** Accept one atomic batch of the current MT5 tick for each supported APK market. */
    private synchronized String receiveQuoteUpload(String payload){
        try{
            if(payload==null||payload.isEmpty()||payload.length()>4096)return "QUOTE ERROR SIZE";
            String[] records=payload.split("\\r?\\n");
            if(records.length<1||records.length>3)return "QUOTE ERROR BATCH";
            SharedPreferences.Editor edit=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE).edit();
            long received=System.currentTimeMillis();
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(String record:records){
                if(record==null||record.trim().isEmpty())continue;
                String symbol="";double bid=0,ask=0,point=0;int digits=-1;long tickMs=0;
                for(String part:record.split(";")){
                    int eq=part.indexOf('=');if(eq<=0)continue;
                    String key=part.substring(0,eq).trim(),value=part.substring(eq+1).trim();
                    if("symbol".equals(key))symbol=value;
                    else if("bid".equals(key))bid=Double.parseDouble(value);
                    else if("ask".equals(key))ask=Double.parseDouble(value);
                    else if("point".equals(key))point=Double.parseDouble(value);
                    else if("digits".equals(key))digits=Integer.parseInt(value);
                    else if("tickms".equals(key))tickMs=Long.parseLong(value);
                }
                if(!("XAUUSDm".equals(symbol)||"BTCUSDm".equals(symbol)||"ETHUSDm".equals(symbol)))
                    return "QUOTE ERROR SYMBOL";
                if(!seen.add(symbol))return "QUOTE ERROR DUPLICATE";
                if(!QuoteSafety.valid(bid,ask)||!finitePositive(point)||digits<0||digits>8||tickMs<=0L)
                    return "QUOTE ERROR VALUES "+symbol;
                edit.putString("broker_quote_bid_"+symbol,Double.toString(bid))
                    .putString("broker_quote_ask_"+symbol,Double.toString(ask))
                    .putString("broker_quote_point_"+symbol,Double.toString(point))
                    .putInt("broker_quote_digits_"+symbol,digits)
                    .putLong("broker_quote_tick_ms_"+symbol,tickMs)
                    .putLong("broker_quote_received_ms_"+symbol,received);
            }
            if(seen.isEmpty())return "QUOTE ERROR EMPTY";
            edit.apply();
            return "QUOTE OK COUNT="+seen.size();
        }catch(Exception e){return "QUOTE ERROR "+e.getClass().getSimpleName();}
    }

    /**
     * Isolated SMART AUTO-POWERFULL configuration channel.
     * It deliberately does not reuse /next, /ack, or the legacy order-state keys.
     */
    private String powerConfig(String symbol){
        if(!"XAUUSDm".equals(symbol)&&!"BTCUSDm".equals(symbol)&&!"ETHUSDm".equals(symbol))
            return "symbol=INVALID;enabled=0;reason=UNSUPPORTED_SYMBOL";
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String suffix=symbol;
        boolean requested=p.getBoolean("powerfull_enabled_"+suffix,false);
        boolean standardOn=p.getBoolean("rayyan_strategy_enabled_STANDAR_"+suffix,false);
        boolean excellentOn=p.getBoolean("rayyan_strategy_enabled_EXCELLENT_"+suffix,false);
        boolean strategyConflict=requested&&(standardOn||excellentOn);
        boolean buyLimit=p.getBoolean("powerfull_buy_limit_"+suffix,true);
        boolean sellLimit=p.getBoolean("powerfull_sell_limit_"+suffix,true);
        boolean reforecast=p.getBoolean("powerfull_reforecast_"+suffix,true);
        boolean quickProfit=p.getBoolean("powerfull_quick_profit_"+suffix,true);
        boolean crossCandle=p.getBoolean("powerfull_cross_candle_"+suffix,true);
        double lot=p.getFloat("powerfull_lot_"+suffix,0.0f);
        double pip=p.getFloat("powerfull_pip_size_"+suffix,0.0f);
        int percentile=p.getInt("powerfull_percentile_"+suffix,70);
        double stopPips=3.0;
        double minimumLot=("BTCUSDm".equals(symbol)||"ETHUSDm".equals(symbol))?0.10:0.01;
        boolean valid=Double.isFinite(lot)&&lot>=minimumLot
            &&Double.isFinite(pip)&&pip>0.0
            &&(percentile==50||percentile==60||percentile==70||percentile==80||percentile==90);
        boolean hasEntrySide=buyLimit||sellLimit;
        boolean enabled=requested&&valid&&hasEntrySide&&!strategyConflict;
        String reason=enabled?"READY":(!requested?"DISABLED":(strategyConflict?"STRATEGY_CONFLICT":(!valid?"CONFIG_INVALID":"NO_ENTRY_SIDE")));
        return "symbol="+symbol
            +";enabled="+(enabled?"1":"0")
            +";buyLimit="+(buyLimit?"1":"0")
            +";sellLimit="+(sellLimit?"1":"0")
            +";reforecast="+(reforecast?"1":"0")
            +";quickProfit="+(quickProfit?"1":"0")
            +";crossCandleHold="+(crossCandle?"1":"0")
            +";lot="+String.format(Locale.US,"%.4f",lot)
            +";pip="+String.format(Locale.US,"%.8f",pip)
            +";percentile="+percentile
            +";stopPips="+String.format(Locale.US,"%.2f",stopPips)
            +";reason="+reason;
    }

    /** Persists Powerfull-only status reported by the single MT5 EA. */
    private String receivePowerState(String path){
        String symbol=query(path,"symbol");
        if(!"XAUUSDm".equals(symbol)&&!"BTCUSDm".equals(symbol)&&!"ETHUSDm".equals(symbol))
            return "POWER STATE ERROR SYMBOL";
        String status=query(path,"status");
        if(status==null||status.trim().isEmpty())status="UNKNOWN";
        String range=query(path,"range");
        String low=query(path,"low");
        String high=query(path,"high");
        String bar=query(path,"bar");
        String samples=query(path,"samples");
        String days=query(path,"days");
        String pending=query(path,"pending");
        String positions=query(path,"positions");
        String detail=query(path,"detail");
        try{
            if(!range.isEmpty()&&!Double.isFinite(Double.parseDouble(range)))return "POWER STATE ERROR RANGE";
            if(!low.isEmpty()&&!Double.isFinite(Double.parseDouble(low)))return "POWER STATE ERROR LOW";
            if(!high.isEmpty()&&!Double.isFinite(Double.parseDouble(high)))return "POWER STATE ERROR HIGH";
            if(!bar.isEmpty()&&Long.parseLong(bar)<0L)return "POWER STATE ERROR BAR";
            if(!samples.isEmpty()&&Integer.parseInt(samples)<0)return "POWER STATE ERROR SAMPLES";
            if(!days.isEmpty()&&Integer.parseInt(days)<0)return "POWER STATE ERROR DAYS";
            if(!pending.isEmpty()&&Integer.parseInt(pending)<0)return "POWER STATE ERROR PENDING";
            if(!positions.isEmpty()&&Integer.parseInt(positions)<0)return "POWER STATE ERROR POSITIONS";
        }catch(Exception bad){return "POWER STATE ERROR FORMAT";}
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        p.edit()
            .putString("powerfull_status_"+symbol,status)
            .putString("powerfull_range_pips_"+symbol,range)
            .putString("powerfull_low_"+symbol,low)
            .putString("powerfull_high_"+symbol,high)
            .putString("powerfull_bar_"+symbol,bar)
            .putString("powerfull_samples_"+symbol,samples)
            .putString("powerfull_days_"+symbol,days)
            .putString("powerfull_pending_"+symbol,pending)
            .putString("powerfull_positions_"+symbol,positions)
            .putString("powerfull_detail_"+symbol,detail)
            .putLong("powerfull_state_at_"+symbol,System.currentTimeMillis())
            .apply();
        return "POWER STATE OK";
    }

    private String first(String l){
        if(l==null)return "";
        String[] a=l.split(" ");
        return a.length>1?a[1]:"";
    }

    private String query(String path,String key){
        int q=path.indexOf('?');if(q<0)return "";
        String[] a=path.substring(q+1).split("&");
        for(String x:a){
            int e=x.indexOf('=');
            if(e>0&&key.equals(x.substring(0,e))){
                String value=x.substring(e+1);
                try {
                    return URLDecoder.decode(value,"UTF-8");
                } catch (UnsupportedEncodingException ignored) {
                    return value;
                }
            }
        }
        return "";
    }

    private synchronized String getPending(){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",0);
        String cmd=p.getString("next","");
        // Discard legacy test commands; never execute them on a later reconnect.
        if(cmd.contains("type=TEST") || cmd.contains("side=TEST") || cmd.contains("id=TEST-")){
            p.edit().remove("next").apply();
            AppLog.add(this,"MT5","discarded legacy TEST/ACK queue; LIMIT only");
            return "";
        }
        // A scalping command must not sit in the queue and execute after MT5 reconnects much later.
        String queuedId=field(cmd,"id");
        try{
            long queuedAt=Long.parseLong(queuedId);
            if(System.currentTimeMillis()-queuedAt>15000L){
                p.edit().remove("next")
                    .putString("order_status_id",queuedId)
                    .putString("order_status","EXPIRED")
                    .putString("order_status_detail","Perintah LIMIT kedaluwarsa setelah 15 detik; cek tab Trade dan Experts MT5 sebelum mengirim ulang")
                    .putLong("order_status_at",System.currentTimeMillis()).apply();
                AppLog.add(this,"ORDER","EXPIRED id="+queuedId+" stale LIMIT command not sent to EA");
                return "";
            }
        }catch(Exception ignored){}
        return cmd;
    }

    private synchronized String ack(String id,String result,String detail){
        if(id==null||id.isEmpty())return "ACK ERROR";
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        String current=p.getString("next","");
        String currentId=field(current,"id");
        if(id.equals(currentId)){
            String safeResult=(result==null||result.isEmpty())?"UNKNOWN":result;
            p.edit().remove("next")
                .putString("order_status_id",id)
                .putString("order_status",safeResult)
                .putString("order_status_detail",(detail==null||detail.trim().isEmpty())?"Hasil diterima dari EA MT5":detail)
                .putLong("order_status_at",System.currentTimeMillis()).apply();
            AppLog.add(this,"MT5","ACK id="+id+" result="+safeResult);
            return "ACK OK";
        }
        AppLog.add(this,"MT5","ACK STALE id="+id+" current="+currentId);
        return "ACK STALE";
    }

    private String field(String s,String key){
        if(s==null)return "";
        String p=key+"=";int a=s.indexOf(p);if(a<0)return "";
        a+=p.length();int b=s.indexOf(';',a);if(b<0)b=s.length();
        return s.substring(a,b);
    }

    private void startBackgroundEngine(){
        engine=Executors.newScheduledThreadPool(1);
        engine.scheduleWithFixedDelay(this::backgroundSignalTick,5,15,TimeUnit.SECONDS);
    }

    private void backgroundSignalTick(){
        if(MainActivity.isAppVisible())return;
        long now=System.currentTimeMillis();
        if(!isEaRecentlyOnline(this)){
            logBgStatus("EA_OFFLINE signal notifications paused");
            return;
        }
        if(now<nextBgAttemptAt)return;
        nextBgAttemptAt=now+30000L;
        try{
            String market=getSharedPreferences("rayyan4_indicators",MODE_PRIVATE)
                .getString("market","XAUUSD");
            String symbol=backgroundMt5Symbol(market);
            Candle[] m1=readStoredMt5Bars(symbol,"M1",200);
            Candle[] m5=readStoredMt5Bars(symbol,"M5",200);
            double[] t=readFreshMt5Quote(symbol);
            bgFailureCount=0;
            nextBgAttemptAt=System.currentTimeMillis()+30000L;
            if(m1==null||m5==null||t==null||m1.length<30||m5.length<60){
                logBgStatus("MT5_DATA_STALE_OR_INSUFFICIENT market="+market+" symbol="+symbol+
                    " M1="+(m1==null?0:m1.length)+" M5="+(m5==null?0:m5.length)+" quote="+(t!=null));
                return;
            }
            logBgStatus("MT5_FEED_OK market="+market+" symbol="+symbol+" M1="+m1.length+" M5="+m5.length);
            SharedPreferences strategyPrefs=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
            boolean standard=strategyPrefs.getBoolean("rayyan_strategy_enabled_STANDAR_"+symbol,false);
            boolean excellent=strategyPrefs.getBoolean("rayyan_strategy_enabled_EXCELLENT_"+symbol,false);
            boolean powerful=strategyPrefs.getBoolean("powerfull_enabled_"+symbol,false);
            int activeCount=(standard?1:0)+(excellent?1:0)+(powerful?1:0);
            if(activeCount!=1 || powerful){
                logBgStatus("BACKGROUND_SIGNAL_PAUSED strategy="+(activeCount>1?"CONFLICT":(powerful?"POWERFULL_EA_OWNED":"OFF"))+" symbol="+symbol);
                return;
            }
            SignalResult s=excellent
                ?ExcellentSignalEngine.evaluate(m1,m5,t[0],t[1],t[0],t[1])
                :SignalEngine.evaluate(m1,m5,t[0],t[1],t[0],t[1]);
            if(s==null){logBgStatus("SIGNAL_RESULT_NULL");return;}
            String side=s.side;
            if(!"BUY".equals(side)&&!"SELL".equals(side))logBgStatus("SIGNAL_WAIT side="+side+" confidence="+s.confidence);
            SharedPreferences p=getSharedPreferences("rayyan4_bg_signal",MODE_PRIVATE);
            String last=p.getString("side_"+symbol,"");
            long lastAlertAt=p.getLong("last_alert_at_"+symbol,0L);
            long alertNow=System.currentTimeMillis();
            if(("BUY".equals(side)||"SELL".equals(side))&&!side.equals(last)&&alertNow-lastAlertAt>=180000L){
                p.edit().putString("side_"+symbol,side).putLong("last_alert_at_"+symbol,alertNow).apply();
                String msg=market+" SCALPING\n\n"+side+
                    "\nEntry: "+f(s.entry)+"\nSL: "+f(s.sl)+"\nTP1: "+f(s.tp1)+"\nTP2: "+f(s.tp2)+
                    "\nTF: M1\nBias: M5 "+s.m5Bias+"\nSetup: "+s.setup+"\nConfidence: "+s.confidence+"/5";
                AppLog.add(this,"BACKGROUND SIGNAL",market+" "+side+" "+f(s.entry)+" score="+s.score);
                HistoryStore.add(this,"SIGNAL",side,s.entry,s.sl,s.tp1,s.tp2,s.confidence);
                String token=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getString("token","");
                String chat=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getString("chat","");
                boolean tg=getSharedPreferences("rayyan4_telegram",MODE_PRIVATE).getBoolean("enabled",false);
                if(tg)TelegramClient.send(token,chat,msg,(ok,detail)->AppLog.add(this,"TELEGRAM",ok?"background sent":"background error"));
                notifyAlert(market+" "+side,"Entry "+f(s.entry)+" | SL "+f(s.sl)+" | TP1 "+f(s.tp1));
            }
        }catch(Exception e){
            bgFailureCount=Math.min(bgFailureCount+1,5);
            long delay=Math.min(300000L,30000L*(1L<<Math.min(bgFailureCount-1,4)));
            nextBgAttemptAt=System.currentTimeMillis()+delay;
            logBgStatus("FEED_ERROR retry="+(delay/1000L)+"s "+e.getClass().getSimpleName()+": "+e.getMessage());
        }
    }

    private String backgroundMt5Symbol(String market){
        if("BTCUSD".equalsIgnoreCase(market))return "BTCUSDm";
        if("ETHUSD".equalsIgnoreCase(market))return "ETHUSDm";
        return "XAUUSDm";
    }

    private Candle[] readStoredMt5Bars(String symbol,String tf,int limit){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        long received=p.getLong("mt5_bars_at_"+symbol+"_"+tf,0L);
        long age=System.currentTimeMillis()-received;
        if(received<=0L||age<0L||age>10000L)return null;
        String raw=p.getString("mt5_bars_"+symbol+"_"+tf,"");
        if(raw.isEmpty())return null;
        ArrayList<Candle> all=new ArrayList<>();
        try{
            for(String row:raw.split("\\|")){
                String[] v=row.split(",");
                if(v.length!=5)continue;
                long t=Long.parseLong(v[0]);
                double o=Double.parseDouble(v[1]),h=Double.parseDouble(v[2]);
                double l=Double.parseDouble(v[3]),c=Double.parseDouble(v[4]);
                if(t>0L&&o>0&&h>=Math.max(o,c)&&l<=Math.min(o,c)&&l>0&&h>=l)
                    all.add(new Candle(t,o,h,l,c));
            }
        }catch(Exception ignored){return null;}
        if(all.size()<2)return null;
        int start=Math.max(0,all.size()-Math.max(2,limit));
        return all.subList(start,all.size()).toArray(new Candle[0]);
    }

    private double[] readFreshMt5Quote(String symbol){
        SharedPreferences p=getSharedPreferences("rayyan4_gateway",MODE_PRIVATE);
        long received=p.getLong("broker_quote_received_ms_"+symbol,0L);
        long tickMs=p.getLong("broker_quote_tick_ms_"+symbol,0L);
        if(!QuoteSafety.isFresh(received,tickMs,System.currentTimeMillis()))return null;
        try{
            double bid=Double.parseDouble(p.getString("broker_quote_bid_"+symbol,"0"));
            double ask=Double.parseDouble(p.getString("broker_quote_ask_"+symbol,"0"));
            return QuoteSafety.valid(bid,ask)?new double[]{bid,ask}:null;
        }catch(Exception ignored){return null;}
    }

    private synchronized void logBgStatus(String status){
        long now=System.currentTimeMillis();
        boolean changed=!status.equals(lastBgStatus);
        boolean heartbeat=now-lastBgHeartbeat>=60000L;
        if(changed||heartbeat){
            AppLog.add(this,"BACKGROUND",status);
            lastBgStatus=status;
            lastBgHeartbeat=now;
        }
    }

    private String f(double v){return String.format(Locale.US,"%.2f",v);}

    private void notifyAlert(String title,String text){
        String id="rayyan4_alerts";
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(id,"Rayyan4 Alerts",NotificationManager.IMPORTANCE_HIGH));
        n.notify((int)(System.currentTimeMillis()&0x7fffffff),
            new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).build());
    }

    private Notification notification(String message){
        String id="rayyan4_gateway";
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(id,"MT5 Gateway / Background Engine",NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Rayyan4 Background Engine")
            .setContentText(message)
            .setOngoing(true).setPriority(Notification.PRIORITY_LOW).build();
    }

    @Override public void onDestroy(){
        running=false;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        if(serverPool!=null)serverPool.shutdownNow();
        if(engine!=null)engine.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i){return null;}
}
