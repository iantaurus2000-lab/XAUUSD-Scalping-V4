#property strict
#include <Trade/Trade.mqh>
CTrade trade;
CTrade powerTrade; // isolated order manager: legacy CTrade state/magic remains untouched
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
datetime powerLastDiag=0;
uint quoteDiagAtMs[3];
string quoteDiagState[3];
void manageTP2RetraceProtection();
void updateChartCountdown();
void uploadChartSnapshot();
void publishNativeChartBars();
bool appendBarsForTimeframe(string symbol,ENUM_TIMEFRAMES tf,string tfName,int count,string &batch);
void publishBarsForTimeframe(string symbol,ENUM_TIMEFRAMES tf,string tfName,int count);
string chartTimeframeName(ENUM_TIMEFRAMES tf);
uint lastNativeBarsTick=0;
uint lastScreenshotTick=0;

// Native chart layers are managed by this same EA; no second EA is needed.
input int SRLookbackBars=80;
input int FiboLookbackBars=60;
int hEma50=INVALID_HANDLE, hEma200=INVALID_HANDLE;
bool onEma50=true, onEma200=true, onSR=true, onFibo=true;
string lastIndicatorCommandId="";
datetime lastIndicatorBar=0, lastIndicatorDiag=0;
#define RAYYAN_EMA_DRAW_BARS 140

#define POWERFULL_MAGIC 4104451
#define POWERFULL_HISTORY_LIMIT 105120
#define POWERFULL_MIN_HISTORY 2000
#define POWERFULL_MIN_HISTORY_DAYS 350
#define POWERFULL_CONFIG_TIMEOUT_MS 300
#define POWERFULL_STATUS_TIMEOUT_MS 100
#define POWERFULL_GATEWAY_MIN_GAP_MS 500
#define POWERFULL_STATUS_INTERVAL_MS 2000
#define POWERFULL_REPLAN_MAX 2

string powerSymbols[3]={"XAUUSDm","BTCUSDm","ETHUSDm"};
struct PowerfullConfig {
   bool enabled;
   double lot;
   double pipSize;
   double stopPips;
   int percentile;
   string reason;
};
PowerfullConfig powerCfg[3];
datetime powerSeenBar[3];
datetime powerLastSeedBar[3];
uint powerLastModelAttemptMs[3];
uint powerModelBuiltAtMs[3];
uint powerLastSeedAttemptMs[3];
uint powerLastManageMs[3];
uint powerLastCancelAttemptMs[3];
uint powerConfigReceivedMs[3];
uint powerLastStatePublishMs[3];
uint powerLastConfigPollMs=0;
uint powerLastLegacyTimerMs=0;
uint powerLastGatewayRequestMs=0;
bool powerAllowGatewayCalls=false;
bool powerFastTickOnly=false;
int powerConfigRoundRobin=0;
int powerReplans[3];
bool powerModelGood[3];
int powerModelPercentile[3];
double powerModelPipSize[3];
bool powerReplacementPending[3];
bool powerCancelOnly[3];
int powerHistorySamples[3];
int powerHistoryDays[3];
double powerBaseRangePips[3];
double powerForecastRangePips[3];
double powerUpperShare[3];
double powerProjectedLow[3];
double powerProjectedHigh[3];
double powerLastBid[3];
double powerLastAsk[3];
long powerLastTickMs[3];
string powerLastStatus[3];
string powerLastDetail[3];

void managePowerfullAllMarkets(bool pollConfig);
void managePowerfullSymbol(int index);
bool powerGatewayGet(string path,int &httpCode,string &body,string &diagnostic,int timeoutMs);
bool powerRefreshConfig(int index);
bool powerBuildModel(int index);
void powerProjectBounds(int index,double candleOpen);
void powerPublishState(int index,string status,string detail,datetime barTime);
int powerPendingCount(string symbol);
int powerPositionCount(string symbol);
bool powerCancelPendingForSymbol(string symbol);
bool powerCancelStalePending(string symbol,datetime currentBar);
bool powerPlacePair(int index,datetime barTime,const MqlTick &tick);
void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool hasPreviousTick,double previousBid);
double powerProjectedNet(ulong ticket);
bool powerClosePosition(ulong ticket,string reason);
string powerShortSymbol(string symbol);
datetime powerOrderBarFromComment(string comment);
double powerRoundToTick(string symbol,double price);
bool powerConfigFresh(int index);
bool powerHasPendingOnBar(string symbol,datetime barTime);


int OnInit(){
   // Remove legacy chart-attached EMA/MA indicators and old Rayyan EMA objects.
   // The EA below is the sole owner of the two colored EMA curves.
   cleanupDuplicateEmaLayers();
   hEma50=iMA(_Symbol,_Period,50,0,MODE_EMA,PRICE_CLOSE);
   hEma200=iMA(_Symbol,_Period,200,0,MODE_EMA,PRICE_CLOSE);
   if(hEma50==INVALID_HANDLE || hEma200==INVALID_HANDLE)
      Print("RAYYAN4 INDICATOR WARNING EMA handles=",hEma50,",",hEma200);
   ChartSetInteger(0,CHART_AUTOSCROLL,true);
   ChartSetInteger(0,CHART_SHIFT,false);
   ChartSetInteger(0,CHART_SCALE,4);
   applyAll();
   if(!EventSetMillisecondTimer(100)) EventSetTimer(MathMax(1,PollSeconds));
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
void OnTick(){
   manageTP2RetraceProtection();
   // Opportunistic fast path; no gateway config request is issued from this event.
   managePowerfullAllMarkets(false);
}

void OnTimer(){
   // Fast 100 ms housekeeping lets the EA inspect all subscribed symbols near the
   // M5 boundary. Legacy gateway/chart work still runs only at PollSeconds cadence
   // and is serviced before any POWERFULL-specific gateway request.
   uint nowMs=GetTickCount();
   uint legacyInterval=(uint)MathMax(1,PollSeconds)*1000;
   if(powerLastLegacyTimerMs==0 || (uint)(nowMs-powerLastLegacyTimerMs)>=legacyInterval){
      powerLastLegacyTimerMs=nowMs;
      manageTP2RetraceProtection();
      processTradeQueue();
      publishOrderState();
      publishMarketQuotes();
      publishNativeChartBars();
      processIndicatorQueue();
      updateChartCountdown();
      // Screenshot mirroring disabled: native CopyRates OHLC is the chart source.
   }
   managePowerfullAllMarkets(true);
}

void processTradeQueue(){
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
}

void uploadChartSnapshot(){
   // Full chart screenshots are expensive under Winlator; trade polling stays at 1s,
   // while the visual mirror refreshes at a lighter cadence.
   if(GetTickCount()-lastScreenshotTick<2500) return;
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
   bool included[3];
   string details[3];
   for(int i=0;i<3;i++){included[i]=false;details[i]="";}
   string batch="";
   int count=0;
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
      if(StringLen(batch)>0)batch+="\n";
      batch+="symbol="+sym+
         ";bid="+DoubleToString(tick.bid,digits)+
         ";ask="+DoubleToString(tick.ask,digits)+
         ";point="+DoubleToString(point,10)+
         ";digits="+IntegerToString(digits)+
         ";tickms="+(string)tick.time_msc;
      included[i]=true;
      details[i]="bid="+DoubleToString(tick.bid,digits)+" ask="+DoubleToString(tick.ask,digits)+
         " digits="+IntegerToString(digits)+" point="+DoubleToString(point,10)+
         " tickms="+(string)tick.time_msc;
      count++;
   }
   if(count==0||StringLen(batch)==0)return;
   string base=GatewayUrl;
   while(StringLen(base)>0 && StringSubstr(base,StringLen(base)-1,1)=="/")
      base=StringSubstr(base,0,StringLen(base)-1);
   if(StringFind(base,"http://")!=0 && StringFind(base,"https://")!=0)return;
   char requestData[],responseData[];
   string responseHeaders="";
   int bytes=StringToCharArray(batch,requestData,0,WHOLE_ARRAY,CP_UTF8);
   if(bytes<=1)return;
   ArrayResize(requestData,bytes-1);
   ResetLastError();
   int code=WebRequest("POST",base+"/quote/upload",
      "Content-Type: text/plain; charset=utf-8\r\n",1000,
      requestData,responseData,responseHeaders);
   string response=CharArrayToString(responseData,0,WHOLE_ARRAY,CP_UTF8);
   if(code!=200||StringFind(response,"QUOTE OK")!=0){
      for(int i=0;i<3;i++)if(included[i])
         quoteDiagnostic(i,symbols[i],"PUBLISH_FAILED",
            "batch transport http="+IntegerToString(code)+" err="+IntegerToString(GetLastError())+
            " response="+response);
   }else{
      for(int i=0;i<3;i++)if(included[i])
         quoteDiagnostic(i,symbols[i],"QUOTE_PUBLISHED",details[i]);
   }
}

