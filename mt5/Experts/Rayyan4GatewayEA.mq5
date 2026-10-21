#property strict
#include <Trade/Trade.mqh>
CTrade trade;
#define RAYYAN_MAGIC 4104407

input string GatewayUrl="http://192.168.0.112:8787";
input string AllowedSymbol="XAUUSDm";
input bool DemoOnly=true;
input int PollSeconds=1;
// After TP1 is reached, close on a confirmed M1 reversal before TP2.
input bool EnableTP2RetraceProtection=true;
input double TP1FallbackRiskMultiple=2.0;


string lastId="";
string trackedOrderId="";
string pendingAckId="";
string pendingAckResult="";
string pendingAckDetail="";
datetime lastDiag=0;
uint quoteDiagAtMs[3];
string quoteDiagState[3];
void manageTP2RetraceProtection();
void updateChartCountdown();
void uploadChartSnapshot();
uint lastScreenshotTick=0;

// Native chart layers are managed by this same EA; no second EA is needed.
input int SRLookbackBars=80;
input int FiboLookbackBars=60;
int hEma50=INVALID_HANDLE, hEma200=INVALID_HANDLE;
bool onEma50=true, onEma200=true, onSR=true, onFibo=true;
string lastIndicatorCommandId="";
datetime lastIndicatorBar=0, lastIndicatorDiag=0;
#define RAYYAN_EMA_DRAW_BARS 220

int OnInit(){
   hEma50=iMA(_Symbol,_Period,50,0,MODE_EMA,PRICE_CLOSE);
   hEma200=iMA(_Symbol,_Period,200,0,MODE_EMA,PRICE_CLOSE);
   if(hEma50==INVALID_HANDLE || hEma200==INVALID_HANDLE)
      Print("RAYYAN4 INDICATOR WARNING EMA handles=",hEma50,",",hEma200);
   ChartSetInteger(0,CHART_AUTOSCROLL,true);
   ChartSetInteger(0,CHART_SHIFT,false);
   ChartSetInteger(0,CHART_SCALE,4);
   applyAll();
   EventSetTimer(MathMax(1,PollSeconds));
   Print("RAYYAN4 START Gateway=",GatewayUrl," Symbol=",AllowedSymbol,
         " trade queue=/next indicator queue=/indicator_next");
   return(INIT_SUCCEEDED);
}
void OnDeinit(const int r){
   EventKillTimer();
   ObjectDelete(0,"RAYYAN_MT5_BAR_TIMER");
   ObjectDelete(0,"RAYYAN8_SR_RESISTANCE");
   ObjectDelete(0,"RAYYAN8_SR_SUPPORT");
   ObjectDelete(0,"RAYYAN8_FIBO");
   deleteEmaSegments("RAYYAN_EMA50_SEG_");
   deleteEmaSegments("RAYYAN_EMA200_SEG_");
   if(hEma50!=INVALID_HANDLE)IndicatorRelease(hEma50);
   if(hEma200!=INVALID_HANDLE)IndicatorRelease(hEma200);
}
void OnTick(){ manageTP2RetraceProtection(); }

