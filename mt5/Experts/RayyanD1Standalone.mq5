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
input int StandaloneMaxLayers=5; // configurable 1..5; A6 default allows five layers
// POWERFULL final: no time-based entry warm-up.
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

input bool PowerfullCloseBeforeWeekend=true;
input int PowerfullFridayCloseHour=21;   // Broker server time.
input int PowerfullFridayCloseMinute=30;
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
double powerMomentumLastSellHigh[3];
double powerMomentumLastBuyLow[3];
// A6 tracks live Ask range separately because standard MT5 OHLC candles are Bid-based.
datetime powerD1FallbackBar[3];
double powerD1FallbackOpen[3],powerD1FallbackHigh[3],powerD1FallbackLow[3];
double powerD1AskHigh[3],powerD1AskLow[3];
// A12: live M5 candle distribution only. No completed-candle reference/history is used.
long powerD1LastCountedTickMs[3];
datetime powerMomentumLastEntryBar[3];
// Successful-entry extremes persist within each M5 candle for subsequent layers.
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
bool powerA6StructureBreak(string symbol,ENUM_TIMEFRAMES tf,bool isBuy,int lookback){
   MqlRates rates[];
   ArraySetAsSeries(rates,true);
   int need=lookback+2;
   if(CopyRates(symbol,tf,1,need,rates)<need)return false;
   double lastClose=rates[0].close;
   if(lastClose<=0.0)return false;
   double boundary=(isBuy?DBL_MAX:-DBL_MAX);
   for(int i=1;i<need;i++){
      if(rates[i].high<=0.0||rates[i].low<=0.0)return false;
      if(isBuy)boundary=MathMin(boundary,rates[i].low);
      else boundary=MathMax(boundary,rates[i].high);
   }
   return (isBuy?lastClose<boundary:lastClose>boundary);
}

void powerA6LogOrderflowProxy(string symbol,ulong ticket,bool isBuy){
   // Broker tick-volume/candle proxy only. This is NOT exchange-wide bid/ask footprint data.
   MqlRates r[];
   ArraySetAsSeries(r,true);
   if(CopyRates(symbol,PERIOD_M5,1,1,r)!=1)return;
   if(r[0].time<=0||r[0].tick_volume<=0)return;
   double body=r[0].close-r[0].open;
   double range=MathMax(r[0].high-r[0].low,SymbolInfoDouble(symbol,SYMBOL_TICK_SIZE));
   double bodyRatio=(range>0.0?MathAbs(body)/range:0.0);
   string candle=(body>0.0?"BULL":(body<0.0?"BEAR":"DOJI"));
   Print("POWERFULL A6 ORDERFLOW_PROXY symbol=",symbol," ticket=",ticket,
         " M5=",candle," tick_volume=",r[0].tick_volume,
         " body_ratio=",DoubleToString(bodyRatio,2),
         " direction_supports_position=",((isBuy&&body>0.0)||(!isBuy&&body<0.0)?"YES":"NO"),
         " source=broker_tick_volume_proxy_only; decision_weight=OBSERVATION_ONLY");
}