// Publish actual MT5 CopyRates OHLC to the APK-native chart; screenshots are not used as chart data.
// Publish OHLC for every APK market in one Gateway request. Chart selection in the APK
// must not depend on which symbol/timeframe happens to be open in the MT5 desktop.
void publishNativeChartBars(){
   uint now=GetTickCount();
   if(now-lastNativeBarsTick<2000) return;
   lastNativeBarsTick=now;
   string symbols[3]={"XAUUSDm","BTCUSDm","ETHUSDm"};
   ENUM_TIMEFRAMES frames[6]={PERIOD_M1,PERIOD_M5,PERIOD_M15,PERIOD_H1,PERIOD_H4,PERIOD_D1};
   string names[6]={"M1","M5","M15","H1","H4","D1"};
   string batch="";
   int series=0;
   for(int i=0;i<ArraySize(symbols);i++){
      string sym=symbols[i];
      if(!SymbolSelect(sym,true)){
         quoteDiagnostic(i,sym,"BARS_SYMBOL_SELECT_FAILED","cannot publish native OHLC; check exact broker symbol");
         continue;
      }
      for(int j=0;j<ArraySize(frames);j++){
         int count=(j<2?200:50);
         if(appendBarsForTimeframe(sym,frames[j],names[j],count,batch))series++;
      }
   }
   if(series==0 || StringLen(batch)==0) return;
   string base=GatewayUrl;
   while(StringLen(base)>0 && StringSubstr(base,StringLen(base)-1,1)=="/")
      base=StringSubstr(base,0,StringLen(base)-1);
   if(StringFind(base,"http://")!=0 && StringFind(base,"https://")!=0) return;
   char requestData[],responseData[];
   string responseHeaders="";
   int bytes=StringToCharArray(batch,requestData,0,WHOLE_ARRAY,CP_UTF8);
   if(bytes<=1) return;
   ArrayResize(requestData,bytes-1);
   ResetLastError();
   int code=WebRequest("POST",base+"/bars/upload",
      "Content-Type: text/plain; charset=utf-8\r\n",1000,
      requestData,responseData,responseHeaders);
   string response=CharArrayToString(responseData,0,WHOLE_ARRAY,CP_UTF8);
   if(code!=200 || StringFind(response,"BARS OK")!=0){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN NATIVE BARS FAILED http=",code," err=",GetLastError(),
               " series=",series," body=",response);
      }
   }else if(TimeCurrent()-lastDiag>=30){
      lastDiag=TimeCurrent();
      Print("RAYYAN NATIVE BARS OK series=",series," body=",response);
   }
}
bool appendBarsForTimeframe(string symbol,ENUM_TIMEFRAMES tf,string tfName,int count,string &batch){
   MqlRates rates[];
   ArraySetAsSeries(rates,true);
   int copied=CopyRates(symbol,tf,0,count,rates);
   if(copied<2) return false;
   int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
   string bars="";
   for(int i=copied-1;i>=0;i--){
      if(StringLen(bars)>0) bars+="|";
      bars+=IntegerToString((long)rates[i].time*1000)+","+
         DoubleToString(rates[i].open,digits)+","+
         DoubleToString(rates[i].high,digits)+","+
         DoubleToString(rates[i].low,digits)+","+
         DoubleToString(rates[i].close,digits);
   }
   if(StringLen(batch)>0) batch+="\n";
   batch+="symbol="+symbol+";tf="+tfName+";bars="+bars;
   return true;
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

void cleanupDuplicateEmaLayers(){
   long windows=ChartGetInteger(0,CHART_WINDOWS_TOTAL);
   for(int window=(int)windows-1;window>=0;window--){
      int total=ChartIndicatorsTotal(0,window);
      for(int i=total-1;i>=0;i--){
         string shortName=ChartIndicatorName(0,window,i);
         string upper=shortName;
         StringToUpper(upper);
         if(StringFind(upper,"EMA")>=0 || StringFind(upper,"RAYYANCOLOREDEMA")>=0
            || StringFind(upper,"MOVING AVERAGE")>=0){
            if(ChartIndicatorDelete(0,window,shortName))
               Print("RAYYAN4 removed duplicate chart MA indicator: ",shortName);
         }
      }
   }
   int totalObjects=ObjectsTotal(0,0,-1);
   for(int i=totalObjects-1;i>=0;i--){
      string objectName=ObjectName(0,i,0,-1);
      string upperObject=objectName;
      StringToUpper(upperObject);
      if(StringFind(upperObject,"RAYYAN")==0 && StringFind(upperObject,"EMA")>=0)
         ObjectDelete(0,objectName);
   }
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
   // Anchor the 0% level at the most recent swing extreme:
   // bullish impulse: 0% at high and retrace downward toward the older low;
   // bearish impulse: 0% at low and retrace upward toward the older high.
   // This orientation matches SignalEngine's BUY high-range*ratio / SELL low+range*ratio.
   bool highIsRecent=(th>tl);
   datetime t1=highIsRecent?th:tl;
   datetime t2=highIsRecent?tl:th;
   double p1=highIsRecent?ph:pl;
   double p2=highIsRecent?pl:ph;
   if(ObjectFind(0,"RAYYAN8_FIBO")<0)
      ObjectCreate(0,"RAYYAN8_FIBO",OBJ_FIBO,0,t1,p1,t2,p2);
   else{
      ObjectMove(0,"RAYYAN8_FIBO",0,t1,p1);
      ObjectMove(0,"RAYYAN8_FIBO",1,t2,p2);
   }
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_COLOR,clrGold);
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_RAY_RIGHT,true);
   // Keep chart levels aligned with SignalEngine's M1 retracement and TP2 extension.
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_LEVELS,8);
   // -27.2% extends beyond the 0% swing extreme (127.2% target).
   double levels[]={0.0,0.236,0.382,0.5,0.618,0.786,1.0,-0.272};
   string labels[]={"0","23.6","38.2","50","61.8","78.6","100","127.2 TP2"};
   for(int i=0;i<8;i++){
      ObjectSetDouble(0,"RAYYAN8_FIBO",OBJPROP_LEVELVALUE,i,levels[i]);
      ObjectSetString(0,"RAYYAN8_FIBO",OBJPROP_LEVELTEXT,i,labels[i]);
   }
}