void OnTimer(){
   // Risk protection, chart sync and snapshots stay active independently of /next.
   manageTP2RetraceProtection();
   processIndicatorQueue();
   updateChartCountdown();
   uploadChartSnapshot();
   int code=-1;
   string cmd="",transportDetail="";
   ResetLastError();
   bool received=gatewayGet("/next",code,cmd,transportDetail);

   if(!received || code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY HTTP ERROR transport=",received,
               " code=",code," err=",GetLastError()," detail=",transportDetail,
               " gateway=",GatewayUrl);
      }
      return;
   }

   publishOrderState();
   publishMarketQuotes();

   if(StringLen(cmd)<5){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY OK - waiting for command");
      }
      return;
   }

   string id=field(cmd,"id");
   if(id=="")return;
   // A trade is sent once; until ACK is confirmed, retry ACK only, never place it twice.
   if(pendingAckId!=""){
      if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
         lastId=pendingAckId;
         pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
      }else return;
      if(id==lastId)return;
   }
   if(id==lastId)return;

   string type=field(cmd,"type");
   string side=field(cmd,"side");

   // Isolated additive path: cancel only the exact still-pending Rayyan LIMIT
   // identified by both ticket and its unique R4-<original-id> order comment.
   // No new order is sent by this command.
   if(type=="CANCEL"){
      string targetId=field(cmd,"targetId");
      ulong cancelTicket=(ulong)StringToInteger(field(cmd,"ticket"));
      string expectedComment="R4-"+targetId;
      string cancelResult="CANCEL_FAILED";
      string cancelDetail="Pending order not found or identity mismatch; no replacement permitted";
      trackedOrderId=targetId;
      if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
         cancelResult="NOT_DEMO";
         cancelDetail="DemoOnly guard blocked order cancellation outside demo account";
      }else if(targetId!="" && cancelTicket>0 && OrderSelect(cancelTicket)){
         string orderComment=OrderGetString(ORDER_COMMENT);
         ENUM_ORDER_TYPE orderType=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
         long orderMagic=OrderGetInteger(ORDER_MAGIC);
         bool correctSide=(orderType==ORDER_TYPE_BUY_LIMIT || orderType==ORDER_TYPE_SELL_LIMIT)
            && orderMagic==RAYYAN_MAGIC;
         if(orderComment==expectedComment && correctSide){
            ResetLastError();
            bool deleted=trade.OrderDelete(cancelTicket);
            uint cancelRc=trade.ResultRetcode();
            if(deleted && cancelRc==TRADE_RETCODE_DONE){
               cancelResult="CANCELLED";
               cancelDetail="Exact pending LIMIT deleted ticket="+(string)cancelTicket;
               Print("RAYYAN4 SMART CANCEL OK id=",targetId," ticket=",cancelTicket);
            }else{
               cancelDetail="OrderDelete failed retcode="+IntegerToString((int)cancelRc)+
                  " "+trade.ResultRetcodeDescription()+" lastError="+IntegerToString(GetLastError());
            }
         }else{
            cancelDetail="Order identity rejected comment="+orderComment+
               " type="+IntegerToString((int)orderType);
         }
      }
      pendingAckId=id; pendingAckResult=cancelResult; pendingAckDetail=cancelDetail;
      if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
         lastId=pendingAckId;
         pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
      }
      Print("RAYYAN4 id=",id," type=CANCEL target=",targetId,
            " result=",cancelResult," detail=",cancelDetail);
      return;
   }

   if(side=="TEST") { Print("RAYYAN4 REJECT legacy TEST command; use BUY/SELL market or LIMIT side"); return; }
   double vol=StringToDouble(field(cmd,"volume"));
   double entry=StringToDouble(field(cmd,"entry"));
   double sl=StringToDouble(field(cmd,"sl"));
   double tp=StringToDouble(field(cmd,"tp"));
   double tp1=StringToDouble(field(cmd,"tp1"));
   double maxSpread=StringToDouble(field(cmd,"maxSpread"));
   long refTickMs=(long)StringToInteger(field(cmd,"refTickMs"));
   long localNowMs=(long)TimeLocal()*1000;
   long quoteAgeMs=localNowMs-refTickMs;

   bool ok=false;
   string resultText="REJECT";
   string detail="";
   string requestedSym=field(cmd,"symbol");
   string sym=(requestedSym=="" ? AllowedSymbol : requestedSym);
   bool supported=(sym=="XAUUSDm" || sym=="BTCUSDm" || sym=="ETHUSDm");
   Print("RAYYAN4 COMMAND RECEIVED id=",id," type=",type," side=",side,
         " symbol=",sym," supported=",supported,
         " volume=",DoubleToString(vol,2),
         " entry=",DoubleToString(entry,8)," sl=",DoubleToString(sl,8),
         " tp=",DoubleToString(tp,8)," tp1=",DoubleToString(tp1,8),
         " server=",AccountInfoString(ACCOUNT_SERVER),
         " login=",AccountInfoInteger(ACCOUNT_LOGIN));
   if(supported) SymbolSelect(sym,true);

   MqlTick tick;
   bool haveTick=supported && SymbolInfoTick(sym,tick);
   int digits=(int)SymbolInfoInteger(sym,SYMBOL_DIGITS);
   double point=SymbolInfoDouble(sym,SYMBOL_POINT);
   double minVol=SymbolInfoDouble(sym,SYMBOL_VOLUME_MIN);
   double maxVol=SymbolInfoDouble(sym,SYMBOL_VOLUME_MAX);
   double stepVol=SymbolInfoDouble(sym,SYMBOL_VOLUME_STEP);
   long stopsLevel=SymbolInfoInteger(sym,SYMBOL_TRADE_STOPS_LEVEL);

   // APK chart is sourced from Biquote while orders are validated against the
   // broker quote in MT5. Map the signal levels to the MT5 quote reference
   // captured when the command was queued, preserving the signal's risk distances.
   double refBid=StringToDouble(field(cmd,"refBid"));
   double refAsk=StringToDouble(field(cmd,"refAsk"));
   if(haveTick && refBid>0 && refAsk>=refBid){
      double shift=0.0;
      if(side=="BUY_LIMIT" || side=="BUY") shift=tick.ask-refAsk;
      else if(side=="SELL_LIMIT" || side=="SELL") shift=tick.bid-refBid;
      if(side=="BUY_LIMIT" || side=="SELL_LIMIT" || side=="BUY" || side=="SELL"){
         entry+=shift; sl+=shift; tp+=shift;
         if(tp1>0.0) tp1+=shift;
         if(side=="BUY") entry=tick.ask;
         else if(side=="SELL") entry=tick.bid;
         Print("RAYYAN4 PRICE MAP side=",side,
               " refBid=",DoubleToString(refBid, digits),
               " refAsk=",DoubleToString(refAsk, digits),
               " brokerBid=",DoubleToString(tick.bid, digits),
               " brokerAsk=",DoubleToString(tick.ask, digits),
               " shift=",DoubleToString(shift, digits));
      }
   }

   if(stepVol>0) vol=MathFloor(vol/stepVol+1e-8)*stepVol;
   vol=NormalizeDouble(vol,2);
   entry=NormalizeDouble(entry,digits);
   sl=NormalizeDouble(sl,digits);
   tp=NormalizeDouble(tp,digits);
   if(tp1>0.0) tp1=NormalizeDouble(tp1,digits);

   if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
      resultText="NOT_DEMO";
   }else if(!supported){
      resultText="UNSUPPORTED_SYMBOL"; detail=sym;
   }else if(!haveTick){
      resultText="NO_TICK";
   }else if(tick.bid<=0.0 || tick.ask<tick.bid){
      resultText="BAD_TICK";
      detail=StringFormat("bid=%.8f ask=%.8f",tick.bid,tick.ask);
   }else if(type=="MARKET" && (refTickMs<=0 || quoteAgeMs < -5000 || quoteAgeMs > 15000)){
      resultText="QUOTE_EXPIRED";
      detail=StringFormat("refTickMs=%I64d localNowMs=%I64d ageMs=%I64d; market quote age must be within -5s..15s",
                          refTickMs,localNowMs,quoteAgeMs);
   }else if(type=="MARKET" && (maxSpread<=0.0 || tick.ask-tick.bid>maxSpread)){
      resultText="SPREAD_FILTER";
      detail=StringFormat("spread=%.8f maximum=%.8f bid=%.8f ask=%.8f",
                          tick.ask-tick.bid,maxSpread,tick.bid,tick.ask);
   }else if(vol<minVol || vol>maxVol){
      resultText="BAD_VOLUME";
      detail=StringFormat("vol=%.2f min=%.2f max=%.2f",vol,minVol,maxVol);
   }else if(entry<=0 || sl<=0 || tp<=0){
      resultText="BAD_VALUES";
   }else if(side=="BUY_LIMIT" && !(entry<tick.ask)){
      resultText="BAD_ENTRY";
      detail=StringFormat("BUY_LIMIT entry=%.5f ask=%.5f",entry,tick.ask);
   }else if(side=="SELL_LIMIT" && !(entry>tick.bid)){
      resultText="BAD_ENTRY";
      detail=StringFormat("SELL_LIMIT entry=%.5f bid=%.5f",entry,tick.bid);
   }else if((side=="BUY_LIMIT" || side=="BUY") && !(sl<entry && tp>entry)){
      resultText="BAD_STOPS";
      detail="BUY requires SL<Entry<TP";
   }else if((side=="SELL_LIMIT" || side=="SELL") && !(tp<entry && sl>entry)){
      resultText="BAD_STOPS";
      detail="SELL requires TP<Entry<SL";
   }else if(type=="MARKET" && tp1>0.0
            && ((side=="BUY" && tp1<=entry) || (side=="SELL" && tp1>=entry))){
      resultText="BAD_TP1";
      detail=StringFormat("market TP1 invalid side=%s entry=%.8f tp1=%.8f",side,entry,tp1);
   }else if(point<=0){
      resultText="BAD_SYMBOL";
   }else if(stopsLevel>0 && MathAbs(entry-sl)/point<stopsLevel){
      resultText="STOP_DISTANCE";
      detail=StringFormat("entry-sl=%.0f min=%d points",MathAbs(entry-sl)/point,stopsLevel);
   }else if(type=="MARKET" && stopsLevel>0 && MathAbs(tp-entry)/point<stopsLevel){
      resultText="TP_DISTANCE";
      detail=StringFormat("tp-entry=%.0f min=%d points",MathAbs(tp-entry)/point,stopsLevel);
   }else{
      bool terminalConnected=(bool)TerminalInfoInteger(TERMINAL_CONNECTED);
      bool terminalTradeAllowed=(bool)TerminalInfoInteger(TERMINAL_TRADE_ALLOWED);
      bool eaTradeAllowed=(bool)MQLInfoInteger(MQL_TRADE_ALLOWED);
      bool accountTradeAllowed=(bool)AccountInfoInteger(ACCOUNT_TRADE_ALLOWED);
      bool accountExpertAllowed=(bool)AccountInfoInteger(ACCOUNT_TRADE_EXPERT);
      string brokerServer=AccountInfoString(ACCOUNT_SERVER);
      long accountLogin=AccountInfoInteger(ACCOUNT_LOGIN);
      Print("RAYYAN4 BROKER CHECK connected=",terminalConnected,
            " terminalTrade=",terminalTradeAllowed," eaTrade=",eaTradeAllowed,
            " accountTrade=",accountTradeAllowed," accountExpert=",accountExpertAllowed,
            " server=",brokerServer," login=",accountLogin);

      if(!terminalConnected){
         resultText="NO_BROKER_CONNECTION";
         detail="TERMINAL_CONNECTED=false server="+brokerServer+" login="+IntegerToString((int)accountLogin);
      }else if(!terminalTradeAllowed || !eaTradeAllowed || !accountTradeAllowed || !accountExpertAllowed){
         resultText="TRADE_NOT_ALLOWED";
         detail=StringFormat("terminal=%d ea=%d account=%d expert=%d server=%s",
            (int)terminalTradeAllowed,(int)eaTradeAllowed,(int)accountTradeAllowed,
            (int)accountExpertAllowed,brokerServer);
      }else{
         // Snapshot the connection immediately before the request. This is a point-in-time
         // indicator only; the trade server can disconnect between this check and OrderSend.
         Print("RAYYAN4 SEND PRECHECK time=",TimeToString(TimeCurrent(),TIME_DATE|TIME_SECONDS),
               " connected=",(bool)TerminalInfoInteger(TERMINAL_CONNECTED),
               " terminalTrade=",(bool)TerminalInfoInteger(TERMINAL_TRADE_ALLOWED),
               " eaTrade=",(bool)MQLInfoInteger(MQL_TRADE_ALLOWED),
               " accountTrade=",(bool)AccountInfoInteger(ACCOUNT_TRADE_ALLOWED),
               " accountExpert=",(bool)AccountInfoInteger(ACCOUNT_TRADE_EXPERT),
               " server=",brokerServer," login=",accountLogin,
               " symbol=",sym," side=",side);

         trade.SetAsyncMode(false);
         trade.SetExpertMagicNumber(RAYYAN_MAGIC);
         trade.SetTypeFillingBySymbol(sym);
         ResetLastError();
         if(side=="BUY_LIMIT") ok=trade.BuyLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"R4-"+id);
         else if(side=="SELL_LIMIT") ok=trade.SellLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"R4-"+id);
         else if(side=="BUY") ok=trade.Buy(vol,sym,0.0,sl,tp,"R4-"+id);
         else if(side=="SELL") ok=trade.Sell(vol,sym,0.0,sl,tp,"R4-"+id);
         else { resultText="BAD_SIDE"; detail=side; }

         uint rc=trade.ResultRetcode();
         int sendLastError=GetLastError();
         bool connectedAfter=(bool)TerminalInfoInteger(TERMINAL_CONNECTED);
         bool brokerAccepted=(rc==TRADE_RETCODE_PLACED || rc==TRADE_RETCODE_DONE);
         string rcDescription=trade.ResultRetcodeDescription();
         if(side=="BUY_LIMIT" || side=="SELL_LIMIT" || side=="BUY" || side=="SELL"){
            if(ok && brokerAccepted){
               resultText="EXECUTED";
            }else if(rc==TRADE_RETCODE_CONNECTION){
               resultText="NO_BROKER_CONNECTION";
               detail="MT5 returned TRADE_RETCODE_CONNECTION(10031); terminalConnectedBefore="+
                      IntegerToString((int)terminalConnected)+" connectedAfter="+
                      IntegerToString((int)connectedAfter)+" server="+brokerServer+
                      " login="+IntegerToString((int)accountLogin)+
                      ". No automatic resubmission; verify MT5 Trade/Journal before retry. retcode=10031";
            }else{
               resultText="FAILED";
               detail=rcDescription+" retcode="+IntegerToString((int)rc)+
                      " lastError="+IntegerToString(sendLastError)+
                      " order="+(string)trade.ResultOrder();
            }
         }
         Print("RAYYAN4 TRADE CHECK time=",TimeToString(TimeCurrent(),TIME_DATE|TIME_SECONDS),
               " symbol=",sym," side=",side," vol=",DoubleToString(vol,2),
               " entry=",DoubleToString(entry,digits)," bid=",DoubleToString(tick.bid,digits),
               " ask=",DoubleToString(tick.ask,digits)," sl=",DoubleToString(sl,digits),
               " tp=",DoubleToString(tp,digits)," retcode=",rc," desc=",rcDescription,
               " ok=",ok," lastError=",sendLastError," order=",trade.ResultOrder(),
               " connectedBefore=",terminalConnected," connectedAfter=",connectedAfter,
               " server=",brokerServer);
      }
   }

   if(resultText=="EXECUTED"){
      trackedOrderId=id;
      if(tp1>0.0 && (((side=="BUY_LIMIT" || side=="BUY") && entry<tp1 && tp1<tp)
                    ||((side=="SELL_LIMIT" || side=="SELL") && tp<tp1 && tp1<entry))){
         GlobalVariableSet("R8_TP1_"+id,tp1);
         Print("RAYYAN4 TP1 protection target saved id=",id," tp1=",DoubleToString(tp1,digits));
      }
   }
   Print("RAYYAN4 id=",id," side=",side," result=",resultText,
         " detail=",detail," retcode=",trade.ResultRetcode());
   pendingAckId=id; pendingAckResult=resultText; pendingAckDetail=detail;
   if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
      lastId=pendingAckId;
      pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
   }
}



