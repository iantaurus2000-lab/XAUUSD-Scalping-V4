#property strict
#include <Trade/Trade.mqh>
CTrade trade;
CTrade powerTrade; // isolated order manager: legacy CTrade state/magic remains untouched
#define RAYYAN_MAGIC 4104407

input string GatewayUrl="";
input string AllowedSymbol="XAUUSDm";
input bool DemoOnly=true;
// Standalone test mode: trades directly from MT5 ticks and never calls the APK Gateway.
input bool StandaloneD1Mode=true;
input string StandaloneSymbol="";
input double StandaloneLot=0.01;
input int StandaloneSLBuyPoints=0;
input int StandaloneSLSellPoints=0;
input int StandaloneD1TurnConfirmMs=500;
input int StandaloneMaxLayers=1;
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
// Last successfully delivered quote snapshot per symbol. Only changed ticks are sent.
long quoteLastPublishedTickMs[3];
double quoteLastPublishedBid[3], quoteLastPublishedAsk[3];
uint lastQuotePublishAttemptMs=0;
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
// Fail closed: no strategy means no technical indicators on the MT5 chart.
bool onEma50=false, onEma200=false, onSR=false, onFibo=false;
string indicatorStrategy="OFF";
string lastIndicatorCommandId="";
datetime lastIndicatorBar=0, lastIndicatorDiag=0;
#define RAYYAN_EMA_DRAW_BARS 140

#define POWERFULL_MAGIC 4104451
#define POWERFULL_HISTORY_LIMIT 105120
#define POWERFULL_CONFIG_TIMEOUT_MS 300
#define POWERFULL_STATUS_TIMEOUT_MS 100
#define POWERFULL_GATEWAY_MIN_GAP_MS 500
#define POWERFULL_STATUS_INTERVAL_MS 2000
#define POWERFULL_REPLAN_MAX 2

string powerSymbols[3]={"XAUUSDm","BTCUSDm","ETHUSDm"};
int powerAtrHandles[3]={INVALID_HANDLE,INVALID_HANDLE,INVALID_HANDLE};
struct PowerfullConfig {
   bool enabled;
   bool buyLimitEnabled;
   bool sellLimitEnabled;
   bool autoReforecast;
   bool quickProfitClose;
   bool crossCandleHold;
   double lot;
   double pipSize;
   double stopPips;
   double lotStep;
   int minDistancePoints;
   int deviationPoints;
   int slEntry1Points;
   int slEntry2Points;
   bool usePointStops;
   bool pendingLimitEnabled;
   int d1TurnConfirmMs;
   int maxLayers;
   double maxPendingDistanceATR;
   int minHoldCandles;
   double reversalATRFactor;
   int minProfitLockPoints;
   string reason;
};
PowerfullConfig powerCfg[3];
datetime powerSeenBar[3];
datetime powerLastSeedBar[3];
uint powerLastModelAttemptMs[3];
uint powerModelBuiltAtMs[3];
uint powerLastSeedAttemptMs[3];
uint powerLastManageMs[3];
uint powerMomentumCandidateMs[3];
int powerMomentumCandidateSide[3];
datetime powerMomentumCandidateBar[3];
long powerMomentumCandidateTickMs[3];
double powerMomentumCandidateExtreme[3];
datetime powerMomentumLayerBar[3];
double powerMomentumLastSellHigh[3];
double powerMomentumLastBuyLow[3];
// D1 keeps its own live-tick candle extremes; it does not wait for APK/chart uploads.
datetime powerD1FallbackBar[3];
double powerD1FallbackOpen[3],powerD1FallbackHigh[3],powerD1FallbackLow[3];
datetime powerMomentumLastEntryBar[3];
uint powerStatsCalcMs[3];
int powerStatsWins[3];
int powerStatsLosses[3];
int powerStatsClosed[3];
uint powerLastCancelAttemptMs[3];
uint powerLastDistanceCancelAttemptMs[3];
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
double powerUpperWickPips[3];
double powerLowerWickPips[3];
double powerTickPrices[3][32];
int powerTickWriteIndex[3];
int powerTickSampleCount[3];
double powerProjectedLow[3];
double powerProjectedHigh[3];
double powerLastBid[3];
double powerLastAsk[3];
long powerLastTickMs[3];
string powerLastStatus[3];
string powerLastDetail[3];
string powerSignalDetail[3];

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
bool powerCancelDistantPendings(int index,const MqlTick &tick);
bool powerHasPendingSide(string symbol,bool buySide);
bool powerPlacePendingSide(int index,bool buySide,datetime barTime,const MqlTick &tick,double target);
void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool hasPreviousTick,double previousBid);
void powerTryD1ExtremeEntry(int index,const MqlTick &tick,datetime barTime,double candleOpen,double candleHigh,double candleLow);
void powerClosedDealStats(string symbol,int &wins,int &losses,int &closed);
double powerAtrPrice(int index);
void powerRecordTick(int index,double mid);
double powerTickPriceAgo(int index,int ago);
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
   if(StandaloneD1Mode){
      string directSymbol=(StringLen(StandaloneSymbol)>0?StandaloneSymbol:_Symbol);
      powerSymbols[0]=directSymbol; powerSymbols[1]=""; powerSymbols[2]="";
      if(!SymbolSelect(directSymbol,true)){
         Print("D1 STANDALONE CONFIG ERROR: symbol unavailable: ",directSymbol);
         return(INIT_FAILED);
      }
      powerCfg[0].enabled=(StandaloneLot>0.0&&StandaloneSLBuyPoints>0&&StandaloneSLSellPoints>0
         &&StandaloneD1TurnConfirmMs>=250&&StandaloneD1TurnConfirmMs<=5000
         &&StandaloneMaxLayers>=1&&StandaloneMaxLayers<=3);
      powerCfg[0].lot=StandaloneLot;
      powerCfg[0].pipSize=SymbolInfoDouble(directSymbol,SYMBOL_POINT);
      powerCfg[0].lotStep=SymbolInfoDouble(directSymbol,SYMBOL_VOLUME_STEP);
      powerCfg[0].usePointStops=true;
      powerCfg[0].slEntry1Points=StandaloneSLBuyPoints;
      powerCfg[0].slEntry2Points=StandaloneSLSellPoints;
      powerCfg[0].d1TurnConfirmMs=StandaloneD1TurnConfirmMs;
      powerCfg[0].maxLayers=StandaloneMaxLayers;
      powerCfg[0].minProfitLockPoints=5;
      powerCfg[0].minHoldCandles=0;
      powerCfg[0].reason=(powerCfg[0].enabled?"STANDALONE_READY":"STANDALONE_CONFIG_INVALID");
      powerConfigReceivedMs[0]=1;
      Print("D1 STANDALONE MODE symbol=",directSymbol," lot=",DoubleToString(StandaloneLot,2),
         " SLbuy=",StandaloneSLBuyPoints," SLsell=",StandaloneSLSellPoints,
         " confirmMs=",StandaloneD1TurnConfirmMs," maxLayers=",StandaloneMaxLayers,
         " enabled=",powerCfg[0].enabled,
         " | BUY/SELL SL points must be positive before enabling Algo Trading");
   }else{
      for(int i=0;i<ArraySize(powerSymbols);i++){
         if(powerSymbols[i]=="")continue;
         if(SymbolSelect(powerSymbols[i],true))
            powerAtrHandles[i]=iATR(powerSymbols[i],PERIOD_M5,14);
         if(powerAtrHandles[i]==INVALID_HANDLE)
            Print("POWERFULL ATR HANDLE WARNING symbol=",powerSymbols[i]," error=",GetLastError());
      }
   }
   if(hEma50==INVALID_HANDLE || hEma200==INVALID_HANDLE)
      Print("RAYYAN4 INDICATOR WARNING EMA handles=",hEma50,",",hEma200);
   // Match Android CandleChartView exactly: bull RGB(34,197,94), bear RGB(239,68,68).
   ChartSetInteger(0,CHART_MODE,CHART_CANDLES);
   ChartSetInteger(0,CHART_COLOR_CANDLE_BULL,C'34,197,94');
   ChartSetInteger(0,CHART_COLOR_CANDLE_BEAR,C'239,68,68');
   ChartSetInteger(0,CHART_COLOR_CHART_UP,C'34,197,94');
   ChartSetInteger(0,CHART_COLOR_CHART_DOWN,C'239,68,68');
   ChartSetInteger(0,CHART_AUTOSCROLL,true);
   ChartSetInteger(0,CHART_SHIFT,false);
   ChartSetInteger(0,CHART_SCALE,4);
   applyAll();
   if(!EventSetMillisecondTimer(100)) EventSetTimer(MathMax(1,PollSeconds));
   if(StandaloneD1Mode) Print("RAYYAN D1 STANDALONE READY: no Gateway/WebRequest calls will be made.");
   else Print("RAYYAN4 START Gateway=",GatewayUrl," Symbol=",AllowedSymbol,
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
   for(int i=0;i<ArraySize(powerAtrHandles);i++){
      if(powerAtrHandles[i]!=INVALID_HANDLE){
         IndicatorRelease(powerAtrHandles[i]);
         powerAtrHandles[i]=INVALID_HANDLE;
      }
   }
}
void OnTick(){
   manageTP2RetraceProtection();
   // Opportunistic fast path; no gateway config request is issued from this event.
   managePowerfullAllMarkets(false);
}