/* =========================================================================
   SMART AUTO-POWERFULL -- isolated M5 distribution / dual LIMIT strategy.
   Separate magic number and /power_config + /power_state endpoints. Legacy
   /next, /ack, /state and RAYYAN_MAGIC order handling remain independent.
   ========================================================================= */

bool powerGatewayGet(string path,int &httpCode,string &body,string &diagnostic,int timeoutMs){
   httpCode=-1; body=""; diagnostic="";
   string base=GatewayUrl;
   while(StringLen(base)>0 && StringSubstr(base,StringLen(base)-1,1)=="/")
      base=StringSubstr(base,0,StringLen(base)-1);
   if(StringFind(base,"http://")!=0 && StringFind(base,"https://")!=0){
      diagnostic="GatewayUrl must start with http:// or https://";
      return false;
   }
   string responseHeaders="";
   char requestData[];
   char responseData[];
   ResetLastError();
   int status=WebRequest("GET",base+path,"","",timeoutMs,
                         requestData,0,responseData,responseHeaders);
   int err=GetLastError();
   if(status==-1 || status==1001){
      diagnostic="POWERFULL WebRequest failed err="+IntegerToString(err);
      return false;
   }
   httpCode=status;
   body=CharArrayToString(responseData,0,ArraySize(responseData),CP_UTF8);
   return (status>=100 && status<=599);
}

bool powerRefreshConfig(int index){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   int code=-1; string body="",diagnostic="";
   bool received=powerGatewayGet("/power_config?symbol="+urlEncode(powerSymbols[index]),
                                 code,body,diagnostic,POWERFULL_CONFIG_TIMEOUT_MS);
   powerLastGatewayRequestMs=GetTickCount();
   if(!received||code!=200){
      if(TimeCurrent()-powerLastDiag>=10){
         powerLastDiag=TimeCurrent();
         Print("POWERFULL CONFIG ERROR symbol=",powerSymbols[index],
               " code=",code," detail=",diagnostic);
      }
      return false;
   }
   if(field(body,"symbol")!=powerSymbols[index]){
      Print("POWERFULL CONFIG INVALID symbol=",powerSymbols[index]," body=",body);
      return false;
   }
   string symbol=powerSymbols[index];
   double oldPipSize=powerCfg[index].pipSize;
   int oldPercentile=powerCfg[index].percentile;
   double lot=StringToDouble(field(body,"lot"));
   double pip=StringToDouble(field(body,"pip"));
   double stop=StringToDouble(field(body,"stopPips"));
   int percentile=(int)StringToInteger(field(body,"percentile"));
   string reason=field(body,"reason");
   bool requested=(field(body,"enabled")=="1");
   double minLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MIN);
   if(minLot<=0.0)minLot=(symbol=="XAUUSDm"?0.01:0.10);
   bool valid=MathIsValidNumber(lot)&&lot+1e-8>=minLot
      &&MathIsValidNumber(pip)&&pip>0.0
      &&MathIsValidNumber(stop)&&stop>0.0
      &&(percentile==50||percentile==60||percentile==70||percentile==80||percentile==90);
   powerCfg[index].enabled=requested&&valid;
   powerCfg[index].lot=lot;
   powerCfg[index].pipSize=pip;
   powerCfg[index].stopPips=stop;
   powerCfg[index].percentile=percentile;
   powerCfg[index].reason=!requested?"DISABLED":(valid?reason:"CONFIG_INVALID");
   powerConfigReceivedMs[index]=GetTickCount();
   if(valid&&(MathAbs(pip-oldPipSize)>1e-12||percentile!=oldPercentile)){
      powerModelGood[index]=false;
      powerModelBuiltAtMs[index]=0;
      powerLastModelAttemptMs[index]=0;
   }
   return true;
}

bool powerConfigFresh(int index){
   if(index<0||index>=ArraySize(powerSymbols)||powerConfigReceivedMs[index]==0)return false;
   return (uint)(GetTickCount()-powerConfigReceivedMs[index])<=10000;
}

void managePowerfullAllMarkets(bool pollConfig){
   powerAllowGatewayCalls=pollConfig;
   powerFastTickOnly=!pollConfig;
   uint nowMs=GetTickCount();
   if(pollConfig&&(powerLastConfigPollMs==0||
      (uint)(nowMs-powerLastConfigPollMs)>=1000)){
      powerLastConfigPollMs=nowMs;
      int index=powerConfigRoundRobin;
      powerConfigRoundRobin=(powerConfigRoundRobin+1)%ArraySize(powerSymbols);
      powerRefreshConfig(index);
   }
   for(int i=0;i<ArraySize(powerSymbols);i++)managePowerfullSymbol(i);
   powerFastTickOnly=false;
   powerAllowGatewayCalls=false;
}