// Native MT5 candle countdown displayed in the captured chart and updated each timer pass.
void updateChartCountdown(){
   datetime bar=iTime(_Symbol,_Period,0);
   int periodSeconds=PeriodSeconds(_Period);
   if(bar<=0 || periodSeconds<=0) return;
   int remain=(int)MathMax(0,periodSeconds-(TimeCurrent()-bar));
   string label=StringFormat("%s  %02d:%02d",EnumToString(_Period),remain/60,remain%60);
   const string name="RAYYAN_MT5_BAR_TIMER";
   if(ObjectFind(0,name)<0){
      if(!ObjectCreate(0,name,OBJ_LABEL,0,0,0)){
         Print("RAYYAN TIMER CREATE FAILED err=",GetLastError()); return;
      }
      ObjectSetInteger(0,name,OBJPROP_CORNER,CORNER_RIGHT_UPPER);
      ObjectSetInteger(0,name,OBJPROP_ANCHOR,ANCHOR_RIGHT_UPPER);
      ObjectSetInteger(0,name,OBJPROP_XDISTANCE,12);
      ObjectSetInteger(0,name,OBJPROP_YDISTANCE,20);
      ObjectSetInteger(0,name,OBJPROP_COLOR,clrWhite);
      ObjectSetInteger(0,name,OBJPROP_FONTSIZE,10);
      ObjectSetString(0,name,OBJPROP_FONT,"Arial");
      ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
      ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
   }
   ObjectSetString(0,name,OBJPROP_TEXT,label);
   ChartRedraw(0);
}