void OnTimer(){
   if(StandaloneD1Mode){ managePowerfullAllMarkets(false); return; }
   // Fast quote path: publish changed MT5 ticks without waiting for the OHLC batch.
   // The publisher throttles requests and sends only symbols whose tick changed.
   uint nowMs=GetTickCount();
   publishMarketQuotes();
   // Countdown is display-only: refresh independently from trade/gateway housekeeping.
   updateChartCountdown();
   // Legacy gateway/chart work still runs only at PollSeconds cadence.
   uint legacyInterval=(uint)MathMax(1,PollSeconds)*1000;
   if(powerLastLegacyTimerMs==0 || (uint)(nowMs-powerLastLegacyTimerMs)>=legacyInterval){
      powerLastLegacyTimerMs=nowMs;
      manageTP2RetraceProtection();
      processTradeQueue();
      publishOrderState();
      publishMarketQuotes();
      publishNativeChartBars();
      processIndicatorQueue();
      // Screenshot mirroring disabled: native CopyRates OHLC is the chart source.
   }
   // POWERFULL still evaluates fresh ticks in OnTick; this timer only handles housekeeping/config.
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
   // TimeTradeServer is terminal-estimated between ticks; the display timer does not gate entries.
   datetime serverNow=TimeTradeServer();
   if(serverNow<=0)serverNow=TimeCurrent();
   int remain=(int)MathMax(0,periodSeconds-(serverNow-bar));
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
   // Timer runs every 100ms; this guard avoids excessive synchronous WebRequests.
   uint nowMs=GetTickCount();
   if(lastQuotePublishAttemptMs!=0 && (uint)(nowMs-lastQuotePublishAttemptMs)<100) return;

   string symbols[3]={"XAUUSDm","BTCUSDm","ETHUSDm"};
   bool included[3];
   string details[3];
   long candidateTickMs[3];
   double candidateBid[3],candidateAsk[3];
   for(int i=0;i<3;i++){
      included[i]=false; details[i]="";
      candidateTickMs[i]=0; candidateBid[i]=0; candidateAsk[i]=0;
   }
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
      // Avoid uploading unchanged snapshots; preserve broker precision and tick time.
      if(tick.time_msc==quoteLastPublishedTickMs[i]
         && tick.bid==quoteLastPublishedBid[i] && tick.ask==quoteLastPublishedAsk[i])
         continue;
      if(StringLen(batch)>0)batch+="\n";
      batch+="symbol="+sym+
         ";bid="+DoubleToString(tick.bid,digits)+
         ";ask="+DoubleToString(tick.ask,digits)+
         ";point="+DoubleToString(point,10)+
         ";digits="+IntegerToString(digits)+
         ";tickms="+(string)tick.time_msc;
      included[i]=true;
      candidateTickMs[i]=tick.time_msc;
      candidateBid[i]=tick.bid;
      candidateAsk[i]=tick.ask;
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
   lastQuotePublishAttemptMs=GetTickCount();
   ResetLastError();
   int code=WebRequest("POST",base+"/quote/upload",
      "Content-Type: text/plain; charset=utf-8\r\n",350,
      requestData,responseData,responseHeaders);
   string response=CharArrayToString(responseData,0,WHOLE_ARRAY,CP_UTF8);
   if(code!=200||StringFind(response,"QUOTE OK")!=0){
      for(int i=0;i<3;i++)if(included[i])
         quoteDiagnostic(i,symbols[i],"PUBLISH_FAILED",
            "tick batch http="+IntegerToString(code)+" err="+IntegerToString(GetLastError())+
            " response="+response);
   }else{
      for(int i=0;i<3;i++)if(included[i]){
         quoteLastPublishedTickMs[i]=candidateTickMs[i];
         quoteLastPublishedBid[i]=candidateBid[i];
         quoteLastPublishedAsk[i]=candidateAsk[i];
         quoteDiagnostic(i,symbols[i],"QUOTE_PUBLISHED",details[i]);
      }
   }
}