bool powerBuildModel(int index){
   powerModelGood[index]=false;
   string symbol=powerSymbols[index];
   double pip=powerCfg[index].pipSize;
   if(pip<=0.0)return false;
   MqlRates rates[];
   ArraySetAsSeries(rates,true);
   ResetLastError();
   int copied=CopyRates(symbol,PERIOD_M5,1,POWERFULL_HISTORY_LIMIT,rates);
   if(copied<POWERFULL_MIN_HISTORY){
      powerModelGood[index]=false;
      powerHistorySamples[index]=MathMax(0,copied);
      powerHistoryDays[index]=(copied>0&&ArraySize(rates)>=copied&&rates[copied-1].time>0)
         ?(int)((TimeCurrent()-rates[copied-1].time)/86400):0;
      Print("POWERFULL HISTORY SHORT symbol=",symbol," copied=",copied,
            " minimum=",POWERFULL_MIN_HISTORY," days=",powerHistoryDays[index],
            " err=",GetLastError());
      return false;
   }
   datetime cutoff=TimeCurrent()-365*24*60*60;
   double ranges[];
   ArrayResize(ranges,copied);
   int n=0;
   double rangeSum=0.0,upperSum=0.0,recentSum=0.0;
   int recentCount=0;
   datetime oldest=0;
   for(int i=0;i<copied;i++){
      if(rates[i].time<cutoff)continue;
      double span=rates[i].high-rates[i].low;
      if(!MathIsValidNumber(span)||span<=0.0||rates[i].open<=0.0)continue;
      double rangePips=span/pip;
      if(!MathIsValidNumber(rangePips)||rangePips<=0.0)continue;
      ranges[n++]=rangePips;
      rangeSum+=rangePips;
      upperSum+=(rates[i].high-rates[i].open)/span;
      if(oldest==0||rates[i].time<oldest)oldest=rates[i].time;
      if(i<14){recentSum+=rangePips;recentCount++;}
   }
   powerHistorySamples[index]=n;
   powerHistoryDays[index]=oldest>0?(int)((TimeCurrent()-oldest)/86400):0;
   if(n<POWERFULL_MIN_HISTORY||recentCount<5){
      powerModelGood[index]=false;
      Print("POWERFULL HISTORY WINDOW TOO SHORT symbol=",symbol," bars=",n,
            " recent=",recentCount," days=",powerHistoryDays[index]);
      return false;
   }
   if(powerHistoryDays[index]<POWERFULL_MIN_HISTORY_DAYS){
      powerModelGood[index]=false;
      Print("POWERFULL HISTORY COVERAGE SHORT symbol=",symbol,
            " days=",powerHistoryDays[index]," requiredDays=",POWERFULL_MIN_HISTORY_DAYS,
            " samples=",n," | increase MT5 Max bars/load more M5 history");
      return false;
   }
   ArrayResize(ranges,n);
   ArraySort(ranges);
   int percentile=powerCfg[index].percentile;
   if(percentile<50||percentile>90)percentile=70;
   int q=(int)MathFloor((percentile/100.0)*(n-1));
   if(q<0)q=0;
   if(q>=n)q=n-1;
   double quantileRange=ranges[q];
   double historicalMean=rangeSum/n;
   double recentMean=recentSum/recentCount;
   double scale=historicalMean>0.0?recentMean/historicalMean:1.0;
   scale=MathMax(0.50,MathMin(2.00,scale));
   double forecast=quantileRange*scale;
   double upperShare=MathMax(0.35,MathMin(0.65,upperSum/n));
   if(!MathIsValidNumber(forecast)||forecast<=0.0)return false;
   powerBaseRangePips[index]=forecast;
   powerForecastRangePips[index]=forecast;
   powerUpperShare[index]=upperShare;
   powerModelGood[index]=true;
   powerModelBuiltAtMs[index]=GetTickCount();
   Print("POWERFULL MODEL symbol=",symbol," samples=",n," historyDays=",powerHistoryDays[index],
         " P",percentile,"=",DoubleToString(quantileRange,2)," pip scale=",DoubleToString(scale,2),
         " rangeForecast=",DoubleToString(forecast,2));
   return true;
}

void powerProjectBounds(int index,double candleOpen){
   double pip=powerCfg[index].pipSize;
   double span=powerForecastRangePips[index]*pip;
   double upper=span*powerUpperShare[index];
   double lower=span-upper;
   powerProjectedLow[index]=candleOpen-lower;
   powerProjectedHigh[index]=candleOpen+upper;
}

string powerShortSymbol(string symbol){
   if(symbol=="XAUUSDm")return "XAU";
   if(symbol=="BTCUSDm")return "BTC";
   if(symbol=="ETHUSDm")return "ETH";
   return "UNK";
}

datetime powerOrderBarFromComment(string comment){
   string parts[];
   int count=StringSplit(comment,'-',parts);
   if(count<4||parts[0]!="PWR")return 0;
   return (datetime)StringToInteger(parts[2]);
}

int powerPendingCount(string symbol){
   int count=0;
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_SELL_LIMIT)count++;
   }
   return count;
}

int powerPositionCount(string symbol){
   int count=0;
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0)continue;
      if(PositionGetString(POSITION_SYMBOL)!=symbol)continue;
      if((long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      count++;
   }
   return count;
}

bool powerHasPendingOnBar(string symbol,datetime barTime){
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(type!=ORDER_TYPE_BUY_LIMIT&&type!=ORDER_TYPE_SELL_LIMIT)continue;
      if(powerOrderBarFromComment(OrderGetString(ORDER_COMMENT))==barTime)return true;
   }
   return false;
}

bool powerCancelPendingForSymbol(string symbol){
   int symbolIndex=-1;
   for(int s=0;s<ArraySize(powerSymbols);s++)if(powerSymbols[s]==symbol){symbolIndex=s;break;}
   uint nowMs=GetTickCount();
   if(symbolIndex>=0&&powerLastCancelAttemptMs[symbolIndex]!=0&&
      (uint)(nowMs-powerLastCancelAttemptMs[symbolIndex])<300)
      return powerPendingCount(symbol)==0;
   if(symbolIndex>=0)powerLastCancelAttemptMs[symbolIndex]=nowMs;
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(type!=ORDER_TYPE_BUY_LIMIT&&type!=ORDER_TYPE_SELL_LIMIT)continue;
      ResetLastError();
      bool deleted=powerTrade.OrderDelete(ticket);
      uint rc=powerTrade.ResultRetcode();
      if(!deleted||rc!=TRADE_RETCODE_DONE)
         Print("POWERFULL CANCEL FAILED symbol=",symbol," ticket=",ticket,
               " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
      else Print("POWERFULL CANCEL OK symbol=",symbol," ticket=",ticket);
   }
   return powerPendingCount(symbol)==0;
}

bool powerCancelStalePending(string symbol,datetime currentBar){
   int symbolIndex=-1;
   for(int s=0;s<ArraySize(powerSymbols);s++)if(powerSymbols[s]==symbol){symbolIndex=s;break;}
   uint nowMs=GetTickCount();
   if(symbolIndex>=0&&powerLastCancelAttemptMs[symbolIndex]!=0&&
      (uint)(nowMs-powerLastCancelAttemptMs[symbolIndex])<300)
      return powerPendingCount(symbol)==0;
   if(symbolIndex>=0)powerLastCancelAttemptMs[symbolIndex]=nowMs;
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(type!=ORDER_TYPE_BUY_LIMIT&&type!=ORDER_TYPE_SELL_LIMIT)continue;
      if(powerOrderBarFromComment(OrderGetString(ORDER_COMMENT))==currentBar)continue;
      bool deleted=powerTrade.OrderDelete(ticket);
      uint rc=powerTrade.ResultRetcode();
      if(!deleted||rc!=TRADE_RETCODE_DONE)
         Print("POWERFULL STALE CANCEL FAILED symbol=",symbol," ticket=",ticket," retcode=",rc);
      else Print("POWERFULL STALE CANCEL OK symbol=",symbol," ticket=",ticket);
   }
   return powerPendingCount(symbol)==0;
}