void uploadChartSnapshot(){
   if(GetTickCount()-lastScreenshotTick<900) return;
   lastScreenshotTick=GetTickCount();
   string filename="rayyan_mt5_chart.png";
   ResetLastError();
   if(!ChartScreenShot(0,filename,1000,500,ALIGN_RIGHT)){
      Print("RAYYAN CHART SCREENSHOT FAILED err=",GetLastError()); return;
   }
   Sleep(100);
   int fh=FileOpen(filename,FILE_READ|FILE_BIN|FILE_SHARE_READ);
   if(fh==INVALID_HANDLE){Print("RAYYAN CHART FILE OPEN FAILED err=",GetLastError());return;}
   ulong size=FileSize(fh);
   if(size<8||size>2000000){FileClose(fh);Print("RAYYAN CHART IMAGE SIZE INVALID=",size);return;}
   char data[]; ArrayResize(data,(int)size);
   uint read=FileReadArray(fh,data,0,(uint)size);
   FileClose(fh);
   if(read!=size){Print("RAYYAN CHART READ MISMATCH ",read,"/",size);return;}
   char result[]; string responseHeaders="";
   string headers="Content-Type: image/png\r\n";
   ResetLastError();
   int code=WebRequest("POST",GatewayUrl+"/chart/upload",headers,2500,data,result,responseHeaders);
   if(code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN CHART PUBLISH FAILED HTTP=",code," err=",GetLastError());
      }
   }else{
      Print("RAYYAN MT5 CHART PUBLISHED symbol=",_Symbol," tf=",EnumToString(_Period),
            " bid=",DoubleToString(SymbolInfoDouble(_Symbol,SYMBOL_BID),_Digits),
            " ask=",DoubleToString(SymbolInfoDouble(_Symbol,SYMBOL_ASK),_Digits));
   }
}