// Publish actual MT5 CopyRates OHLC to the APK-native chart; screenshots are not used as chart data.
// Publish OHLC for every APK market in one Gateway request. Chart selection in the APK
// must not depend on which symbol/timeframe happens to be open in the MT5 desktop.
void publishNativeChartBars(){
   uint now=GetTickCount();
   if(now-lastNativeBarsTick<1000) return;
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
         int count=50;
         if(appendBarsForTimeframe(sym,frames[j],names[j],count,batch))series++;
      }
   }
   if(series==0 || StringLen(batch)==0){
      if(TimeCurrent()-lastDiag>=30){
         lastDiag=TimeCurrent();
         Print("RAYYAN NATIVE BARS WAIT: CopyRates has not supplied enough history; check Market Watch symbols/history");
      }
      return;
   }
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
      "Content-Type: text/plain; charset=utf-8\r\n",5000,
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
   batch+="symbol="+symbol+";tf="+tfName+";bars="+bars+";serverTime="+IntegerToString((long)TimeCurrent()*1000);
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

         string requestedStrategy=field(cmd,"strategy");
         if(requestedStrategy!="STANDAR" && requestedStrategy!="EXCELLENT" &&
            requestedStrategy!="POWERFULL") requestedStrategy="OFF";
         indicatorStrategy=requestedStrategy;
         // Only STANDAR owns the EMA50/EMA200, SR and Fibonacci drawing layer.
         // EXCELLENT uses its own internal EMA20/50 + RSI/ATR calculations;
         // POWERFULL uses its distribution model and must not inherit classic overlays.
         onEma50=(indicatorStrategy=="STANDAR" && field(cmd,"ema50")=="1");
         onEma200=(indicatorStrategy=="STANDAR" && field(cmd,"ema200")=="1");
         onSR=(indicatorStrategy=="STANDAR" && field(cmd,"sr")=="1");
         onFibo=(indicatorStrategy=="STANDAR" && field(cmd,"fibo")=="1");
         applyAll();

         string ack="";
         if(indicatorGet("/indicator_ack?id="+id,ack) &&
            StringFind(ack,"INDICATOR ACK OK")>=0){
            lastIndicatorCommandId=id;
            Print("RAYYAN4 indicators ACK id=",id,
                  " strategy=",indicatorStrategy,
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
   double lot=StringToDouble(field(body,"lot"));
   double pip=StringToDouble(field(body,"pip"));
   double stop=StringToDouble(field(body,"stopPips"));
   string reason=field(body,"reason");
   bool requested=(field(body,"enabled")=="1");
   string buyFlag=field(body,"buyLimit");
   string sellFlag=field(body,"sellLimit");
   string reforecastFlag=field(body,"reforecast");
   string quickProfitFlag=field(body,"quickProfit");
   string crossCandleFlag=field(body,"crossCandleHold");
   string maxPendingAtrText=field(body,"maxPendingATR");
   string minHoldText=field(body,"minHoldCandles");
   string reversalAtrText=field(body,"reversalATRFactor");
   string minProfitLockText=field(body,"minProfitLockPoints");
   string lotStepText=field(body,"lotStep");
   string minDistanceText=field(body,"minDistancePoints");
   string deviationText=field(body,"deviationPoints");
   string slEntry1Text=field(body,"slEntry1Points");
   string slEntry2Text=field(body,"slEntry2Points");
   string pendingLimitFlag=field(body,"pendingLimit");
   string confirmText=field(body,"d1TurnConfirmMs");
   string maxLayersText=field(body,"maxLayers");
   bool pendingLimitEnabled=(pendingLimitFlag=="1");
   int d1TurnConfirmMs=(confirmText==""?500:(int)StringToInteger(confirmText));
   int maxLayers=(maxLayersText==""?3:(int)StringToInteger(maxLayersText));
   bool buyEnabled=(buyFlag==""||buyFlag=="1");
   bool sellEnabled=(sellFlag==""||sellFlag=="1");
   bool reforecastEnabled=(reforecastFlag==""||reforecastFlag=="1");
   bool quickProfitEnabled=(quickProfitFlag==""||quickProfitFlag=="1");
   bool crossCandleEnabled=(crossCandleFlag==""||crossCandleFlag=="1");
   double maxPendingAtr=(maxPendingAtrText==""?1.5:StringToDouble(maxPendingAtrText));
   int minHold=(minHoldText==""?2:(int)StringToInteger(minHoldText));
   double reversalAtr=(reversalAtrText==""?0.25:StringToDouble(reversalAtrText));
   int minProfitLock=(minProfitLockText==""?5:(int)StringToInteger(minProfitLockText));
   double lotStep=(lotStepText==""?0.01:StringToDouble(lotStepText));
   int minDistance=(minDistanceText==""?30:(int)StringToInteger(minDistanceText));
   int deviation=(deviationText==""?20:(int)StringToInteger(deviationText));
   int slEntry1=(slEntry1Text==""?-1:(int)StringToInteger(slEntry1Text));
   int slEntry2=(slEntry2Text==""?-1:(int)StringToInteger(slEntry2Text));
   bool usePointStops=(slEntry1Text!=""&&slEntry2Text!="");
   double minLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MIN);
   if(minLot<=0.0)minLot=(symbol=="XAUUSDm"?0.01:0.10);
   bool valid=MathIsValidNumber(lot)&&lot+1e-8>=minLot
      &&MathIsValidNumber(pip)&&pip>0.0
      &&MathIsValidNumber(stop)&&stop>=0.0
      &&MathIsValidNumber(lotStep)&&lotStep>0.0
      &&minDistance>=0&&minDistance<=100000
      &&deviation>=0&&deviation<=10000
      &&(!usePointStops||(slEntry1>=0&&slEntry2>=0))
      &&MathIsValidNumber(maxPendingAtr)&&maxPendingAtr>=0.5&&maxPendingAtr<=5.0
      &&minHold>=0&&minHold<=12
      &&MathIsValidNumber(reversalAtr)&&reversalAtr>=0.05&&reversalAtr<=2.0
      &&minProfitLock>=0&&minProfitLock<=100000
      &&d1TurnConfirmMs>=250&&d1TurnConfirmMs<=5000
      &&maxLayers>=1&&maxLayers<=3;
   powerCfg[index].buyLimitEnabled=buyEnabled;
   powerCfg[index].sellLimitEnabled=sellEnabled;
   powerCfg[index].autoReforecast=reforecastEnabled;
   powerCfg[index].quickProfitClose=quickProfitEnabled;
   powerCfg[index].crossCandleHold=crossCandleEnabled;
   powerCfg[index].maxPendingDistanceATR=maxPendingAtr;
   powerCfg[index].minHoldCandles=minHold;
   powerCfg[index].reversalATRFactor=reversalAtr;
   powerCfg[index].minProfitLockPoints=minProfitLock;
   powerCfg[index].lotStep=lotStep;
   powerCfg[index].minDistancePoints=minDistance;
   powerCfg[index].deviationPoints=deviation;
   powerCfg[index].slEntry1Points=(slEntry1<0?0:slEntry1);
   powerCfg[index].slEntry2Points=(slEntry2<0?0:slEntry2);
   powerCfg[index].usePointStops=usePointStops;
   powerCfg[index].pendingLimitEnabled=pendingLimitEnabled;
   powerCfg[index].d1TurnConfirmMs=d1TurnConfirmMs;
   powerCfg[index].maxLayers=maxLayers;
   powerCfg[index].enabled=requested&&valid;
   powerCfg[index].lot=lot;
   powerCfg[index].pipSize=pip;
   powerCfg[index].stopPips=stop;
   powerCfg[index].reason=(reason!=""?reason:(!requested?"DISABLED":(valid?"READY":"CONFIG_INVALID")));
   powerConfigReceivedMs[index]=GetTickCount();
   if(valid&&MathAbs(pip-oldPipSize)>1e-12){
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
   int copied=CopyRates(symbol,PERIOD_M5,1,5,rates);
   if(copied<3){
      powerHistorySamples[index]=MathMax(0,copied);
      powerHistoryDays[index]=0;
      Print("POWERFULL FORECAST WAIT symbol=",symbol," completedM5=",copied," required=3 err=",GetLastError());
      return false;
   }
   double rangeSum=0.0;
   int used=0;
   for(int i=0;i<copied&&used<5;i++){
      double h=rates[i].high,l=rates[i].low,o=rates[i].open,cl=rates[i].close;
      if(rates[i].time<=0||h<=0.0||l<=0.0||h<l||o<=0.0||cl<=0.0)continue;
      double candleRange=h-l;
      if(!MathIsValidNumber(candleRange)||candleRange<=0.0)continue;
      rangeSum+=candleRange;
      used++;
   }
   powerHistorySamples[index]=used;
   powerHistoryDays[index]=0;
   if(used<3)return false;
   double projectedRange=rangeSum/(double)used;
   if(!MathIsValidNumber(projectedRange)||projectedRange<=0.0)return false;
   powerBaseRangePips[index]=projectedRange/pip;
   powerForecastRangePips[index]=projectedRange/pip;
   powerUpperWickPips[index]=(projectedRange*0.5)/pip;
   powerLowerWickPips[index]=(projectedRange*0.5)/pip;
   powerUpperShare[index]=0.50;
   powerModelGood[index]=true;
   powerModelBuiltAtMs[index]=GetTickCount();
   Print("POWERFULL SIMPLE M5 FORECAST symbol=",symbol," completedBars=",used,
         " averageRange=",DoubleToString(projectedRange,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
         " centeredOnCurrentM5Open=YES");
   return true;
}
void powerProjectBounds(int index,double candleAnchor){
   if(index<0||index>=ArraySize(powerSymbols))return;
   double pip=powerCfg[index].pipSize;
   double range=powerForecastRangePips[index]*pip;
   if(pip<=0.0||candleAnchor<=0.0||range<=0.0)return;
   double projectedLow=candleAnchor-range*0.5;
   powerProjectedLow[index]=projectedLow+range*0.001;
   powerProjectedHigh[index]=projectedLow+range*0.989;
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
      if(type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_SELL_LIMIT||
         type==ORDER_TYPE_BUY_STOP||type==ORDER_TYPE_SELL_STOP)count++;
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
      bool pending=(type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_SELL_LIMIT||
                    type==ORDER_TYPE_BUY_STOP||type==ORDER_TYPE_SELL_STOP);
      if(!pending)continue;
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
bool powerHasDisabledPending(string symbol,bool allowBuy,bool allowSell){
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(((type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_BUY_STOP)&&!allowBuy)||
         ((type==ORDER_TYPE_SELL_LIMIT||type==ORDER_TYPE_SELL_STOP)&&!allowSell))
         return true;
   }
   return false;
}

bool powerCancelDisabledPending(string symbol,bool allowBuy,bool allowSell){
   int symbolIndex=-1;
   for(int s=0;s<ArraySize(powerSymbols);s++)if(powerSymbols[s]==symbol){symbolIndex=s;break;}
   uint nowMs=GetTickCount();
   if(symbolIndex>=0&&powerLastCancelAttemptMs[symbolIndex]!=0&&
      (uint)(nowMs-powerLastCancelAttemptMs[symbolIndex])<300)
      return !powerHasDisabledPending(symbol,allowBuy,allowSell);
   if(symbolIndex>=0)powerLastCancelAttemptMs[symbolIndex]=nowMs;
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(!(((type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_BUY_STOP)&&!allowBuy)||
           ((type==ORDER_TYPE_SELL_LIMIT||type==ORDER_TYPE_SELL_STOP)&&!allowSell)))continue;
      ResetLastError();
      bool deleted=powerTrade.OrderDelete(ticket);
      uint rc=powerTrade.ResultRetcode();
      if(!deleted||rc!=TRADE_RETCODE_DONE)
         Print("POWERFULL DISABLED-SIDE CANCEL FAILED symbol=",symbol," ticket=",ticket,
               " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
      else Print("POWERFULL DISABLED-SIDE CANCEL OK symbol=",symbol," ticket=",ticket);
   }
   return !powerHasDisabledPending(symbol,allowBuy,allowSell);
}

bool powerHasPendingSide(string symbol,bool buySide){
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      bool existingBuy=(type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_BUY_STOP);
      bool pending=(type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_BUY_STOP||
                    type==ORDER_TYPE_SELL_LIMIT||type==ORDER_TYPE_SELL_STOP);
      if(pending&&existingBuy==buySide)return true;
   }
   return false;
}

bool powerCancelDistantPendings(int index,const MqlTick &tick){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   string symbol=powerSymbols[index];
   uint nowMs=GetTickCount();
   if(powerLastDistanceCancelAttemptMs[index]!=0&&
      (uint)(nowMs-powerLastDistanceCancelAttemptMs[index])<250)
      return true;
   powerLastDistanceCancelAttemptMs[index]=nowMs;
   double atr=powerAtrPrice(index);
   if(atr<=0.0)atr=powerForecastRangePips[index]*powerCfg[index].pipSize;
   if(atr<=0.0||powerCfg[index].maxPendingDistanceATR<=0.0)return true;
   double maxDistance=atr*powerCfg[index].maxPendingDistanceATR;
   bool allSafe=true;
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   for(int i=OrdersTotal()-1;i>=0;i--){
      ulong ticket=OrderGetTicket(i);
      if(ticket==0)continue;
      if(OrderGetString(ORDER_SYMBOL)!=symbol)continue;
      if((long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      bool buy=(type==ORDER_TYPE_BUY_LIMIT||type==ORDER_TYPE_BUY_STOP);
      bool sell=(type==ORDER_TYPE_SELL_LIMIT||type==ORDER_TYPE_SELL_STOP);
      if(!buy&&!sell)continue;
      double orderPrice=OrderGetDouble(ORDER_PRICE_OPEN);
      double marketPrice=(buy?tick.ask:tick.bid);
      double distance=MathAbs(orderPrice-marketPrice);
      if(distance<=maxDistance)continue;
      ResetLastError();
      bool deleted=powerTrade.OrderDelete(ticket);
      uint rc=powerTrade.ResultRetcode();
      if(!deleted||rc!=TRADE_RETCODE_DONE){
         allSafe=false;
         Print("POWERFULL DISTANT PENDING CANCEL FAILED symbol=",symbol," ticket=",ticket,
               " distancePts=",DoubleToString(distance/SymbolInfoDouble(symbol,SYMBOL_POINT),1),
               " maxATR=",DoubleToString(powerCfg[index].maxPendingDistanceATR,2),
               " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
      }else{
         Print("POWERFULL DISTANT PENDING CANCEL OK symbol=",symbol," ticket=",ticket,
               " distancePts=",DoubleToString(distance/SymbolInfoDouble(symbol,SYMBOL_POINT),1),
               " maxATR=",DoubleToString(powerCfg[index].maxPendingDistanceATR,2));
      }
   }
   return allSafe;
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

void powerRecordTick(int index,double mid){
   if(index<0||index>=ArraySize(powerSymbols)||mid<=0.0||!MathIsValidNumber(mid))return;
   int write=powerTickWriteIndex[index];
   powerTickPrices[index][write]=mid;
   powerTickWriteIndex[index]=(write+1)%32;
   powerTickSampleCount[index]=(int)MathMin(32,powerTickSampleCount[index]+1);
}

double powerTickPriceAgo(int index,int ago){
   if(index<0||index>=ArraySize(powerSymbols)||ago<0||ago>=powerTickSampleCount[index])return 0.0;
   int idx=powerTickWriteIndex[index]-1-ago;
   while(idx<0)idx+=32;
   return powerTickPrices[index][idx];
}

double powerAtrPrice(int index){
   if(index<0||index>=ArraySize(powerSymbols))return 0.0;
   if(powerAtrHandles[index]==INVALID_HANDLE){
      if(!SymbolSelect(powerSymbols[index],true))return 0.0;
      powerAtrHandles[index]=iATR(powerSymbols[index],PERIOD_M5,14);
      if(powerAtrHandles[index]==INVALID_HANDLE)return 0.0;
   }
   double buffer[1];
   if(CopyBuffer(powerAtrHandles[index],0,1,1,buffer)!=1)return 0.0;
   if(!MathIsValidNumber(buffer[0])||buffer[0]<=0.0)return 0.0;
   return buffer[0];
}

bool powerPlacePendingSide(int index,bool buySide,datetime barTime,const MqlTick &tick,double target){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   string symbol=powerSymbols[index];
   double pip=powerCfg[index].pipSize,lot=powerCfg[index].lot,stopPips=powerCfg[index].stopPips;
   if(!powerModelGood[index]||pip<=0.0||powerForecastRangePips[index]<=0.0||target<=0.0)return false;
   if(powerHasPendingSide(symbol,buySide))return true;
   ENUM_ACCOUNT_MARGIN_MODE marginMode=(ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE);
   bool hedgeAllowed=(AccountInfoInteger(ACCOUNT_HEDGE_ALLOWED)!=0);
   if(marginMode!=ACCOUNT_MARGIN_MODE_RETAIL_HEDGING||!hedgeAllowed){
      powerPublishState(index,"BLOCKED_NETTING_ACCOUNT",
         "POWERFULL requires MT5 hedging mode with opposite positions allowed; legacy order paths untouched",barTime);
      return false;
   }
   if(!TerminalInfoInteger(TERMINAL_CONNECTED)||!TerminalInfoInteger(TERMINAL_TRADE_ALLOWED)
      ||!MQLInfoInteger(MQL_TRADE_ALLOWED)||!AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)){
      powerPublishState(index,"BLOCKED_TRADING_DISABLED","Terminal/account trading permission unavailable",barTime);
      return false;
   }
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   double tickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   double stopPoints=(double)SymbolInfoInteger(symbol,SYMBOL_TRADE_STOPS_LEVEL);
   double brokerMin=stopPoints*point;
   if(point<=0.0||tickSize<=0.0){
      powerPublishState(index,"BLOCKED_SYMBOL_SPEC","SYMBOL_POINT/TICK_SIZE invalid",barTime);
      return false;
   }
   // POWERFULL only places LIMIT orders; it never converts a level to STOP.
   double gap=MathMax((double)powerCfg[index].minDistancePoints,stopPoints+1.0)*point;
   ENUM_ORDER_TYPE type=(buySide?ORDER_TYPE_BUY_LIMIT:ORDER_TYPE_SELL_LIMIT);
   double entry=(buySide?powerFloorToTick(symbol,target):powerCeilToTick(symbol,target));
   if(entry<=0.0||(buySide&&entry>=tick.ask-gap)||(!buySide&&entry<=tick.bid+gap)){
      powerPublishState(index,"BLOCKED_ENTRY_GEOMETRY","Projected LIMIT level crossed or inside broker minimum distance",barTime);
      return false;
   }
   int configuredSLPoints=(buySide?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
   double stopDistance=(powerCfg[index].usePointStops?(double)configuredSLPoints*point:stopPips*pip);
   if(stopDistance>0.0&&stopDistance+1e-10<brokerMin+tickSize){
      powerPublishState(index,"BLOCKED_SL_TOO_CLOSE","Broker stop level exceeds configured POWERFULL loss guard",barTime);
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
   double sl=0.0;
   if(stopDistance>0.0)sl=(buySide?powerFloorToTick(symbol,entry-stopDistance):powerCeilToTick(symbol,entry+stopDistance));
   datetime expiry=barTime+PeriodSeconds(PERIOD_M5);
   if(expiry<=TimeCurrent()){
      powerPublishState(index,"BLOCKED_EXPIRY","M5 candle expired before LIMIT placement",barTime);
      return false;
   }
   string comment="PWR-"+powerShortSymbol(symbol)+"-"+IntegerToString((long)barTime)+(buySide?"-B":"-S");
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   powerTrade.SetDeviationInPoints(powerCfg[index].deviationPoints);
   powerTrade.SetTypeFillingBySymbol(symbol);
   powerTrade.SetAsyncMode(false);
   ResetLastError();
   bool ok=false;
   if(type==ORDER_TYPE_BUY_LIMIT)
      ok=powerTrade.BuyLimit(lot,entry,symbol,sl,0.0,ORDER_TIME_SPECIFIED,expiry,comment);
   else
      ok=powerTrade.SellLimit(lot,entry,symbol,sl,0.0,ORDER_TIME_SPECIFIED,expiry,comment);
   uint rc=powerTrade.ResultRetcode();
   if(!ok||(rc!=TRADE_RETCODE_PLACED&&rc!=TRADE_RETCODE_DONE)){
      powerPublishState(index,"PENDING_REJECTED",EnumToString(type)+" retcode="+IntegerToString((int)rc)+
         " "+powerTrade.ResultRetcodeDescription(),barTime);
      Print("POWERFULL PENDING FAILED symbol=",symbol," type=",EnumToString(type),
         " entry=",DoubleToString(entry,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
         " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
      return false;
   }
   powerLastSeedBar[index]=barTime;
   Print("POWERFULL PENDING PLACED symbol=",symbol," side=",EnumToString(type),
      " entry=",DoubleToString(entry,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
      " lot=",DoubleToString(lot,2)," SL=",DoubleToString(sl,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
      " TP=OFF expiry=",TimeToString(expiry,TIME_DATE|TIME_MINUTES));
   powerPublishState(index,"PENDING_PLACED",EnumToString(type)+" at projected range level; expires at M5 close",barTime);
   return true;
}
void powerTryD1ExtremeEntry(int index,const MqlTick &tick,datetime barTime,double candleOpen,double candleHigh,double candleLow){
   string symbol=powerSymbols[index];
   uint now=GetTickCount();
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   double tickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   double spread=tick.ask-tick.bid;
   if(point<=0.0||tickSize<=0.0||spread<0.0){
      powerPublishState(index,"D1_WAIT_DATA","D1 waiting for valid MT5 point/tick-size/quote",barTime);
      return;
   }
   if(powerMomentumLayerBar[index]!=barTime){
      powerMomentumLayerBar[index]=barTime;
      powerMomentumLastSellHigh[index]=0.0;
      powerMomentumLastBuyLow[index]=0.0;
      powerMomentumCandidateMs[index]=0;
      powerMomentumCandidateSide[index]=0;
      powerMomentumCandidateTickMs[index]=0;
      powerMomentumCandidateExtreme[index]=0.0;
   }
   if(!powerCfg[index].usePointStops){
      powerMomentumCandidateMs[index]=0;
      powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"BLOCKED_SL_POINTS","D1 requires valid BUY and SELL SL points; no entry without loss protection",barTime);
      return;
   }
   ENUM_ACCOUNT_MARGIN_MODE marginMode=(ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE);
   if((marginMode!=ACCOUNT_MARGIN_MODE_RETAIL_HEDGING||AccountInfoInteger(ACCOUNT_HEDGE_ALLOWED)==0)&&powerCfg[index].maxLayers>1){
      powerPublishState(index,"BLOCKED_NETTING_ACCOUNT","D1 layered entries require MT5 hedging mode",barTime);
      return;
   }
   int totalLayers=0,buyLayers=0,sellLayers=0;
   for(int p=PositionsTotal()-1;p>=0;p--){
      ulong ticket=PositionGetTicket(p);
      if(ticket==0||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      totalLayers++;
      ENUM_POSITION_TYPE positionSide=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      if(positionSide==POSITION_TYPE_BUY)buyLayers++;
      else if(positionSide==POSITION_TYPE_SELL)sellLayers++;
   }
   int maxLayers=MathMax(1,MathMin(3,powerCfg[index].maxLayers));
   if(totalLayers>=maxLayers){
      powerMomentumCandidateMs[index]=0;
      powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"D1_LAYER_CAP","D1 layer cap reached "+IntegerToString(totalLayers)+"/"+IntegerToString(maxLayers),barTime);
      return;
   }

   double nearDistance=MathMax(tickSize*3.0,MathMax(spread*1.5,point*3.0));
   double p0=powerTickPriceAgo(index,0);
   double p1=powerTickPriceAgo(index,1);
   double p2=powerTickPriceAgo(index,2);
   if(p0<=0.0||p1<=0.0||p2<=0.0){
      powerMomentumCandidateMs[index]=0;
      powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"D1_TICK_WARMUP","D1 collecting live MT5 ticks; no previous candle history required",barTime);
      return;
   }
   bool nearHigh=(candleHigh>=tick.bid&&candleHigh-tick.bid<=nearDistance);
   bool nearLow=(tick.bid>=candleLow&&tick.bid-candleLow<=nearDistance);
   bool freshSellExtreme=(powerMomentumLastSellHigh[index]<=0.0||candleHigh>=powerMomentumLastSellHigh[index]+tickSize*0.5);
   bool freshBuyExtreme=(powerMomentumLastBuyLow[index]<=0.0||candleLow<=powerMomentumLastBuyLow[index]-tickSize*0.5);
   int side=powerMomentumCandidateSide[index];

   // Once a turning tick appears, keep the candidate alive while the same extreme
   // holds. A continuing reversal tick need not recreate the exact turning pattern.
   if(side<0){
      if(candleHigh>powerMomentumCandidateExtreme[index]+tickSize*0.5||
         candleHigh-tick.bid>nearDistance*1.5||!freshSellExtreme||sellLayers>=maxLayers){
         side=0;
      }
   }else if(side>0){
      if(candleLow<powerMomentumCandidateExtreme[index]-tickSize*0.5||
         tick.bid-candleLow>nearDistance*1.5||!freshBuyExtreme||buyLayers>=maxLayers){
         side=0;
      }
   }
   if(side==0){
      powerMomentumCandidateMs[index]=0;
      powerMomentumCandidateSide[index]=0;
      powerMomentumCandidateTickMs[index]=0;
      powerMomentumCandidateExtreme[index]=0.0;
      bool sellTurn=nearHigh&&freshSellExtreme&&sellLayers<maxLayers&&p1>p2&&p0<p1;
      bool buyTurn=nearLow&&freshBuyExtreme&&buyLayers<maxLayers&&p1<p2&&p0>p1;
      if(sellTurn)side=-1;
      else if(buyTurn)side=1;
      if(side==0){
         powerPublishState(index,"D1_EXTREME_SCAN",
            "D1 live tick scan | High="+DoubleToString(candleHigh,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS))+
            " Low="+DoubleToString(candleLow,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS))+
            " | layers="+IntegerToString(totalLayers)+"/"+IntegerToString(maxLayers)+
            " | waiting for a turn near a fresh extreme",barTime);
         return;
      }
      powerMomentumCandidateBar[index]=barTime;
      powerMomentumCandidateSide[index]=side;
      powerMomentumCandidateMs[index]=now;
      powerMomentumCandidateTickMs[index]=tick.time_msc;
      powerMomentumCandidateExtreme[index]=(side<0?candleHigh:candleLow);
      powerPublishState(index,"D1_EXTREME_CONFIRM",
         (side>0?"BUY near candle Low":"SELL near candle High")+
         "; confirming live tick turn for "+IntegerToString(powerCfg[index].d1TurnConfirmMs)+" ms",barTime);
      return;
   }
   if(powerMomentumCandidateSide[index]!=side||powerMomentumCandidateBar[index]!=barTime)return;
   if(tick.time_msc<=powerMomentumCandidateTickMs[index])return;
   powerMomentumCandidateTickMs[index]=tick.time_msc;
   if((uint)(now-powerMomentumCandidateMs[index])<(uint)powerCfg[index].d1TurnConfirmMs)return;

   int slPoints=(side>0?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
   long stopLevel=SymbolInfoInteger(symbol,SYMBOL_TRADE_STOPS_LEVEL);
   double slDistance=(double)slPoints*point;
   double minStop=(double)stopLevel*point;
   if(slPoints<=0||slDistance+1e-10<minStop+tickSize){
      powerMomentumCandidateMs[index]=0;powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"BLOCKED_SL_POINTS","D1 SL "+IntegerToString(slPoints)+" points is zero or below broker stop distance",barTime);
      return;
   }
   double spreadPoints=spread/point;
   if(spreadPoints>(double)MathMax(20,powerCfg[index].minDistancePoints*2)){
      powerMomentumCandidateMs[index]=0;powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"BLOCKED_SPREAD","D1 spread="+DoubleToString(spreadPoints,1)+" points exceeds safety threshold",barTime);
      return;
   }
   double lot=powerCfg[index].lot;
   double minLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MIN);
   double maxLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MAX);
   double lotStep=SymbolInfoDouble(symbol,SYMBOL_VOLUME_STEP);
   if(lotStep<=0.0||lot<minLot-1e-8||lot>maxLot+1e-8){
      powerMomentumCandidateMs[index]=0;powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"BLOCKED_LOT","D1 configured lot outside broker volume limits",barTime);
      return;
   }
   totalLayers=0;
   for(int p=PositionsTotal()-1;p>=0;p--){
      ulong ticket=PositionGetTicket(p);
      if(ticket!=0&&PositionGetString(POSITION_SYMBOL)==symbol&&
         (long)PositionGetInteger(POSITION_MAGIC)==POWERFULL_MAGIC)totalLayers++;
   }
   if(totalLayers>=maxLayers){
      powerMomentumCandidateMs[index]=0;powerMomentumCandidateSide[index]=0;
      powerPublishState(index,"D1_LAYER_CAP","Layer cap reached during confirmation",barTime);
      return;
   }
   double entry=(side>0?tick.ask:tick.bid);
   double sl=powerRoundToTick(symbol,side>0?entry-slDistance:entry+slDistance);
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   powerTrade.SetDeviationInPoints(powerCfg[index].deviationPoints);
   powerTrade.SetTypeFillingBySymbol(symbol);
   powerTrade.SetAsyncMode(false);
   ResetLastError();
   bool sent=(side>0
      ?powerTrade.Buy(lot,symbol,0.0,sl,0.0,"D1-HUMAN-EXTREME-BUY")
      :powerTrade.Sell(lot,symbol,0.0,sl,0.0,"D1-HUMAN-EXTREME-SELL"));
   uint rc=powerTrade.ResultRetcode();
   powerMomentumCandidateMs[index]=0;powerMomentumCandidateSide[index]=0;powerMomentumCandidateTickMs[index]=0;
   if(sent&&(rc==TRADE_RETCODE_DONE||rc==TRADE_RETCODE_PLACED||rc==TRADE_RETCODE_DONE_PARTIAL)){
      if(side>0)powerMomentumLastBuyLow[index]=candleLow;
      else powerMomentumLastSellHigh[index]=candleHigh;
      Print("POWERFULL D1 HUMAN EXTREME ENTRY OK symbol=",symbol," side=",(side>0?"BUY":"SELL"),
         " lot=",DoubleToString(lot,2)," extreme=",DoubleToString((side>0?candleLow:candleHigh),(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
         " layers=",IntegerToString(totalLayers+1),"/",IntegerToString(maxLayers)," SLpoints=",slPoints," retcode=",rc);
      powerMomentumLastEntryBar[index]=barTime;
      powerPublishState(index,"D1_EXTREME_ENTRY_SENT",
         string(side>0?"BUY near live candle Low":"SELL near live candle High")+
         " | layers="+IntegerToString(totalLayers+1)+"/"+IntegerToString(maxLayers)+
         " | SL="+IntegerToString(slPoints)+" points",barTime);
   }else{
      Print("POWERFULL D1 HUMAN EXTREME ENTRY FAILED symbol=",symbol," side=",(side>0?"BUY":"SELL"),
         " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
      powerPublishState(index,"D1_EXTREME_ENTRY_FAILED","Broker rejected D1 market entry: "+powerTrade.ResultRetcodeDescription(),barTime);
   }
}

void powerClosedDealStats(string symbol,int &wins,int &losses,int &closed){
   int idx=-1;for(int k=0;k<ArraySize(powerSymbols);k++)if(powerSymbols[k]==symbol){idx=k;break;}
   if(idx>=0&&powerStatsCalcMs[idx]>0&&(uint)(GetTickCount()-powerStatsCalcMs[idx])<30000){
      wins=powerStatsWins[idx];losses=powerStatsLosses[idx];closed=powerStatsClosed[idx];return;
   }
   wins=0;losses=0;closed=0;
   datetime to=TimeCurrent();
   datetime from=to-30*86400;
   if(!HistorySelect(from,to))return;
   for(int i=HistoryDealsTotal()-1;i>=0;i--){
      ulong deal=HistoryDealGetTicket(i);
      if(deal==0)continue;
      if((long)HistoryDealGetInteger(deal,DEAL_MAGIC)!=POWERFULL_MAGIC)continue;
      if(HistoryDealGetString(deal,DEAL_SYMBOL)!=symbol)continue;
      ENUM_DEAL_ENTRY entry=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(deal,DEAL_ENTRY);
      if(entry!=DEAL_ENTRY_OUT&&entry!=DEAL_ENTRY_OUT_BY)continue;
      double net=HistoryDealGetDouble(deal,DEAL_PROFIT)+HistoryDealGetDouble(deal,DEAL_SWAP)+
                 HistoryDealGetDouble(deal,DEAL_COMMISSION)+HistoryDealGetDouble(deal,DEAL_FEE);
      if(net>0.0)wins++;
      else if(net<0.0)losses++;
   }
   closed=wins+losses;
   if(idx>=0){powerStatsWins[idx]=wins;powerStatsLosses[idx]=losses;powerStatsClosed[idx]=closed;powerStatsCalcMs[idx]=GetTickCount();}
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
      string peakKey="PW_PEAK_"+suffix;
      if(GlobalVariableCheck(key))GlobalVariableDel(key);
      if(GlobalVariableCheck(barKey))GlobalVariableDel(barKey);
      if(GlobalVariableCheck(peakKey))GlobalVariableDel(peakKey);
      return true;
   }
   Print("POWERFULL CLOSE FAILED symbol=",symbol," ticket=",ticket," reason=",reason,
         " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
   return false;
}

void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool hasPreviousTick,double previousBid){
 if(index<0||index>=ArraySize(powerSymbols))return;
 string symbol=powerSymbols[index];double point=SymbolInfoDouble(symbol,SYMBOL_POINT),tickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE),spread=tick.ask-tick.bid;
 if(point<=0.0||tickSize<=0.0||spread<0.0)return;
 double minLock=MathMax((double)powerCfg[index].minProfitLockPoints*point,spread*1.5);
 double trail=MathMax(minLock,MathMax(spread*2.0,tickSize*3.0));
 for(int i=PositionsTotal()-1;i>=0;i--){
  ulong ticket=PositionGetTicket(i);if(ticket==0||PositionGetString(POSITION_SYMBOL)!=symbol||(long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
  if(!PositionSelectByTicket(ticket))continue;
  ENUM_POSITION_TYPE side=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);double entry=PositionGetDouble(POSITION_PRICE_OPEN),executable=(side==POSITION_TYPE_BUY?tick.bid:tick.ask),net=powerProjectedNet(ticket);
  int slPoints=(side==POSITION_TYPE_BUY?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
  double hardStop=(powerCfg[index].usePointStops?(double)slPoints*point:powerCfg[index].stopPips*powerCfg[index].pipSize);
  double adverse=(side==POSITION_TYPE_BUY?entry-executable:executable-entry);
  if(hardStop>0.0&&adverse+point*0.1>=hardStop){powerClosePosition(ticket,"HARD_LOSS_GUARD configuredDistance="+DoubleToString(hardStop/point,1)+" points");continue;}
  string peakKey="PW_PEAK_"+IntegerToString((long)ticket);if(!GlobalVariableCheck(peakKey))GlobalVariableSet(peakKey,executable);
  double peak=GlobalVariableGet(peakKey);if((side==POSITION_TYPE_BUY&&executable>peak)||(side==POSITION_TYPE_SELL&&executable<peak)){peak=executable;GlobalVariableSet(peakKey,peak);}
  double retrace=(side==POSITION_TYPE_BUY?peak-executable:executable-peak),locked=(side==POSITION_TYPE_BUY?executable-entry:entry-executable);
  if(net>0.0&&locked>=minLock&&retrace>=trail)powerClosePosition(ticket,"D1_TICK_PROFIT_LOCK retrace="+DoubleToString(retrace/point,1)+" points threshold="+DoubleToString(trail/point,1)+" points");
 }
}

void powerPublishState(int index,string status,string detail,datetime barTime){
   uint nowMs=GetTickCount();
   if(StandaloneD1Mode){
      if(status!=powerLastStatus[index]||detail!=powerLastDetail[index]||powerLastStatePublishMs[index]==0||
         (uint)(nowMs-powerLastStatePublishMs[index])>=POWERFULL_STATUS_INTERVAL_MS){
         Print("D1 STANDALONE ",powerSymbols[index]," status=",status," detail=",detail);
         powerLastStatus[index]=status; powerLastDetail[index]=detail; powerLastStatePublishMs[index]=nowMs;
      }
      return;
   }
   if(!powerAllowGatewayCalls){
      if(StringFind(status,"BLOCKED_")==0&&(status!=powerLastStatus[index]||detail!=powerLastDetail[index])&&
         (powerLastStatePublishMs[index]==0||(uint)(nowMs-powerLastStatePublishMs[index])>=POWERFULL_STATUS_INTERVAL_MS)){
         Print("POWERFULL BLOCK ",powerSymbols[index]," status=",status," detail=",detail);
         powerLastStatus[index]=status; powerLastDetail[index]=detail; powerLastStatePublishMs[index]=nowMs;
      }
      return; // never perform a synchronous HTTP request inside OnTick
   }
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
   // Publish the actual broker-confirmed POWERFULL order/position so the APK
   // can draw its own entry line. The legacy /state channel intentionally excludes
   // POWERFULL_MAGIC and must remain independent.
   string orderKind="NONE",orderSide="",orderTicket="",orderEntry="0";
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0)continue;
      if(PositionGetString(POSITION_SYMBOL)!=symbol ||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      ENUM_POSITION_TYPE side=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      orderKind="POSITION";
      orderSide=(side==POSITION_TYPE_BUY?"BUY":"SELL");
      orderTicket=(string)ticket;
      orderEntry=DoubleToString(PositionGetDouble(POSITION_PRICE_OPEN),
                                (int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
      break;
   }
   if(orderKind=="NONE"){
      for(int i=OrdersTotal()-1;i>=0;i--){
         ulong ticket=OrderGetTicket(i);
         if(ticket==0)continue;
         if(OrderGetString(ORDER_SYMBOL)!=symbol ||
            (long)OrderGetInteger(ORDER_MAGIC)!=POWERFULL_MAGIC)continue;
         ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
         if(type!=ORDER_TYPE_BUY_LIMIT&&type!=ORDER_TYPE_SELL_LIMIT&&
            type!=ORDER_TYPE_BUY_STOP&&type!=ORDER_TYPE_SELL_STOP)continue;
         orderKind="PENDING";
         orderSide=(type==ORDER_TYPE_BUY_LIMIT?"BUY_LIMIT":
                    type==ORDER_TYPE_SELL_LIMIT?"SELL_LIMIT":
                    type==ORDER_TYPE_BUY_STOP?"BUY_STOP":"SELL_STOP");
         orderTicket=(string)ticket;
         orderEntry=DoubleToString(OrderGetDouble(ORDER_PRICE_OPEN),
                                   (int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
         break;
      }
   }
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
      +"&orderKind="+urlEncode(orderKind)
      +"&orderSide="+urlEncode(orderSide)
      +"&orderTicket="+urlEncode(orderTicket)
      +"&orderEntry="+urlEncode(orderEntry)
      +"&detail="+urlEncode(detail);
   int closedWins=0,closedLosses=0,closedDeals=0;
   powerClosedDealStats(symbol,closedWins,closedLosses,closedDeals);
   path+="&wins="+IntegerToString(closedWins)+"&losses="+IntegerToString(closedLosses)+
         "&closedDeals="+IntegerToString(closedDeals);
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
   if(index<0||index>=ArraySize(powerSymbols)||powerSymbols[index]=="")return;
   uint nowMs=GetTickCount();
   // Timer housekeeping is throttled; OnTick still manages positions at tick frequency.
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
   // Prefer MT5's current M5 candle, but never let missing OHLC history or APK chart
   // freshness block D1. Maintain a fallback candle directly from this symbol's live ticks.
   datetime tickTime=(datetime)(tick.time_msc/1000);
   datetime barTime=iTime(symbol,PERIOD_M5,0);
   if(barTime<=0)barTime=(datetime)(((long)tickTime/300)*300);
   double candleOpen=iOpen(symbol,PERIOD_M5,0);
   double candleHigh=iHigh(symbol,PERIOD_M5,0);
   double candleLow=iLow(symbol,PERIOD_M5,0);
   if(powerD1FallbackBar[index]!=barTime||powerD1FallbackOpen[index]<=0.0){
      powerD1FallbackBar[index]=barTime;
      powerD1FallbackOpen[index]=(candleOpen>0.0?candleOpen:tick.bid);
      powerD1FallbackHigh[index]=MathMax(tick.bid,(candleHigh>0.0?candleHigh:tick.bid));
      powerD1FallbackLow[index]=MathMin(tick.bid,(candleLow>0.0?candleLow:tick.bid));
   }else{
      powerD1FallbackHigh[index]=MathMax(powerD1FallbackHigh[index],tick.bid);
      powerD1FallbackLow[index]=MathMin(powerD1FallbackLow[index],tick.bid);
      if(candleHigh>0.0)powerD1FallbackHigh[index]=MathMax(powerD1FallbackHigh[index],candleHigh);
      if(candleLow>0.0)powerD1FallbackLow[index]=MathMin(powerD1FallbackLow[index],candleLow);
      if(candleOpen>0.0)powerD1FallbackOpen[index]=candleOpen;
   }
   if(candleOpen<=0.0)candleOpen=powerD1FallbackOpen[index];
   if(candleHigh<=0.0||candleHigh<candleLow)candleHigh=powerD1FallbackHigh[index];
   else candleHigh=MathMax(candleHigh,powerD1FallbackHigh[index]);
   if(candleLow<=0.0||candleLow>candleHigh)candleLow=powerD1FallbackLow[index];
   else candleLow=MathMin(candleLow,powerD1FallbackLow[index]);
   if(barTime<=0||candleOpen<=0.0||candleHigh<candleLow||candleLow<=0.0){
      // Only impossible live-price data stops entry; a missing chart snapshot does not.
      powerPublishState(index,"D1_TICK_CANDLE_WAIT","Waiting for a valid live tick to seed the current candle",barTime);
      return;
   }

   bool hasPreviousTick=(powerLastTickMs[index]>0);
   double previousBid=powerLastBid[index];
   if(tick.time_msc!=powerLastTickMs[index])
      powerRecordTick(index,(tick.bid+tick.ask)*0.5);
   powerManagePositions(index,tick,barTime,candleOpen,hasPreviousTick,previousBid);
   powerLastBid[index]=tick.bid;
   powerLastAsk[index]=tick.ask;
   powerLastTickMs[index]=tick.time_msc;

   // Both entry engines may run together or independently. Momentum is evaluated
   // on each fresh tick; pending LIMIT planning remains timer-only.
   bool d1Active=(powerConfigReceivedMs[index]>0&&powerCfg[index].enabled)
   if(d1Active)
      powerTryD1ExtremeEntry(index,tick,barTime,candleOpen,candleHigh,candleLow);

   // Only timer cycles perform model/config/order work; OnTick manages positions.
   if(powerFastTickOnly)return;
   // On EA startup, do not interpret the zero-initialized config as an explicit OFF.
   // Wait for the first per-symbol APK/Gateway config before deleting any pending order.
   if(powerConfigReceivedMs[index]==0){
      powerPublishState(index,"CONFIG_WAIT","Waiting for the first SMART AUTO-POWERFULL config; existing pending orders preserved",barTime);
      return;
   }
   if(!powerCfg[index].enabled){
      powerCancelPendingForSymbol(symbol);
      powerPublishState(index,"OFF",powerCfg[index].reason==""?"POWERFULL disabled":powerCfg[index].reason,barTime);
      return;
   }

   // D1 is the primary POWERFULL entry engine. Do not run the legacy candle-history
   // forecast alongside it; cancel old POWERFULL pendings and continue using live MT5 ticks.
   if(d1Active){
      if(!powerCancelPendingForSymbol(symbol)||powerPendingCount(symbol)>0){
         powerPublishState(index,"D1_WAIT_LEGACY_CANCEL","D1 is active; waiting for old POWERFULL pending orders to cancel",barTime);
         return;
      }
      powerPublishState(index,"D1_ACTIVE","Human Price Extreme reads live MT5 ticks and current M5 High/Low; no prior-candle forecast; max layers="+
         IntegerToString(powerCfg[index].maxLayers),barTime);
      return;
   }

   // If D1 is OFF, the optional legacy Pending LIMIT mode remains available.
   if(!powerCfg[index].pendingLimitEnabled){
      if(!powerCancelPendingForSymbol(symbol)||powerPendingCount(symbol)>0){
         powerPublishState(index,"LIMIT_OFF_WAIT_CANCEL","Pending LIMIT mode is OFF; waiting for broker cancellation",barTime);
         return;
      }
      powerPublishState(index,d1Active?"MOMENTUM_ACTIVE":"NO_ENTRY_MODE",
         d1Active?"Liquid Candle Momentum ON; Pending LIMIT OFF":"Momentum and Pending LIMIT are both OFF",barTime);
      return;
   }

   // Cancel explicitly disabled sides or pending orders that have drifted far from price.
   // A new M5 candle or temporary signal change alone never deletes a pending order.
   if(!powerCancelDisabledPending(symbol,powerCfg[index].buyLimitEnabled,powerCfg[index].sellLimitEnabled)){
      powerPublishState(index,"DISABLED_SIDE_CANCEL_PENDING",
         "A disabled side's pending deletion is awaiting broker confirmation",barTime);
      return;
   }

   bool newBar=(powerSeenBar[index]!=barTime);
   if(newBar){
      powerSeenBar[index]=barTime;
      powerReplans[index]=0;
      powerForecastRangePips[index]=0.0;
      powerProjectedLow[index]=0.0;
      powerProjectedHigh[index]=0.0;
   }
   bool modelDue=newBar||!powerModelGood[index]||powerModelBuiltAtMs[index]==0||
      (uint)(nowMs-powerModelBuiltAtMs[index])>=15*60*1000;
   if(modelDue&&(powerLastModelAttemptMs[index]==0||
      (uint)(nowMs-powerLastModelAttemptMs[index])>=30000)){
      powerLastModelAttemptMs[index]=nowMs;
      if(!powerBuildModel(index)){
         powerPublishState(index,"FORECAST_WAIT","Need at least 3 completed M5 candles for wick/ATR projection; loaded="+
                           IntegerToString(powerHistorySamples[index])+"; existing pending retained",barTime);
         return;
      }
   }
   if(!powerModelGood[index]){
      powerPublishState(index,"MODEL_NOT_READY","Wick/ATR history is not ready; existing pending retained",barTime);
      return;
   }
   // Keep the most recent forecast for order targets. Existing orders are not repriced.
   if(powerForecastRangePips[index]<=0.0)
      powerForecastRangePips[index]=powerBaseRangePips[index];
   powerProjectBounds(index,candleOpen);
   bool placed=false;
   // Simple completed-candle forecast; no tick-reversal filter or final-seconds gate.
   if(powerLastSeedBar[index]!=barTime){
      if(powerCfg[index].buyLimitEnabled&&!powerHasPendingSide(symbol,true))
         placed=powerPlacePendingSide(index,true,barTime,tick,powerProjectedLow[index])||placed;
      if(powerCfg[index].sellLimitEnabled&&!powerHasPendingSide(symbol,false))
         placed=powerPlacePendingSide(index,false,barTime,tick,powerProjectedHigh[index])||placed;
      powerLastSeedBar[index]=barTime;
   }
   int pending=powerPendingCount(symbol);
   int positions=powerPositionCount(symbol);
   string stateDetail="simple average range of "+IntegerToString(powerHistorySamples[index])+
      " completed M5 candles; pending="+IntegerToString(pending)+"; positions="+IntegerToString(positions);
   if(pending>0){
      powerPublishState(index,"PENDING_ACTIVE",stateDetail+"; fixed levels expire at M5 close",barTime);
   }else if(positions>0){
      powerPublishState(index,"POSITION_ACTIVE",stateDetail+"; risk management active",barTime);
   }else if(placed){
      powerPublishState(index,"PENDING_PLACED",stateDetail+"; entry attempted on new M5 bar",barTime);
   }else{
      powerPublishState(index,"ENTRY_ATTEMPTED",stateDetail+"; inspect broker geometry, permissions and return code",barTime);
   }
}