double powerRoundToTick(string symbol,double price){
   double step=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
   if(step<=0.0)step=point;
   if(step<=0.0)return 0.0;
   return NormalizeDouble(MathRound(price/step)*step,digits);
}

double powerFloorToTick(string symbol,double price){
   double step=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
   if(step<=0.0)step=point;
   if(step<=0.0)return 0.0;
   return NormalizeDouble(MathFloor(price/step+1e-10)*step,digits);
}

double powerCeilToTick(string symbol,double price){
   double step=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
   if(step<=0.0)step=point;
   if(step<=0.0)return 0.0;
   return NormalizeDouble(MathCeil(price/step-1e-10)*step,digits);
}

bool powerPlacePair(int index,datetime barTime,const MqlTick &tick){
   string symbol=powerSymbols[index];
   double pip=powerCfg[index].pipSize;
   double lot=powerCfg[index].lot;
   double stopPips=powerCfg[index].stopPips;
   if(!powerModelGood[index]||pip<=0.0||powerForecastRangePips[index]<=0.0)return false;
   // POWERFULL remains demo-only even if the legacy EA's shared DemoOnly input
   // is changed for another workflow.
   if(AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
      powerPublishState(index,"BLOCKED_NOT_DEMO","SMART AUTO-POWERFULL is demo-only",barTime);
      return false;
   }
   ENUM_ACCOUNT_MARGIN_MODE marginMode=(ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE);
   bool hedgeAllowed=(AccountInfoInteger(ACCOUNT_HEDGE_ALLOWED)!=0);
   if(marginMode!=ACCOUNT_MARGIN_MODE_RETAIL_HEDGING||!hedgeAllowed){
      powerPublishState(index,"BLOCKED_NETTING_ACCOUNT",
         "POWERFULL requires MT5 hedging mode with opposite positions allowed; legacy order paths remain untouched",barTime);
      return false;
   }
   long elapsedMs=tick.time_msc-(long)barTime*1000;
   // First dual-LIMIT pair belongs at the beginning of its M5 candle. A forecast
   // replacement may be sent later in the same candle, but only after old orders
   // are confirmed deleted and with enough time before the specified expiry.
   if(!powerReplacementPending[index]&&(elapsedMs<0||elapsedMs>5000)){
      powerLastSeedBar[index]=barTime;
      powerPublishState(index,"MISSED_ENTRY_WINDOW","Initial dual LIMIT not sent outside first 5 seconds of M5; wait for the next candle",barTime);
      return false;
   }
   if(!TerminalInfoInteger(TERMINAL_CONNECTED)||!TerminalInfoInteger(TERMINAL_TRADE_ALLOWED)
      ||!MQLInfoInteger(MQL_TRADE_ALLOWED)||!AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)){
      powerPublishState(index,"BLOCKED_TRADING_DISABLED","Terminal/account trading permission unavailable",barTime);
      return false;
   }
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   double tickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   double stopsPoints=(double)SymbolInfoInteger(symbol,SYMBOL_TRADE_STOPS_LEVEL);
   double minDistance=stopsPoints*point;
   if(point<=0.0||tickSize<=0.0){
      powerPublishState(index,"BLOCKED_SYMBOL_SPEC","SYMBOL_POINT/TICK_SIZE invalid",barTime);
      return false;
   }
   long expirationModes=SymbolInfoInteger(symbol,SYMBOL_EXPIRATION_MODE);
   if((expirationModes&SYMBOL_EXPIRATION_SPECIFIED)==0){
      powerPublishState(index,"BLOCKED_EXPIRY_MODE","Broker does not support specified expiration; safe 5-minute pending expiry is required",barTime);
      return false;
   }
   datetime expiry=barTime+300;
   if(TimeCurrent()>=expiry-5){
      powerPublishState(index,"BLOCKED_LATE_ENTRY","Too little candle time remains to safely create a new pair",barTime);
      return false;
   }
   double stopDistance=stopPips*pip;
   if(stopDistance+1e-10<minDistance+tickSize){
      powerPublishState(index,"BLOCKED_SL_TOO_CLOSE","Broker stop level exceeds configured 3-pip loss guard",barTime);
      return false;
   }
   double spreadPips=(tick.ask-tick.bid)/pip;
   double spreadCap=MathMin(stopPips*0.50,powerForecastRangePips[index]*0.02);
   if(!MathIsValidNumber(spreadPips)||spreadPips<0.0||spreadPips>spreadCap){
      powerPublishState(index,"BLOCKED_SPREAD","spread="+DoubleToString(spreadPips,2)+
                        " pip; limit="+DoubleToString(spreadCap,2)+" pip",barTime);
      return false;
   }
   double low=powerProjectedLow[index];
   double high=powerProjectedHigh[index];
   double span=powerForecastRangePips[index]*pip;
   double buyEntry=powerFloorToTick(symbol,low+span*0.01);
   double sellEntry=powerCeilToTick(symbol,low+span*0.99);
   if(buyEntry<=0.0||sellEntry<=buyEntry
      ||buyEntry>=tick.ask-minDistance-tickSize
      ||sellEntry<=tick.bid+minDistance+tickSize){
      powerPublishState(index,"BLOCKED_ENTRY_GEOMETRY","predicted 1%/99% level is no longer a valid LIMIT price",barTime);
      return false;
   }
   double minLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MIN);
   double maxLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MAX);
   double step=SymbolInfoDouble(symbol,SYMBOL_VOLUME_STEP);
   if(minLot<=0.0||maxLot<minLot||step<=0.0||lot+1e-8<minLot||lot>maxLot+1e-8){
      powerPublishState(index,"BLOCKED_LOT","configured lot does not match broker volume constraints",barTime);
      return false;
   }
   lot=MathFloor((lot+1e-9)/step)*step;
   lot=NormalizeDouble(lot,8);
   if(lot+1e-8<minLot){
      powerPublishState(index,"BLOCKED_LOT","rounded volume below broker minimum",barTime);
      return false;
   }

   string baseComment="PWR-"+powerShortSymbol(symbol)+"-"+IntegerToString((long)barTime);
   string buyComment=baseComment+"-B";
   string sellComment=baseComment+"-S";
   double buySL=powerFloorToTick(symbol,buyEntry-stopDistance);
   double sellSL=powerCeilToTick(symbol,sellEntry+stopDistance);
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   powerTrade.SetTypeFillingBySymbol(symbol);
   powerTrade.SetAsyncMode(false);
   ResetLastError();
   bool buyOk=powerTrade.BuyLimit(lot,buyEntry,symbol,buySL,0.0,ORDER_TIME_SPECIFIED,expiry,buyComment);
   uint buyRc=powerTrade.ResultRetcode();
   ulong buyTicket=powerTrade.ResultOrder();
   if(!buyOk||(buyRc!=TRADE_RETCODE_PLACED&&buyRc!=TRADE_RETCODE_DONE)){
      // Treat timeouts/rejections as uncertain until the terminal order list proves
      // there is no orphaned Powerfull pending order for this symbol.
      bool cleanup=powerCancelPendingForSymbol(symbol);
      powerLastSeedBar[index]=barTime;
      powerReplacementPending[index]=false;
      powerCancelOnly[index]=!cleanup;
      powerPublishState(index,cleanup?"BUY_REJECTED_CLEAN":"BUY_REJECTED_UNCERTAIN",
                        "retcode="+IntegerToString((int)buyRc)+" "+
                        powerTrade.ResultRetcodeDescription()+" cleanup="+(cleanup?"OK":"PENDING"),barTime);
      Print("POWERFULL BUY LIMIT FAILED symbol=",symbol," entry=",buyEntry," sl=",buySL,
            " retcode=",buyRc," ",powerTrade.ResultRetcodeDescription(),
            " cleanup=",cleanup," err=",GetLastError());
      return false;
   }

   ResetLastError();
   bool sellOk=powerTrade.SellLimit(lot,sellEntry,symbol,sellSL,0.0,ORDER_TIME_SPECIFIED,expiry,sellComment);
   uint sellRc=powerTrade.ResultRetcode();
   ulong sellTicket=powerTrade.ResultOrder();
   if(!sellOk||(sellRc!=TRADE_RETCODE_PLACED&&sellRc!=TRADE_RETCODE_DONE)){
      bool rollback=powerCancelPendingForSymbol(symbol);
      powerLastSeedBar[index]=barTime;
      powerReplacementPending[index]=false;
      powerCancelOnly[index]=!rollback;
      powerPublishState(index,rollback?"PAIR_ROLLED_BACK":"PAIR_PARTIAL_UNCERTAIN",
                        "SELL rejected retcode="+IntegerToString((int)sellRc)+
                        " "+powerTrade.ResultRetcodeDescription()+" rollback="+(rollback?"OK":"PENDING"),barTime);
      Print("POWERFULL SELL LIMIT FAILED symbol=",symbol," entry=",sellEntry," sl=",sellSL,
            " retcode=",sellRc," ",powerTrade.ResultRetcodeDescription(),
            " rollback=",rollback," err=",GetLastError());
      return false;
   }
   powerLastSeedBar[index]=barTime;
   powerReplacementPending[index]=false;
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   Print("POWERFULL PAIR PLACED symbol=",symbol," bar=",TimeToString(barTime,TIME_DATE|TIME_MINUTES),
         " samples=",powerHistorySamples[index]," days=",powerHistoryDays[index],
         " quantile=P",powerCfg[index].percentile," range=",DoubleToString(powerForecastRangePips[index],2),
         " low=",DoubleToString(low,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
         " high=",DoubleToString(high,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
         " buyTicket=",buyTicket," buy=",buyEntry," sellTicket=",sellTicket," sell=",sellEntry,
         " SL=",DoubleToString(stopPips,2)," pip each");
   powerPublishState(index,"PAIR_PLACED","BUY 1% and SELL 99% pending sent; verify actual state in MT5",
                     barTime);
   return true;
}

double powerProjectedNet(ulong ticket){
   if(ticket==0||!PositionSelectByTicket(ticket))return 0.0;
   double commissionTotal=0.0,expectedExitCosts=0.0;
   ulong positionId=(ulong)PositionGetInteger(POSITION_IDENTIFIER);
   if(positionId>0&&HistorySelectByPosition(positionId)){
      int deals=HistoryDealsTotal();
      for(int i=0;i<deals;i++){
         ulong deal=HistoryDealGetTicket(i);
         if(deal==0)continue;
         double commission=HistoryDealGetDouble(deal,DEAL_COMMISSION);
         double fee=HistoryDealGetDouble(deal,DEAL_FEE);
         commissionTotal+=commission+fee;
         long entry=HistoryDealGetInteger(deal,DEAL_ENTRY);
         if(entry==DEAL_ENTRY_IN)expectedExitCosts+=MathAbs(commission+fee);
      }
   }
   return PositionGetDouble(POSITION_PROFIT)+PositionGetDouble(POSITION_SWAP)
          +commissionTotal-expectedExitCosts;
}

bool powerClosePosition(ulong ticket,string reason){
   if(ticket==0||!PositionSelectByTicket(ticket))return false;
   string symbol=PositionGetString(POSITION_SYMBOL);
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   powerTrade.SetTypeFillingBySymbol(symbol);
   ResetLastError();
   bool ok=powerTrade.PositionClose(ticket);
   uint rc=powerTrade.ResultRetcode();
   if(ok&&(rc==TRADE_RETCODE_DONE||rc==TRADE_RETCODE_DONE_PARTIAL)){
      Print("POWERFULL CLOSE OK symbol=",symbol," ticket=",ticket," reason=",reason," retcode=",rc);
      string suffix=IntegerToString((long)ticket);
      string key="PW_HMODE_"+suffix;
      string barKey="PW_HBAR_"+suffix;
      if(GlobalVariableCheck(key))GlobalVariableDel(key);
      if(GlobalVariableCheck(barKey))GlobalVariableDel(barKey);
      return true;
   }
   Print("POWERFULL CLOSE FAILED symbol=",symbol," ticket=",ticket," reason=",reason,
         " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
   return false;
}

void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool hasPreviousTick,double previousBid){
   string symbol=powerSymbols[index];
   double pip=powerCfg[index].pipSize;
   double stopPips=powerCfg[index].stopPips;
   if(pip<=0.0||stopPips<=0.0)return;
   long barEndMs=((long)barTime+300)*1000;
   long remainingMs=barEndMs-tick.time_msc;
   bool last100ms=(remainingMs>=0&&remainingMs<=100);
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0)continue;
      if(PositionGetString(POSITION_SYMBOL)!=symbol)continue;
      if((long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      if(!PositionSelectByTicket(ticket))continue;
      ENUM_POSITION_TYPE side=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN);
      double executable=(side==POSITION_TYPE_BUY?tick.bid:tick.ask);
      double movementPips=(side==POSITION_TYPE_BUY?(executable-entry)/pip:(entry-executable)/pip);
      double net=powerProjectedNet(ticket);
      if(movementPips<=-stopPips){
         powerClosePosition(ticket,"HARD_LOSS_GUARD_3PIP");
         continue;
      }
      bool alignedMomentum=hasPreviousTick&&
         (side==POSITION_TYPE_BUY
            ?(tick.bid>candleOpen+pip*0.10&&tick.bid>previousBid)
            :(tick.bid<candleOpen-pip*0.10&&tick.bid<previousBid));
      string suffix=IntegerToString((long)ticket);
      string holdKey="PW_HMODE_"+suffix;
      string holdBarKey="PW_HBAR_"+suffix;
      double holdMode=GlobalVariableCheck(holdKey)?GlobalVariableGet(holdKey):0.0;
      datetime holdBar=GlobalVariableCheck(holdBarKey)?(datetime)GlobalVariableGet(holdBarKey):0;

      // Only the final 100 ms + aligned momentum can arm a cross-candle hold.
      if(last100ms&&net>0.0&&alignedMomentum){
         GlobalVariableSet(holdKey,1.0);
         GlobalVariableSet(holdBarKey,(double)barTime);
         continue;
      }
      if(holdMode>=1.0&&holdBar>0){
         if(holdMode==1.0&&barTime>holdBar){
            if(alignedMomentum){
               GlobalVariableSet(holdKey,2.0);
               GlobalVariableSet(holdBarKey,(double)barTime);
               continue;
            }
            if(tick.time_msc>((long)barTime*1000+1000)&&net>0.0)
               powerClosePosition(ticket,"NEXT_M5_MOMENTUM_NOT_CONFIRMED");
            else if(net<=0.0)
               powerClosePosition(ticket,"CROSS_CANDLE_PROFIT_GUARD");
            continue;
         }
         if(holdMode>=2.0&&barTime==holdBar){
            bool reversed=hasPreviousTick&&
               (side==POSITION_TYPE_BUY
                  ?tick.bid<previousBid-pip*0.10
                  :tick.bid>previousBid+pip*0.10);
            if(net<=0.0)powerClosePosition(ticket,"CROSS_CANDLE_PROFIT_GUARD");
            else if(reversed)powerClosePosition(ticket,"CROSS_CANDLE_MOMENTUM_REVERSED");
            continue;
         }
      }
      if(net>0.0)powerClosePosition(ticket,"DYNAMIC_NET_PROFIT");
   }
}