// TP2-approach retrace protection for positions sent by this EA only.
void manageTP2RetraceProtection(){
   if(!EnableTP2RetraceProtection) return;
   if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO) return;
   if(!TerminalInfoInteger(TERMINAL_CONNECTED)
      || !TerminalInfoInteger(TERMINAL_TRADE_ALLOWED)
      || !MQLInfoInteger(MQL_TRADE_ALLOWED)
      || !AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)
      || !AccountInfoInteger(ACCOUNT_TRADE_EXPERT)) return;

   double fallbackR=MathMax(0.5,MathMin(5.0,TP1FallbackRiskMultiple));
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0 || !PositionSelectByTicket(ticket)) continue;
      if(PositionGetInteger(POSITION_MAGIC)!=RAYYAN_MAGIC) continue;

      string symbol=PositionGetString(POSITION_SYMBOL);
      string comment=PositionGetString(POSITION_COMMENT);
      string orderId=(StringFind(comment,"R4-")==0 ? StringSubstr(comment,3) : trackedOrderId);
      long positionId=PositionGetInteger(POSITION_IDENTIFIER);
      string armedKey="R8_TP2A_"+(string)positionId;
      string tp1Key=(orderId!="" ? "R8_TP1_"+orderId : "");
      ENUM_POSITION_TYPE posType=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN);
      double sl=PositionGetDouble(POSITION_SL);
      double tp2=PositionGetDouble(POSITION_TP);
      double risk=MathAbs(entry-sl);
      if(entry<=0.0 || sl<=0.0 || tp2<=0.0 || risk<=0.0) continue;

      double tp1=0.0;
      if(tp1Key!="" && GlobalVariableCheck(tp1Key))
         tp1=GlobalVariableGet(tp1Key);
      bool tp1Valid=(posType==POSITION_TYPE_BUY
         ? (sl<entry && entry<tp1 && tp1<tp2)
         : (tp2<tp1 && tp1<entry && entry<sl));
      // Recompute TP1 when actual fill/slippage makes the submitted level inconsistent.
      if(!tp1Valid){
         tp1=(posType==POSITION_TYPE_BUY ? entry+risk*fallbackR : entry-risk*fallbackR);
         tp1=NormalizeDouble(tp1,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
      }
      if(posType==POSITION_TYPE_BUY && !(sl<entry && entry<tp1 && tp1<tp2)) continue;
      if(posType==POSITION_TYPE_SELL && !(tp2<tp1 && tp1<entry && entry<sl)) continue;

      MqlTick tick;
      if(!SymbolInfoTick(symbol,tick) || tick.bid<=0.0 || tick.ask<tick.bid) continue;
      // Arm as soon as the executable side of the quote reaches TP1.
      // After that, a closed M1 candle must confirm a reversal before TP2.
      bool armed=GlobalVariableCheck(armedKey) && GlobalVariableGet(armedKey)>0.0;
      bool reachedTP1=(posType==POSITION_TYPE_BUY ? tick.bid>=tp1 : tick.ask<=tp1);
      if(!armed && reachedTP1){
         GlobalVariableSet(armedKey,(double)TimeCurrent());
         armed=true;
         Print("RAYYAN4 TP1 PROFIT PROTECTION ARMED symbol=",symbol," side=",
               (posType==POSITION_TYPE_BUY?"BUY":"SELL"),
               " entry=",DoubleToString(entry,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
               " tp1=",DoubleToString(tp1,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
               " tp2=",DoubleToString(tp2,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)));
      }
      if(!armed) continue;

      datetime closedBarTime=iTime(symbol,PERIOD_M1,1);
      if(closedBarTime<=0) continue;
      // Ignore candles that closed before TP1 was reached; only react to a new reversal.
      double armedAt=GlobalVariableCheck(armedKey)?GlobalVariableGet(armedKey):0.0;
      if(armedAt<=0.0 || (double)closedBarTime<=armedAt) continue;
      string candleKey="R8_TP2C_"+(string)positionId;
      double lastCheckedBar=GlobalVariableCheck(candleKey)?GlobalVariableGet(candleKey):0.0;
      if((double)closedBarTime<=lastCheckedBar) continue;
      double cOpen=iOpen(symbol,PERIOD_M1,1);
      double cClose=iClose(symbol,PERIOD_M1,1);
      double prevLow=iLow(symbol,PERIOD_M1,2);
      double prevHigh=iHigh(symbol,PERIOD_M1,2);
      if(cOpen<=0.0 || cClose<=0.0 || prevLow<=0.0 || prevHigh<=0.0) continue;

      // BUY: bearish close breaks prior M1 low; SELL: bullish close breaks prior M1 high.
      bool reversal=(posType==POSITION_TYPE_BUY
         ? (cClose<cOpen && cClose<prevLow)
         : (cClose>cOpen && cClose>prevHigh));
      GlobalVariableSet(candleKey,(double)closedBarTime);
      if(!reversal) continue;

      trade.SetAsyncMode(false);
      trade.SetExpertMagicNumber(RAYYAN_MAGIC);
      trade.SetTypeFillingBySymbol(symbol);
      ResetLastError();
      bool closed=trade.PositionClose(ticket);
      uint rc=trade.ResultRetcode();
      int lastError=GetLastError();
      if(closed && (rc==TRADE_RETCODE_DONE || rc==TRADE_RETCODE_DONE_PARTIAL)){
         Print("RAYYAN4 TP2 RETRACE EXIT symbol=",symbol," side=",
               (posType==POSITION_TYPE_BUY?"BUY":"SELL"),
               " reason=TP1 reached then confirmed M1 reversal before TP2 ticket=",ticket,
               " tp1=",DoubleToString(tp1,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
               " bid=",DoubleToString(tick.bid,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
               " ask=",DoubleToString(tick.ask,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
               " retcode=",rc);
         if(!PositionSelectByTicket(ticket)){
            GlobalVariableDel(armedKey);
            string candleKeyCleanup="R8_TP2C_"+(string)positionId;
            if(GlobalVariableCheck(candleKeyCleanup)) GlobalVariableDel(candleKeyCleanup);
            if(tp1Key!="" && GlobalVariableCheck(tp1Key)) GlobalVariableDel(tp1Key);
         }
      }else{
         string failKey="R8_TP2F_"+(string)positionId;
         double lastLog=GlobalVariableCheck(failKey)?GlobalVariableGet(failKey):0.0;
         if((double)TimeCurrent()-lastLog>=10.0){
            GlobalVariableSet(failKey,(double)TimeCurrent());
            Print("RAYYAN4 TP2 RETRACE EXIT FAILED symbol=",symbol," ticket=",ticket,
                  " retcode=",rc," desc=",trade.ResultRetcodeDescription()," lastError=",lastError,
                  " • broker SL/TP remain active");
         }
      }
   }
}


// Publish a read-only snapshot of Rayyan-managed order lifecycle. This is
// separate from /next and /ack; it never changes trade state.
void publishOrderState(){
   string managedId=trackedOrderId;
   string state="NONE", ticketText="", symbol="", side="", priceText="0";

   // Positions dominate pending state: if a LIMIT has filled, AUTO ENTRY must
   // stay locked until that position is gone. This is deliberately conservative.
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0) continue;
      string comment=PositionGetString(POSITION_COMMENT);
      long positionMagic=PositionGetInteger(POSITION_MAGIC);
      bool tagged=StringFind(comment,"R4-")==0;
      if(!tagged && positionMagic!=RAYYAN_MAGIC) continue;
      string oid=tagged ? StringSubstr(comment,3)
                        : (managedId!="" ? managedId : "UNKNOWN_POSITION");
      if(managedId!="" && oid!=managedId) continue;
      managedId=oid; trackedOrderId=oid;
      state="POSITION"; ticketText=(string)ticket;
      symbol=PositionGetString(POSITION_SYMBOL);
      ENUM_POSITION_TYPE pt=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      side=(pt==POSITION_TYPE_BUY ? "BUY" : "SELL");
      priceText=DoubleToString(PositionGetDouble(POSITION_PRICE_OPEN),
                               (int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
      break;
   }

   if(state!="POSITION"){
      for(int i=OrdersTotal()-1;i>=0;i--){
         ulong ticket=OrderGetTicket(i);
         if(ticket==0) continue;
         string comment=OrderGetString(ORDER_COMMENT);
         long orderMagic=OrderGetInteger(ORDER_MAGIC);
         bool tagged=StringFind(comment,"R4-")==0;
         if(!tagged && orderMagic!=RAYYAN_MAGIC) continue;
         string oid=tagged ? StringSubstr(comment,3)
                           : (managedId!="" ? managedId : "UNKNOWN_PENDING");
         if(managedId!="" && oid!=managedId) continue;
         ENUM_ORDER_TYPE ot=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
         if(ot!=ORDER_TYPE_BUY_LIMIT && ot!=ORDER_TYPE_SELL_LIMIT) continue;
         managedId=oid; trackedOrderId=oid;
         state="PENDING"; ticketText=(string)ticket;
         symbol=OrderGetString(ORDER_SYMBOL);
         side=(ot==ORDER_TYPE_BUY_LIMIT ? "BUY_LIMIT" : "SELL_LIMIT");
         priceText=DoubleToString(OrderGetDouble(ORDER_PRICE_OPEN),
                                  (int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
         break;
      }
   }

   if(managedId=="") managedId="NONE";
   string path="/state?id="+urlEncode(managedId)+
      "&state="+urlEncode(state)+
      "&ticket="+urlEncode(ticketText)+
      "&symbol="+urlEncode(symbol)+
      "&side="+urlEncode(side)+
      "&price="+urlEncode(priceText);
   int code=-1;
   string body="",diagnostic="";
   if(!gatewayGet(path,code,body,diagnostic) || code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 STATE HTTP ERROR code=",code,
               " err=",GetLastError()," detail=",diagnostic);
      }
   }
}

void quoteDiagnostic(int index,string symbol,string state,string detail){
   uint nowMs=GetTickCount();
   if(state!=quoteDiagState[index] || (uint)(nowMs-quoteDiagAtMs[index])>=30000){
      quoteDiagAtMs[index]=nowMs;
      quoteDiagState[index]=state;
      Print("RAYYAN4 QUOTE DIAG symbol=",symbol," state=",state," ",detail);
   }
}

// /next heartbeat is separate from quote freshness. Log silent symbol/tick failures.
void publishMarketQuotes(){
   string symbols[3]={"XAUUSDm","BTCUSDm","ETHUSDm"};
   for(int i=0;i<ArraySize(symbols);i++){
      string sym=symbols[i];
      if(!SymbolSelect(sym,true)){
         quoteDiagnostic(i,sym,"SYMBOL_SELECT_FAILED","check exact broker symbol name and Market Watch");
         continue;
      }
      MqlTick tick;
      if(!SymbolInfoTick(sym,tick)){
         quoteDiagnostic(i,sym,"NO_TICK","SymbolInfoTick returned false; check symbol subscription and market session");
         continue;
      }
      if(tick.bid<=0||tick.ask<tick.bid||tick.time_msc<=0){
         quoteDiagnostic(i,sym,"INVALID_TICK",
            "bid="+DoubleToString(tick.bid,8)+" ask="+DoubleToString(tick.ask,8)+
            " tickms="+(string)tick.time_msc);
         continue;
      }
      int digits=(int)SymbolInfoInteger(sym,SYMBOL_DIGITS);
      double point=SymbolInfoDouble(sym,SYMBOL_POINT);
      if(point<=0){
         quoteDiagnostic(i,sym,"INVALID_POINT","SYMBOL_POINT="+DoubleToString(point,10));
         continue;
      }
      string path="/quote?symbol="+urlEncode(sym)+
         "&bid="+urlEncode(DoubleToString(tick.bid,digits))+
         "&ask="+urlEncode(DoubleToString(tick.ask,digits))+
         "&point="+urlEncode(DoubleToString(point,10))+
         "&digits="+IntegerToString(digits)+
         "&tickms="+(string)tick.time_msc;
      int code=-1;
      string body="",diagnostic="";
      bool sent=gatewayGet(path,code,body,diagnostic);
      if(!sent || code!=200 || body!="QUOTE OK"){
         quoteDiagnostic(i,sym,"PUBLISH_FAILED",
            "transport="+(string)sent+" http="+IntegerToString(code)+
            " body="+body+" err="+IntegerToString(GetLastError())+" detail="+diagnostic);
      }else{
         quoteDiagnostic(i,sym,"QUOTE_PUBLISHED",
            "bid="+DoubleToString(tick.bid,digits)+" ask="+DoubleToString(tick.ask,digits)+
            " digits="+IntegerToString(digits)+" point="+DoubleToString(point,10)+
            " tickms="+(string)tick.time_msc);
      }
   }
}

// Local LAN bridge uses WebRequest (not MQL5 TCP sockets).
// Add the exact GatewayUrl base to MT5 Tools > Options > Expert Advisors > Allow WebRequest.
bool gatewayGet(string path,int &httpCode,string &body,string &diagnostic){
   httpCode=-1; body=""; diagnostic="";
   string base=GatewayUrl;
   while(StringLen(base)>0 && StringSubstr(base,StringLen(base)-1,1)=="/")
      base=StringSubstr(base,0,StringLen(base)-1);
   if(StringFind(base,"http://")!=0 && StringFind(base,"https://")!=0){
      diagnostic="GatewayUrl must start with http:// or https://";
      return false;
   }

   // Use WebRequest rather than SocketConnect: MT5 may reject socket calls
   // with error 4014. The base URL must be added to Tools > Options >
   // Expert Advisors > Allow WebRequest for listed URL.
   string url=base+path;
   string responseHeaders="";
   char requestData[];
   char responseData[];
   // Use WebRequest's cookie/referer overload with an explicit zero-length GET body.
   // This is more compatible with MetaTrader builds that reject an empty array in
   // the headers overload with internal error 1001 / runtime error 4006.
   ResetLastError();
   int status=WebRequest("GET",url,"","",2500,requestData,0,responseData,responseHeaders);
   int err=GetLastError();
   if(status==-1 || status==1001){
      diagnostic="WebRequest has no HTTP connection status="+IntegerToString(status)+
                 " err="+IntegerToString(err)+"; allow URL "+base+
                 " in MT5 Expert Advisors options and check Winlator LAN reachability";
      return false;
   }
   httpCode=status;
   body=CharArrayToString(responseData,0,ArraySize(responseData),CP_UTF8);
   return (status>=100 && status<=599);
}

int headerContentLength(string headers){
   string lines[];
   int count=StringSplit(headers,'\n',lines);
   for(int i=0;i<count;i++){
      string line=lines[i];
      StringReplace(line,"\r","");
      if(StringFind(line,"Content-Length:")==0)
         return (int)StringToInteger(StringSubstr(line,StringLen("Content-Length:")));
   }
   return -1;
}

bool sendAck(string id,string resultText,string detail){
   string path="/ack?id="+urlEncode(id)+"&result="+urlEncode(resultText)+"&detail="+urlEncode(detail);
   int code=-1;
   string body="",transportDetail="";
   ResetLastError();
   bool received=gatewayGet(path,code,body,transportDetail);
   if(received && code==200 && (StringFind(body,"ACK OK")>=0 || StringFind(body,"ACK STALE")>=0)) return true;
   if(TimeCurrent()-lastDiag>=10){
      lastDiag=TimeCurrent();
      Print("RAYYAN4 ACK HTTP RETRY id=",id," transport=",received," code=",code,
            " err=",GetLastError()," detail=",transportDetail," response=",body);
   }
   return false;
}

string field(string s,string key){
   string p=key+"=";
   int a=StringFind(s,p);
   if(a<0)return "";
   a+=StringLen(p);
   int b=StringFind(s,";",a);
   if(b<0)b=StringLen(s);
   return StringSubstr(s,a,b-a);
}

string urlEncode(string s){
   string out="";
   for(int i=0;i<StringLen(s);i++){
      ushort c=StringGetCharacter(s,i);
      if((c>='0'&&c<='9')||(c>='A'&&c<='Z')||(c>='a'&&c<='z')||c=='-'||c=='_'||c=='.') out+=ShortToString((short)c);
      else if(c==' ') out+="+";
      else { out+="%"; out+=StringFormat("%02X",c); }
   }
   return out;
}


// The indicator queue is independent: it never reads or clears trade /next.
bool indicatorGet(string path,string &out){
   int code=-1;
   string diagnostic="";
   bool ok=gatewayGet(path,code,out,diagnostic);
   if(!ok || code!=200){
      if(TimeCurrent()-lastIndicatorDiag>=30){
         lastIndicatorDiag=TimeCurrent();
         Print("RAYYAN4 INDICATOR HTTP ERROR code=",code,
               " detail=",diagnostic," path=",path);
      }
      return false;
   }
   return true;
}

void processIndicatorQueue(){
   string cmd="";
   if(indicatorGet("/indicator_next",cmd) && StringLen(cmd)>5){
      string id=field(cmd,"id");
      if(id!="" && id!=lastIndicatorCommandId){
         string targetSymbol=field(cmd,"symbol");
         ENUM_TIMEFRAMES targetPeriod=parsePeriod(field(cmd,"tf"));
         if(targetSymbol!="" && targetPeriod!=PERIOD_CURRENT &&
            (ChartSymbol(0)!=targetSymbol || ChartPeriod(0)!=targetPeriod)){
            ResetLastError();
            if(SymbolSelect(targetSymbol,true) &&
               ChartSetSymbolPeriod(0,targetSymbol,targetPeriod)){
               Print("RAYYAN4 chart sync requested ",targetSymbol," ",field(cmd,"tf"));
               return; // leave command pending until the chart change is active
            }
            Print("RAYYAN4 chart sync failed target=",targetSymbol,
                  " tf=",field(cmd,"tf")," err=",GetLastError());
            return; // never ACK a chart state that was not applied
         }

         onEma50=(field(cmd,"ema50")=="1");
         onEma200=(field(cmd,"ema200")=="1");
         onSR=(field(cmd,"sr")=="1");
         onFibo=(field(cmd,"fibo")=="1");
         applyAll();

         string ack="";
         if(indicatorGet("/indicator_ack?id="+id,ack) &&
            StringFind(ack,"INDICATOR ACK OK")>=0){
            lastIndicatorCommandId=id;
            Print("RAYYAN4 indicators ACK id=",id,
                  " EMA50=",onEma50," EMA200=",onEma200,
                  " SR=",onSR," FIBO=",onFibo);
         }else{
            Print("RAYYAN4 indicator ACK not confirmed id=",id,
                  " response=",ack);
            return;
         }
      }
   }

   datetime bar=iTime(_Symbol,_Period,0);
   if(bar!=0 && bar!=lastIndicatorBar){
      lastIndicatorBar=bar;
      applyAll();
   }
}

ENUM_TIMEFRAMES parsePeriod(string tf){
   if(tf=="M1")return PERIOD_M1;
   if(tf=="M5")return PERIOD_M5;
   if(tf=="M15")return PERIOD_M15;
   if(tf=="H1")return PERIOD_H1;
   if(tf=="H4")return PERIOD_H4;
   if(tf=="D1")return PERIOD_D1;
   return PERIOD_CURRENT;
}

void applyAll(){
   drawEmaSegments(hEma50,"RAYYAN_EMA50_SEG_",onEma50,clrOrange);
   drawEmaSegments(hEma200,"RAYYAN_EMA200_SEG_",onEma200,clrMediumOrchid);
   updateSR();
   updateFibo();
   ChartRedraw(0);
}

void deleteEmaSegments(string prefix){
   int total=ObjectsTotal(0,0,-1);
   for(int i=total-1;i>=0;i--){
      string name=ObjectName(0,i,0,-1);
      if(StringFind(name,prefix)==0)ObjectDelete(0,name);
   }
}

// EMA curves use chart objects so their colors are controlled by the EA itself;
// a separate EA or custom indicator is not required.
void drawEmaSegments(int handle,string prefix,bool enabled,color lineColor){
   deleteEmaSegments(prefix);
   if(!enabled || handle==INVALID_HANDLE)return;

   int available=Bars(_Symbol,_Period);
   if(available<3)return;
   int count=(int)MathMin(RAYYAN_EMA_DRAW_BARS,available-1);
   double values[];
   datetime times[];
   ArraySetAsSeries(values,true);
   ArraySetAsSeries(times,true);
   int copiedValues=CopyBuffer(handle,0,0,count,values);
   int copiedTimes=CopyTime(_Symbol,_Period,0,count,times);
   int usable=(int)MathMin(copiedValues,copiedTimes);
   if(usable<3)return;

   for(int i=usable-2;i>=0;i--){
      string name=prefix+IntegerToString(i);
      if(!ObjectCreate(0,name,OBJ_TREND,0,
                       times[i+1],values[i+1],times[i],values[i]))
         continue;
      ObjectSetInteger(0,name,OBJPROP_COLOR,lineColor);
      ObjectSetInteger(0,name,OBJPROP_WIDTH,2);
      ObjectSetInteger(0,name,OBJPROP_STYLE,STYLE_SOLID);
      ObjectSetInteger(0,name,OBJPROP_RAY_LEFT,false);
      ObjectSetInteger(0,name,OBJPROP_RAY_RIGHT,false);
      ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
      ObjectSetInteger(0,name,OBJPROP_SELECTED,false);
      ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
      ObjectSetInteger(0,name,OBJPROP_BACK,false);
   }
}

void updateSR(){
   if(!onSR){
      ObjectDelete(0,"RAYYAN8_SR_RESISTANCE");
      ObjectDelete(0,"RAYYAN8_SR_SUPPORT");
      return;
   }
   int hi=iHighest(_Symbol,_Period,MODE_HIGH,SRLookbackBars,1);
   int lo=iLowest(_Symbol,_Period,MODE_LOW,SRLookbackBars,1);
   if(hi<0||lo<0)return;
   drawLevel("RAYYAN8_SR_RESISTANCE",iHigh(_Symbol,_Period,hi),clrTomato);
   drawLevel("RAYYAN8_SR_SUPPORT",iLow(_Symbol,_Period,lo),clrDeepSkyBlue);
}

void drawLevel(string name,double price,color c){
   if(ObjectFind(0,name)<0)ObjectCreate(0,name,OBJ_HLINE,0,0,price);
   ObjectSetDouble(0,name,OBJPROP_PRICE,price);
   ObjectSetInteger(0,name,OBJPROP_COLOR,c);
   ObjectSetInteger(0,name,OBJPROP_STYLE,STYLE_DASH);
   ObjectSetInteger(0,name,OBJPROP_WIDTH,1);
   ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
}

void updateFibo(){
   if(!onFibo){ObjectDelete(0,"RAYYAN8_FIBO");return;}
   int hi=iHighest(_Symbol,_Period,MODE_HIGH,FiboLookbackBars,1);
   int lo=iLowest(_Symbol,_Period,MODE_LOW,FiboLookbackBars,1);
   if(hi<0||lo<0)return;
   datetime th=iTime(_Symbol,_Period,hi),tl=iTime(_Symbol,_Period,lo);
   double ph=iHigh(_Symbol,_Period,hi),pl=iLow(_Symbol,_Period,lo);
   datetime t1=th<tl?th:tl;
   datetime t2=th<tl?tl:th;
   double p1=th<tl?ph:pl;
   double p2=th<tl?pl:ph;
   if(ObjectFind(0,"RAYYAN8_FIBO")<0)
      ObjectCreate(0,"RAYYAN8_FIBO",OBJ_FIBO,0,t1,p1,t2,p2);
   else{
      ObjectMove(0,"RAYYAN8_FIBO",0,t1,p1);
      ObjectMove(0,"RAYYAN8_FIBO",1,t2,p2);
   }
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_COLOR,clrGold);
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_RAY_RIGHT,true);
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_LEVELS,6);
   double levels[]={0.0,0.236,0.382,0.5,0.618,1.0};
   string labels[]={"0","23.6","38.2","50","61.8","100"};
   for(int i=0;i<6;i++){
      ObjectSetDouble(0,"RAYYAN8_FIBO",OBJPROP_LEVELVALUE,i,levels[i]);
      ObjectSetString(0,"RAYYAN8_FIBO",OBJPROP_LEVELTEXT,i,labels[i]);
   }
}