void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool candleJustChanged,bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return;
   string symbol=powerSymbols[index];
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   double ts=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(point<=0.0||ts<=0.0)return;
   double minLock=MathMax((double)powerCfg[index].minProfitLockPoints*point,ts*2.0);
   ulong closeTickets[]; double closeDistances[]; int closeCount=0;

   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC||
         !PositionSelectByTicket(ticket))continue;
      ENUM_POSITION_TYPE side=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      bool isBuy=(side==POSITION_TYPE_BUY);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN);
      double px=(isBuy?tick.bid:tick.ask);
      double slNet=powerProjectedNet(ticket);
      int slPts=(isBuy?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
      double hard=(powerCfg[index].usePointStops?slPts*point:powerCfg[index].stopPips*powerCfg[index].pipSize);
      double adverse=(isBuy?entry-px:px-entry);
      if(hard>0.0&&adverse+point*0.1>=hard){powerClosePosition(ticket,"HARD_LOSS_GUARD");continue;}

      string suffix=IntegerToString((long)ticket);
      string peakKey="PW_PEAK_"+suffix;
      string evalKey="PW_A6_LAST_H1_"+suffix;
      if(!GlobalVariableCheck(peakKey))GlobalVariableSet(peakKey,px);
      double peak=GlobalVariableGet(peakKey);
      bool newPeak=(isBuy?px>peak+ts*0.1:px<peak-ts*0.1);
      if(newPeak){peak=px;GlobalVariableSet(peakKey,peak);}

      // A6: retracement is measured from this individual position's best executable price.
      double retrace=(isBuy?peak-px:px-peak);
      double peakGain=(isBuy?peak-entry:entry-peak);
      if(peakGain<minLock||retrace<peakGain*0.30)continue;

      datetime h1Closed=iTime(symbol,PERIOD_H1,1);
      if(h1Closed<=0)continue;
      double lastEval=(GlobalVariableCheck(evalKey)?GlobalVariableGet(evalKey):0.0);
      if((double)h1Closed<=lastEval)continue;
      GlobalVariableSet(evalKey,(double)h1Closed);

      // H1 is decisive: a completed H1 close must break the prior 10-bar structure.
      bool h1Broken=powerA6StructureBreak(symbol,PERIOD_H1,isBuy,10);
      // M15 is the early confirmation; a completed M15 close must also break its 8-bar structure.
      bool m15Confirmed=powerA6StructureBreak(symbol,PERIOD_M15,isBuy,8);
      powerA6LogOrderflowProxy(symbol,ticket,isBuy);
      Print("POWERFULL A6 TREND_CHECK symbol=",symbol," ticket=",ticket,
            " H1_BROKEN=",(h1Broken?"YES":"NO"),
            " M15_CONFIRM=",(m15Confirmed?"YES":"NO"),
            " peak_profit_price=",DoubleToString(peakGain,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " giveback=",DoubleToString(retrace,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " giveback_pct=",DoubleToString((peakGain>0.0?100.0*retrace/peakGain:0.0),1),
            " current_net=",DoubleToString(slNet,2));

      if(!h1Broken||!m15Confirmed)continue;
      double net=powerProjectedNet(ticket);
      if(net<=0.0)continue;
      int n=ArraySize(closeTickets);
      ArrayResize(closeTickets,n+1);
      ArrayResize(closeDistances,n+1);
      closeTickets[n]=ticket;
      closeDistances[n]=MathAbs(entry-px);
      closeCount++;
   }

   // If several positions qualify, close only the layer nearest the executable quote first.
   if(closeCount>0){
      int nearest=0;
      for(int c=1;c<closeCount;c++)
         if(closeDistances[c]<closeDistances[nearest])nearest=c;
      Print("POWERFULL A6 EXIT symbol=",symbol," ticket=",closeTickets[nearest],
            " reason=H1_STRUCTURE_BREAK_M15_CONFIRMED_AND_30PCT_PEAK_PROFIT_RETRACE");
      powerClosePosition(closeTickets[nearest],"A6_H1_BREAK_M15_CONFIRM_30PCT_RETRACE");
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
   if(powerWeekendCloseDue()){
      powerApplyWeekendClose(symbol);
      powerPublishState(index,"WEEKEND_FLAT",
         "Friday broker-server cutoff reached; closing POWERFULL positions and deleting POWERFULL pending orders",0);
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
   bool candleJustChanged=(powerD1FallbackBar[index]>0&&powerD1FallbackBar[index]!=barTime);
   double candleOpen=iOpen(symbol,PERIOD_M5,0);
   double candleHigh=iHigh(symbol,PERIOD_M5,0);
   double candleLow=iLow(symbol,PERIOD_M5,0);
   if(powerD1FallbackBar[index]!=barTime||powerD1FallbackOpen[index]<=0.0){
      powerD1FallbackBar[index]=barTime;
      powerD1FallbackOpen[index]=(candleOpen>0.0?candleOpen:tick.bid);
      powerD1FallbackHigh[index]=MathMax(tick.bid,(candleHigh>0.0?candleHigh:tick.bid));
      powerD1FallbackLow[index]=MathMin(tick.bid,(candleLow>0.0?candleLow:tick.bid));
      powerD1AskHigh[index]=tick.ask;
      powerD1AskLow[index]=tick.ask;
      powerD1LastCountedTickMs[index]=tick.time_msc;
   }else{
      powerD1FallbackHigh[index]=MathMax(powerD1FallbackHigh[index],tick.bid);
      powerD1FallbackLow[index]=MathMin(powerD1FallbackLow[index],tick.bid);
      powerD1AskHigh[index]=MathMax(powerD1AskHigh[index],tick.ask);
      powerD1AskLow[index]=MathMin(powerD1AskLow[index],tick.ask);
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
   double previousAsk=powerLastAsk[index];
   powerManagePositions(index,tick,barTime,candleOpen,candleJustChanged,
                         hasPreviousTick,previousBid,previousAsk);
   powerLastBid[index]=tick.bid;
   powerLastAsk[index]=tick.ask;
   powerLastTickMs[index]=tick.time_msc;

   // Both entry engines may run together or independently. Momentum is evaluated
   // on each fresh tick; pending LIMIT planning remains timer-only.
   bool d1Active=(powerConfigReceivedMs[index]>0&&powerCfg[index].enabled);
   if(d1Active)
      powerTryD1ExtremeEntry(index,tick,barTime,candleOpen,candleHigh,candleLow,
                             powerD1AskHigh[index],powerD1AskLow[index],
                             hasPreviousTick,previousBid,previousAsk);

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
      powerPublishState(index,"D1_ACTIVE","POWERFULL final: current M5 weakening extreme; no warm-up or tick-count confirmation; max layers="+
         IntegerToString(powerCfg[index].maxLayers),barTime);
      return;
   }

   // If D1 is OFF, the optional legacy Pending LIMIT mode remains available.
   if(!powerCfg[index].pendingLimitEnabled){
      if(!powerCancelPendingForSymbol(symbol)||powerPendingCount(symbol)>0){
         powerPublishState(index,"LIMIT_OFF_WAIT_CANCEL","Pending LIMIT mode is OFF; waiting for broker cancellation",barTime);
         return;
      }
      powerPublishState(index,d1Active?"D1_ACTIVE":"NO_D1_ENTRY_MODE",
         d1Active?"D1 Human Price Extreme active":"D1 entry engine is OFF",barTime);
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