void powerPublishState(int index,string status,string detail,datetime barTime){
   if(!powerAllowGatewayCalls)return; // never perform a synchronous HTTP request inside OnTick
   uint nowMs=GetTickCount();
   if(powerLastStatePublishMs[index]!=0&&
      (uint)(nowMs-powerLastStatePublishMs[index])<POWERFULL_STATUS_INTERVAL_MS&&
      status==powerLastStatus[index]&&detail==powerLastDetail[index])return;
   // Limit Powerfull status traffic so intermittent gateway latency cannot create
   // a burst of synchronous HTTP calls after the legacy /next queue was serviced.
   if(powerLastGatewayRequestMs!=0&&
      (uint)(nowMs-powerLastGatewayRequestMs)<POWERFULL_GATEWAY_MIN_GAP_MS)return;
   powerLastStatePublishMs[index]=nowMs;
   powerLastStatus[index]=status;
   powerLastDetail[index]=detail;
   string symbol=powerSymbols[index];
   string path="/power_state?symbol="+urlEncode(symbol)
      +"&status="+urlEncode(status)
      +"&range="+DoubleToString(powerForecastRangePips[index],4)
      +"&low="+DoubleToString(powerProjectedLow[index],(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS))
      +"&high="+DoubleToString(powerProjectedHigh[index],(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS))
      +"&bar="+IntegerToString((long)barTime)
      +"&samples="+IntegerToString(powerHistorySamples[index])
       +"&days="+IntegerToString(powerHistoryDays[index])
      +"&pending="+IntegerToString(powerPendingCount(symbol))
      +"&positions="+IntegerToString(powerPositionCount(symbol))
      +"&detail="+urlEncode(detail);
   int code=-1; string body="",diagnostic="";
   bool stateSent=powerGatewayGet(path,code,body,diagnostic,POWERFULL_STATUS_TIMEOUT_MS);
   powerLastGatewayRequestMs=GetTickCount();
   if(!stateSent||code!=200||StringFind(body,"POWER STATE OK")<0){
      if(TimeCurrent()-powerLastDiag>=10){
         powerLastDiag=TimeCurrent();
         Print("POWERFULL STATUS UPLOAD FAILED symbol=",symbol," code=",code," detail=",diagnostic,
               " response=",body);
      }
   }
}

void managePowerfullSymbol(int index){
   if(index<0||index>=ArraySize(powerSymbols))return;
   uint nowMs=GetTickCount();
   // Timer housekeeping is throttled; the OnTick path must inspect every arriving tick.
   if(!powerFastTickOnly&&powerLastManageMs[index]!=0&&
      (uint)(nowMs-powerLastManageMs[index])<25)return;
   powerLastManageMs[index]=nowMs;
   string symbol=powerSymbols[index];
   if(!SymbolSelect(symbol,true)){
      powerPublishState(index,"SYMBOL_UNAVAILABLE","SymbolSelect failed; check broker Market Watch",0);
      return;
   }
   MqlTick tick;
   if(!SymbolInfoTick(symbol,tick)||tick.bid<=0.0||tick.ask<tick.bid||tick.time_msc<=0){
      powerPublishState(index,"QUOTE_INVALID","SymbolInfoTick unavailable or invalid",0);
      return;
   }
   long tickAgeMs=(long)TimeCurrent()*1000-tick.time_msc;
   if(tickAgeMs>5000||tickAgeMs< -3000){
      powerPublishState(index,"QUOTE_STALE","MT5 tick age="+IntegerToString((int)tickAgeMs)+
                        " ms; server SL stays active and new actions wait for a fresh tick",0);
      return;
   }
   datetime barTime=iTime(symbol,PERIOD_M5,0);
   double candleOpen=iOpen(symbol,PERIOD_M5,0);
   double candleHigh=iHigh(symbol,PERIOD_M5,0);
   double candleLow=iLow(symbol,PERIOD_M5,0);
   if(barTime<=0||candleOpen<=0.0||candleHigh<candleLow||candleLow<=0.0){
      powerPublishState(index,"M5_DATA_WAIT","current MT5 M5 OHLC is not ready",barTime);
      return;
   }
   bool hasPreviousTick=powerLastTickMs[index]>0;
   double previousBid=powerLastBid[index];
   powerManagePositions(index,tick,barTime,candleOpen,hasPreviousTick,previousBid);
   powerLastBid[index]=tick.bid;
   powerLastAsk[index]=tick.ask;
   powerLastTickMs[index]=tick.time_msc;

   // OnTick handles open positions only. Model loading, HTTP, cancellations and new
   // entry submission stay on the throttled timer path so the legacy order queue is isolated.
   if(powerFastTickOnly)return;
   bool configFresh=powerConfigFresh(index);
   if(!configFresh){
      powerCancelPendingForSymbol(symbol);
      powerPublishState(index,"CONFIG_STALE","No fresh /power_config heartbeat; new entries locked",barTime);
      return;
   }
   if(!powerCfg[index].enabled){
      powerCancelPendingForSymbol(symbol);
      powerPublishState(index,"OFF",powerCfg[index].reason==""?"POWERFULL disabled":powerCfg[index].reason,barTime);
      return;
   }

   bool newBar=(powerSeenBar[index]!=barTime);
   if(newBar){
      powerSeenBar[index]=barTime;
      powerLastSeedBar[index]=0;
      powerReplans[index]=0;
      powerReplacementPending[index]=false;
      powerCancelOnly[index]=false;
      powerForecastRangePips[index]=0.0;
      powerProjectedLow[index]=0.0;
      powerProjectedHigh[index]=0.0;
   }
   bool modelDue=!powerModelGood[index]||powerModelBuiltAtMs[index]==0||
      (uint)(nowMs-powerModelBuiltAtMs[index])>=15*60*1000;
   if(modelDue&&(powerLastModelAttemptMs[index]==0||
      (uint)(nowMs-powerLastModelAttemptMs[index])>=30000)){
      powerLastModelAttemptMs[index]=nowMs;
      if(!powerBuildModel(index)){
         powerCancelPendingForSymbol(symbol);
         powerPublishState(index,"HISTORY_NOT_READY","Need >= "+IntegerToString(POWERFULL_MIN_HISTORY_DAYS)+
                           " days and >= "+IntegerToString(POWERFULL_MIN_HISTORY)+
                           " valid M5 bars within last 365 days; loadedDays="+
                           IntegerToString(powerHistoryDays[index])+" samples="+
                           IntegerToString(powerHistorySamples[index]),barTime);
         return;
      }
   }
   if(!powerModelGood[index]){
      powerCancelPendingForSymbol(symbol);
      powerPublishState(index,"MODEL_NOT_READY","Historical M5 distribution is not ready",barTime);
      return;
   }
   if(newBar||powerForecastRangePips[index]<=0.0)
      powerForecastRangePips[index]=powerBaseRangePips[index];
   powerProjectBounds(index,candleOpen);

   powerCancelStalePending(symbol,barTime);
   int pending=powerPendingCount(symbol);
   int positions=powerPositionCount(symbol);
   double pip=powerCfg[index].pipSize;
   double realizedPips=(candleHigh-candleLow)/pip;

   // Re-evaluate both kinds of forecast error: range expansion beyond the
   // prediction, and a slow range that is no longer on pace to reach that prediction.
   // The under-pace projection is a conservative sqrt-time heuristic, recalculated
   // only after 60 seconds and only when observed range is materially below expected pace.
   double oldForecast=powerForecastRangePips[index];
   double revised=oldForecast;
   string reviseReason="";
   long elapsedMs=tick.time_msc-(long)barTime*1000;
   double elapsedSeconds=MathMax(0.0,MathMin(300.0,(double)elapsedMs/1000.0));
   double elapsedFraction=elapsedSeconds/300.0;
   if(realizedPips>oldForecast*1.15){
      revised=MathMax(oldForecast,realizedPips*1.20);
      reviseReason="RANGE_EXPANSION";
   }else if(elapsedSeconds>=60.0&&realizedPips>0.0&&elapsedFraction>0.0){
      double expectedSoFar=oldForecast*MathSqrt(elapsedFraction);
      if(realizedPips<expectedSoFar*0.60){
         double paceForecast=realizedPips/MathSqrt(MathMax(0.20,elapsedFraction));
         revised=MathMax(realizedPips*1.20,oldForecast*0.35+paceForecast*0.65);
         reviseReason="UNDER_PACE";
      }
   }
   bool materialRevision=oldForecast>0.0&&
      MathAbs(revised-oldForecast)>=oldForecast*0.12;
   if(materialRevision&&powerReplans[index]<POWERFULL_REPLAN_MAX){
      powerForecastRangePips[index]=revised;
      powerProjectBounds(index,candleOpen);
      // Delete only Powerfull magic orders, then verify the broker-side count
      // before allowing a replacement pair to enter the single order lifecycle.
      bool canceled=powerCancelPendingForSymbol(symbol);
      powerReplans[index]++;
      if(positions>0){
         powerLastSeedBar[index]=barTime;
         powerReplacementPending[index]=false;
         powerCancelOnly[index]=!canceled;
         powerPublishState(index,"DISTRIBUTION_REVISED_NO_REENTRY",reviseReason+
                           " | realized="+DoubleToString(realizedPips,2)+
                           " pip | open position remains managed; no new pair while exposure exists",barTime);
         return;
      }else if(!canceled){
         powerLastSeedBar[index]=0;
         powerReplacementPending[index]=true;
         powerCancelOnly[index]=false;
         powerPublishState(index,"CANCEL_PENDING","Forecast changed; replacement blocked until all old pending orders are confirmed removed",barTime);
         return;
      }else{
         powerLastSeedBar[index]=0;
         powerLastSeedAttemptMs[index]=0;
         powerReplacementPending[index]=true;
         powerCancelOnly[index]=false;
         powerPublishState(index,"DISTRIBUTION_REVISED",reviseReason+
                           " | realized="+DoubleToString(realizedPips,2)+
                           " pip | old="+DoubleToString(oldForecast,2)+
                           " pip | revised="+DoubleToString(revised,2)+
                           " pip | old pending count is zero; replacement permitted",barTime);
      }
      pending=powerPendingCount(symbol);
      if(pending>0){
         powerPublishState(index,"CANCEL_PENDING","Waiting until old pending orders are confirmed deleted",barTime);
         return;
      }
   }

   pending=powerPendingCount(symbol);
   positions=powerPositionCount(symbol);
   if(positions>0)powerLastSeedBar[index]=barTime; // exposure in this candle blocks reseeding after it closes
   if(powerReplacementPending[index]&&pending>0){
      // Retry failed/throttled deletes. Never treat old orders as the new pair.
      powerCancelPendingForSymbol(symbol);
      pending=powerPendingCount(symbol);
      if(pending>0){
         powerPublishState(index,"CANCEL_PENDING","Replacement waits for confirmed zero Powerfull pending orders",barTime);
         return;
      }
   }
   if(powerCancelOnly[index]){
      if(pending>0){
         powerCancelPendingForSymbol(symbol);
         pending=powerPendingCount(symbol);
         if(pending>0){
            powerPublishState(index,"CANCEL_PENDING","Partial/uncertain pair: retry cancellation; no replacement will be seeded",barTime);
            return;
         }
      }
      powerCancelOnly[index]=false;
      if(positions==0&&powerLastSeedBar[index]==barTime){
         powerPublishState(index,"PAIR_CLEANED_NO_RETRY","Uncertain/rejected pair is now cleared; no retry in this M5 candle",barTime);
         return;
      }
   }
   if(pending>0){
      if(powerHasPendingOnBar(symbol,barTime)){
         powerLastSeedBar[index]=barTime;
         powerPublishState(index,"PENDING_ACTIVE","Powerfull pending orders present in MT5; awaiting fill or forecast recheck",barTime);
      }else powerPublishState(index,"CANCEL_PENDING","Waiting for stale pending deletion",barTime);
      return;
   }
   if(positions>0){
      powerPublishState(index,"POSITION_ACTIVE","Open Powerfull position(s) are managed per tick; no new pair while exposure remains",barTime);
      return;
   }
   if(powerLastSeedBar[index]==barTime&&!powerReplacementPending[index]){
      powerPublishState(index,"CYCLE_COMPLETE","This M5 candle already had a pair; duplicate cycle blocked",barTime);
      return;
   }
   if(powerLastSeedAttemptMs[index]!=0&&(uint)(nowMs-powerLastSeedAttemptMs[index])<2000){
      powerPublishState(index,"ENTRY_RETRY_WAIT","Order validation cooldown",barTime);
      return;
   }
   powerLastSeedAttemptMs[index]=nowMs;
   powerPlacePair(index,barTime,tick);
}
