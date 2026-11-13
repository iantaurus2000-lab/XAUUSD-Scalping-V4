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
void updateHarmonicOverlay();
void clearHarmonicOverlay();
bool appendBarsForTimeframe(string symbol,ENUM_TIMEFRAMES tf,string tfName,int count,string &batch);
void publishBarsForTimeframe(string symbol,ENUM_TIMEFRAMES tf,string tfName,int count);
string chartTimeframeName(ENUM_TIMEFRAMES tf);
uint lastNativeBarsTick=0;
uint lastScreenshotTick=0;

// Native chart layers are managed by this same EA; no second EA is needed.
input int SRLookbackBars=80;
input int FiboLookbackBars=60;
input bool EnableHarmonicOverlay=true; // Visual-only; never participates in trade decisions.
input int HarmonicLookbackBars=260;
input int HarmonicPivotDepth=3;
input double HarmonicRatioTolerance=0.12;
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
#define POWERFULL_MODE_TREND_HOLD 1
#define POWERFULL_MODE_LOSS_RESCUE 2
#define POWERFULL_MIN_TREND_HOLD_SECONDS (3*5*60)
#define POWERFULL_LOSS_RESCUE_ARM_SECONDS (3*5*60)
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
int powerConfirmedSwingStructure(string symbol,ENUM_TIMEFRAMES timeframe);
void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool candleJustChanged,bool hasPreviousTick,double previousBid,double previousAsk);
bool powerManageLossRescue(int index,const MqlTick &tick);
bool powerManageTrendHold(int index,const MqlTick &tick);
void powerRefreshAnalysisReports();
void powerWriteAnalysisReport(string targetPath,datetime sessionStart=0);
void powerTryD1ExtremeEntry(int index,const MqlTick &tick,datetime barTime,
                             double candleOpen,double candleHigh,double candleLow,
                             double askHigh,double askLow,
                             bool hasPreviousTick,double previousBid,double previousAsk);
void powerClosedDealStats(string symbol,int &wins,int &losses,int &closed);
double powerAtrPrice(int index);
double powerProjectedNet(ulong ticket);
bool powerClosePosition(ulong ticket,string reason);
string powerShortSymbol(string symbol);
datetime powerOrderBarFromComment(string comment);
double powerRoundToTick(string symbol,double price);
bool powerConfigFresh(int index);
void powerDashboardRefresh(bool force=false);
void powerDashboardDelete();
void powerDashboardLabel(string name,string value,int x,int y,color clr,int size=9);
void powerDashboardStats(string symbol,int &entries,int &wins,int &losses,double &totalProfit,double &totalLoss);
bool powerHasPendingOnBar(string symbol,datetime barTime);



#define POWERFULL_REPORT_DIR "RAYYAN_REPORTS"
#define POWERFULL_ANALYSIS_FILE "RAYYAN_REPORTS\\RAYYAN_LOSS_ANALYSIS.html"
string powerSessionId="",powerSessionFolder="",powerLastSessionFolder="";
datetime powerSessionStart=0,powerSessionEnd=0;
bool powerSessionActive=false;
string powerSessionStamp(datetime value){
   string stamp=TimeToString(value,TIME_DATE|TIME_MINUTES|TIME_SECONDS);
   StringReplace(stamp,".",""); StringReplace(stamp,":",""); StringReplace(stamp," ","_");
   return stamp;
}
string powerSessionFolderFromStart(datetime started){return POWERFULL_REPORT_DIR+"\\SESSION_"+powerSessionStamp(started);}
string powerSessionFolderForPosition(ulong positionId){
   string key="PW_SID_"+(string)positionId;
   if(GlobalVariableCheck(key))return powerSessionFolderFromStart((datetime)GlobalVariableGet(key));
   return ""; // Do not mix pre-session positions into a new ON session.
}
string powerSessionPath(ulong positionId,string leaf){
   string folder=powerSessionFolderForPosition(positionId);
   if(folder=="")return "";
   FolderCreate(folder); FolderCreate(folder+"\\SCREENSHOTS");
   return folder+"\\"+leaf;
}
bool powerSessionDealSeen(ulong deal){
   return GlobalVariableCheck("PW_DEAL_"+(string)deal);
}
void powerSessionMarkDealSeen(ulong deal){
   if(deal>0)GlobalVariableSet("PW_DEAL_"+(string)deal,(double)TimeCurrent());
}
void powerSessionWriteOpenPositions(string folder){
   if(folder=="")return;
   int h=FileOpen(folder+"\\OPEN_POSITIONS.csv",FILE_WRITE|FILE_CSV|FILE_ANSI,';');
   if(h==INVALID_HANDLE)return;
   FileWrite(h,"ticket","position_id","symbol","side","volume","open_price","sl","profit");
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||(long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      ulong pid=(ulong)PositionGetInteger(POSITION_IDENTIFIER);
      string key="PW_SID_"+(string)pid;
      if(!GlobalVariableCheck(key)||powerSessionFolderFromStart((datetime)GlobalVariableGet(key))!=folder)continue;
      string symbol=PositionGetString(POSITION_SYMBOL);
      ENUM_POSITION_TYPE side=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
      FileWrite(h,(string)ticket,(string)pid,symbol,(side==POSITION_TYPE_BUY?"BUY":"SELL"),
         DoubleToString(PositionGetDouble(POSITION_VOLUME),2),
         DoubleToString(PositionGetDouble(POSITION_PRICE_OPEN),digits),
         DoubleToString(PositionGetDouble(POSITION_SL),digits),
         DoubleToString(PositionGetDouble(POSITION_PROFIT),2));
   }
   FileClose(h);
}
void powerSessionWriteSummary(string folder,datetime started,datetime ended){
   if(folder=="")return;
   int h=FileOpen(folder+"\\SESSION_SUMMARY.html",FILE_WRITE|FILE_TXT|FILE_ANSI);
   if(h==INVALID_HANDLE)return;
   string html="<!doctype html><html><head><meta charset='utf-8'><title>RAYYAN Session</title></head><body>"+
      "<h1>RAYYAN POWERFULL Test Session</h1><p>Session ID: "+powerSessionStamp(started)+
      "</p><p>Start: "+TimeToString(started,TIME_DATE|TIME_SECONDS)+
      "</p><p>Algo Trading OFF: "+(ended>0?TimeToString(ended,TIME_DATE|TIME_SECONDS):"ACTIVE")+
      "</p><p>See LOSS_ANALYSIS.html, LOSS_RECORDER.csv, ENTRY_CANDLE_LOG.csv and OPEN_POSITIONS.csv.</p>"+
      "<p>Late closing deals remain assigned to their original position session.</p></body></html>";
   FileWriteString(h,html); FileClose(h);
   int a=FileOpen(folder+"\\LOSS_ANALYSIS.html",FILE_WRITE|FILE_TXT|FILE_ANSI);
   if(a!=INVALID_HANDLE){FileWriteString(a,html);FileClose(a);}
}
void powerSessionStartNew(){
   powerSessionStart=TimeCurrent(); powerSessionEnd=0;
   GlobalVariableSet("PW_LAST_SESSION_START",(double)powerSessionStart);
   powerSessionId=powerSessionStamp(powerSessionStart);
   powerSessionFolder=powerSessionFolderFromStart(powerSessionStart);
   powerLastSessionFolder=powerSessionFolder;
   FolderCreate(POWERFULL_REPORT_DIR); FolderCreate(powerSessionFolder);
   FolderCreate(powerSessionFolder+"\\SCREENSHOTS");
   powerSessionActive=true; GlobalVariableSet("PW_ACTIVE_SESSION_START",(double)powerSessionStart);
   Print("RAYYAN SESSION START id=",powerSessionId," folder=",powerSessionFolder);
   powerSessionWriteOpenPositions(powerSessionFolder);
   powerSessionWriteSummary(powerSessionFolder,powerSessionStart,0);
   powerRefreshAnalysisReports();
}
void powerSessionFinish(){
   if(!powerSessionActive)return;
   powerSessionEnd=TimeCurrent(); powerSessionActive=false;
   GlobalVariableSet("PW_LAST_SESSION_START",(double)powerSessionStart);
   powerLastSessionFolder=powerSessionFolder;
   GlobalVariableDel("PW_ACTIVE_SESSION_START");
   powerSessionWriteOpenPositions(powerSessionFolder);
   powerSessionWriteSummary(powerSessionFolder,powerSessionStart,powerSessionEnd);
   powerRefreshAnalysisReports();
   Print("RAYYAN SESSION END id=",powerSessionId," time=",TimeToString(powerSessionEnd,TIME_DATE|TIME_SECONDS),
         " open POWERFULL positions are retained in OPEN_POSITIONS.csv");
}
void powerSessionRefresh(){
   bool allowed=powerAlgoTradingEnabled();
   if(allowed&&!powerSessionActive)powerSessionStartNew();
   else if(!allowed&&powerSessionActive)powerSessionFinish();
   if(!allowed&&!powerSessionActive&&powerSessionStart==0&&GlobalVariableCheck("PW_LAST_SESSION_START"))
      powerSessionStart=(datetime)GlobalVariableGet("PW_LAST_SESSION_START");
}
string powerSessionLossPath(ulong positionId){return powerSessionPath(positionId,"LOSS_RECORDER.csv");}
string powerSessionCandlePath(ulong positionId){return powerSessionPath(positionId,"ENTRY_CANDLE_LOG.csv");}

string powerReportSide(ENUM_DEAL_TYPE type){
   if(type==DEAL_TYPE_BUY)return "BUY";
   if(type==DEAL_TYPE_SELL)return "SELL";
   return EnumToString(type);
}
void powerAppendDealRecord(ulong deal){
   if(deal==0||!HistoryDealSelect(deal))return;
   if((long)HistoryDealGetInteger(deal,DEAL_MAGIC)!=POWERFULL_MAGIC)return;
   ulong positionId=(ulong)HistoryDealGetInteger(deal,DEAL_POSITION_ID);
   string dealSessionFolder=powerSessionFolderForPosition(positionId);
   if(powerSessionDealSeen(deal))return;
   ENUM_DEAL_ENTRY dealEntry=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(deal,DEAL_ENTRY);
   if(dealEntry==DEAL_ENTRY_IN||dealEntry==DEAL_ENTRY_INOUT){
      if(!powerSessionActive)return;
      GlobalVariableSet("PW_SID_"+(string)positionId,(double)powerSessionStart);
      dealSessionFolder=powerSessionFolder;
   }else if(dealSessionFolder=="")return;
   ENUM_DEAL_ENTRY entry=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(deal,DEAL_ENTRY);
   ENUM_DEAL_TYPE type=(ENUM_DEAL_TYPE)HistoryDealGetInteger(deal,DEAL_TYPE);
   datetime when=(datetime)HistoryDealGetInteger(deal,DEAL_TIME);
   string symbol=HistoryDealGetString(deal,DEAL_SYMBOL);
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
   bool positionBuy=((entry==DEAL_ENTRY_OUT||entry==DEAL_ENTRY_OUT_BY)?(type==DEAL_TYPE_SELL):(type==DEAL_TYPE_BUY));
   int slPoints=(positionBuy?StandaloneSLBuyPoints:StandaloneSLSellPoints);
   double actualSL=0.0;
   string slState="NOT_AVAILABLE_AFTER_CLOSE";
   if(entry==DEAL_ENTRY_IN||entry==DEAL_ENTRY_INOUT){
      for(int p=PositionsTotal()-1;p>=0;p--){
         ulong ticket=PositionGetTicket(p);
         if(ticket==0||!PositionSelectByTicket(ticket))continue;
         if(PositionGetString(POSITION_SYMBOL)!=symbol||
            (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
         if((ulong)PositionGetInteger(POSITION_IDENTIFIER)!=positionId)continue;
         actualSL=PositionGetDouble(POSITION_SL);
         slState=(actualSL>0.0?"SET":"MISSING");
         break;
      }
   }
   int h=FileOpen(powerSessionLossPath(positionId),FILE_READ|FILE_WRITE|FILE_CSV|FILE_ANSI|FILE_SHARE_READ|FILE_SHARE_WRITE,';');
   if(h==INVALID_HANDLE){Print("POWERFULL REPORT CSV OPEN FAILED err=",GetLastError());return;}
   if(FileSize(h)==0)
      FileWrite(h,"time","deal_ticket","position_id","symbol","entry_type","side","volume","price",
                "sl_points_setting","sl_price_distance","actual_sl_price","sl_state",
                "deal_profit","swap","commission","fee","net","reason","comment");
   FileSeek(h,0,SEEK_END);
   ulong rowStartSize=FileSize(h);
   double profit=HistoryDealGetDouble(deal,DEAL_PROFIT);
   double swap=HistoryDealGetDouble(deal,DEAL_SWAP);
   double commission=HistoryDealGetDouble(deal,DEAL_COMMISSION);
   double fee=HistoryDealGetDouble(deal,DEAL_FEE);
   uint rowWritten=FileWrite(h,TimeToString(when,TIME_DATE|TIME_SECONDS),(string)deal,(string)positionId,symbol,
      EnumToString(entry),powerReportSide(type),
      DoubleToString(HistoryDealGetDouble(deal,DEAL_VOLUME),8),
      DoubleToString(HistoryDealGetDouble(deal,DEAL_PRICE),digits),
      slPoints,DoubleToString(slPoints*point,digits),
      (actualSL>0.0?DoubleToString(actualSL,digits):""),
      slState,DoubleToString(profit,2),DoubleToString(swap,2),
      DoubleToString(commission,2),DoubleToString(fee,2),
      DoubleToString(profit+swap+commission+fee,2),
      EnumToString((ENUM_DEAL_REASON)HistoryDealGetInteger(deal,DEAL_REASON)),
      HistoryDealGetString(deal,DEAL_COMMENT));
   FileFlush(h);
   bool rowSaved=(rowWritten>0&&FileSize(h)>rowStartSize);
   FileClose(h);
   if(!rowSaved){
      Print("POWERFULL REPORT CSV WRITE FAILED deal=",deal," file=",powerSessionLossPath(positionId));
      return;
   }
   // Mark only after the row was flushed. Failed writes can be retried.
   powerSessionMarkDealSeen(deal);
   powerRefreshAnalysisReports();
   if(entry==DEAL_ENTRY_OUT||entry==DEAL_ENTRY_OUT_BY||entry==DEAL_ENTRY_INOUT){
      bool stillOpen=false;
      for(int q=PositionsTotal()-1;q>=0;q--){
         ulong qt=PositionGetTicket(q);
         if(qt>0&&PositionSelectByTicket(qt)&&
            (ulong)PositionGetInteger(POSITION_IDENTIFIER)==positionId&&
            (long)PositionGetInteger(POSITION_MAGIC)==POWERFULL_MAGIC){stillOpen=true;break;}
      }
      // Preserve original session mapping for late close deals and report refreshes.
      if(dealSessionFolder!="")powerSessionWriteOpenPositions(dealSessionFolder);
   }
}
void powerWriteAnalysisReport(string targetPath,datetime sessionStart){
   if(targetPath=="")return;
   datetime to=TimeCurrent(),from=to-90*86400;
   if(!HistorySelect(from,to))return;
   int h=FileOpen(targetPath,FILE_WRITE|FILE_TXT|FILE_ANSI);
   if(h==INVALID_HANDLE){Print("POWERFULL REPORT HTML OPEN FAILED path=",targetPath," err=",GetLastError());return;}
   int entries=0,exits=0,wins=0,losses=0;
   double gross=0.0,net=0.0;
   string rows="";
   for(int i=0;i<HistoryDealsTotal();i++){
      ulong deal=HistoryDealGetTicket(i);
      if(deal==0||(long)HistoryDealGetInteger(deal,DEAL_MAGIC)!=POWERFULL_MAGIC)continue;
      ulong positionId=(ulong)HistoryDealGetInteger(deal,DEAL_POSITION_ID);
      if(sessionStart>0){
         string sk="PW_SID_"+(string)positionId;
         if(!GlobalVariableCheck(sk)||(datetime)GlobalVariableGet(sk)!=sessionStart)continue;
      }
      string symbol=HistoryDealGetString(deal,DEAL_SYMBOL);
      ENUM_DEAL_ENTRY en=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(deal,DEAL_ENTRY);
      ENUM_DEAL_TYPE side=(ENUM_DEAL_TYPE)HistoryDealGetInteger(deal,DEAL_TYPE);
      double profit=HistoryDealGetDouble(deal,DEAL_PROFIT);
      double swap=HistoryDealGetDouble(deal,DEAL_SWAP);
      double commission=HistoryDealGetDouble(deal,DEAL_COMMISSION);
      double fee=HistoryDealGetDouble(deal,DEAL_FEE);
      double dealNet=profit+swap+commission+fee;
      if(en==DEAL_ENTRY_IN||en==DEAL_ENTRY_INOUT)entries++;
      if(en==DEAL_ENTRY_OUT||en==DEAL_ENTRY_OUT_BY||en==DEAL_ENTRY_INOUT){
         exits++;gross+=profit;net+=dealNet;
         if(dealNet>0.0)wins++;else if(dealNet<0.0)losses++;
      }
      rows+="<tr><td>"+TimeToString((datetime)HistoryDealGetInteger(deal,DEAL_TIME),TIME_DATE|TIME_MINUTES)+
         "</td><td>"+symbol+"</td><td>"+EnumToString(en)+"</td><td>"+powerReportSide(side)+
         "</td><td>"+DoubleToString(HistoryDealGetDouble(deal,DEAL_VOLUME),2)+
         "</td><td>"+DoubleToString(HistoryDealGetDouble(deal,DEAL_PRICE),(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS))+
         "</td><td>"+DoubleToString(dealNet,2)+"</td><td>"+
         EnumToString((ENUM_DEAL_REASON)HistoryDealGetInteger(deal,DEAL_REASON))+"</td></tr>";
   }
   double winRate=(exits>0?100.0*(double)wins/(double)exits:0.0);
   string html="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"+
      "<title>Rayyan POWERFULL Analysis</title><style>body{font-family:Arial,sans-serif;margin:18px;color:#202124;background:#f7f8fa}"+
      ".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:10px}.card{background:#fff;border:1px solid #ddd;border-radius:8px;padding:12px}"+
      ".value{font-size:21px;font-weight:bold;margin-top:5px}table{border-collapse:collapse;width:100%;background:#fff;margin-top:12px}"+
      "th,td{border:1px solid #ddd;padding:8px;text-align:left;font-size:13px}th{background:#eef1f5}.tablewrap{overflow-x:auto}</style></head><body>"+
      "<h1>Rayyan POWERFULL — Loss Analysis</h1><p>Period: "+(sessionStart>0?"ON/OFF session "+powerSessionStamp(sessionStart):"Last 90 days")+
      " | Generated: "+TimeToString(to,TIME_DATE|TIME_SECONDS)+"</p><div class='cards'>"+
      "<div class='card'>Entry<div class='value'>"+IntegerToString(entries)+"</div></div><div class='card'>Exit<div class='value'>"+IntegerToString(exits)+"</div></div>"+
      "<div class='card'>Winrate (exit deals)<div class='value'>"+DoubleToString(winRate,2)+"%</div></div>"+
      "<div class='card'>Win / Loss<div class='value'>"+IntegerToString(wins)+" / "+IntegerToString(losses)+"</div></div>"+
      "<div class='card'>Gross<div class='value'>"+DoubleToString(gross,2)+"</div></div><div class='card'>Net<div class='value'>"+DoubleToString(net,2)+"</div></div></div>"+
      "<div class='tablewrap'><table><thead><tr><th>Time</th><th>Symbol</th><th>Entry</th><th>Side</th><th>Volume</th><th>Price</th><th>Net</th><th>Reason</th></tr></thead><tbody>"+
      rows+"</tbody></table></div></body></html>";
   FileWriteString(h,html);
   FileFlush(h);
   FileClose(h);
}

void powerRefreshAnalysisReports(){
   FolderCreate(POWERFULL_REPORT_DIR);
   powerWriteAnalysisReport(POWERFULL_ANALYSIS_FILE,0);
   string sessionFolder=(powerSessionActive?powerSessionFolder:powerLastSessionFolder);
   datetime sessionStart=(powerSessionActive?powerSessionStart:0);
   if(!powerSessionActive&&sessionFolder!=""){
      int cut=StringFind(sessionFolder,"SESSION_");
      if(cut>=0){
         string stamp=StringSubstr(sessionFolder,cut+8);
         if(StringLen(stamp)>=15){
            string d=StringSubstr(stamp,0,4)+"."+StringSubstr(stamp,4,2)+"."+StringSubstr(stamp,6,2);
            string t=StringSubstr(stamp,9,2)+":"+StringSubstr(stamp,11,2)+":"+StringSubstr(stamp,13,2);
            sessionStart=StringToTime(d+" "+t);
         }
      }
   }
   if(sessionFolder!=""){
      FolderCreate(sessionFolder);
      powerWriteAnalysisReport(sessionFolder+"\\LOSS_ANALYSIS.html",sessionStart);
      powerSessionWriteOpenPositions(sessionFolder);
   }
}

void powerCaptureEntryCandle(ulong deal){
   // Observational recorder only: this function runs after MT5 confirms an entry deal.
   if(deal==0||!HistoryDealSelect(deal))return;
   if((long)HistoryDealGetInteger(deal,DEAL_MAGIC)!=POWERFULL_MAGIC)return;
   if(GlobalVariableCheck("PW_CANDLE_"+(string)deal))return;
   GlobalVariableSet("PW_CANDLE_"+(string)deal,(double)TimeCurrent());
   ENUM_DEAL_ENTRY entry=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(deal,DEAL_ENTRY);
   if(entry!=DEAL_ENTRY_IN&&entry!=DEAL_ENTRY_INOUT)return;

   string symbol=HistoryDealGetString(deal,DEAL_SYMBOL);
   ENUM_DEAL_TYPE dealType=(ENUM_DEAL_TYPE)HistoryDealGetInteger(deal,DEAL_TYPE);
   if(dealType!=DEAL_TYPE_BUY&&dealType!=DEAL_TYPE_SELL)return;
   string side=(dealType==DEAL_TYPE_BUY?"BUY":"SELL");
   datetime when=(datetime)HistoryDealGetInteger(deal,DEAL_TIME);
   ulong positionId=(ulong)HistoryDealGetInteger(deal,DEAL_POSITION_ID);
   double entryPrice=HistoryDealGetDouble(deal,DEAL_PRICE);
   double volume=HistoryDealGetDouble(deal,DEAL_VOLUME);
   int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   MqlTick tick; ZeroMemory(tick);
   bool hasTick=SymbolInfoTick(symbol,tick);
   double candleOpen=iOpen(symbol,PERIOD_M5,0);
   double candleHigh=iHigh(symbol,PERIOD_M5,0);
   double candleLow=iLow(symbol,PERIOD_M5,0);
   double candleClose=iClose(symbol,PERIOD_M5,0);
   // Use the EA's live-tick fallback only when the broker's current M5 OHLC is unavailable.
   for(int k=0;k<ArraySize(powerSymbols);k++){
      if(powerSymbols[k]==symbol&&powerD1FallbackBar[k]>0){
         if(candleOpen<=0.0)candleOpen=powerD1FallbackOpen[k];
         if(candleHigh<=0.0)candleHigh=powerD1FallbackHigh[k];
         if(candleLow<=0.0)candleLow=powerD1FallbackLow[k];
         break;
      }
   }
   string candleColor=(candleOpen>0.0&&candleClose>0.0?
      (candleClose>candleOpen?"BULL":(candleClose<candleOpen?"BEAR":"DOJI")):"OHLC_UNAVAILABLE");
   double extremeDistance=(side=="BUY"&&candleLow>0.0?entryPrice-candleLow:
      (side=="SELL"&&candleHigh>0.0?candleHigh-entryPrice:0.0));
   double sl=0.0;
   for(int p=PositionsTotal()-1;p>=0;p--){
      ulong ticket=PositionGetTicket(p);
      if(ticket==0||!PositionSelectByTicket(ticket))continue;
      if(PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      if((ulong)PositionGetInteger(POSITION_IDENTIFIER)==positionId){
         sl=PositionGetDouble(POSITION_SL); break;
      }
   }

   FolderCreate(POWERFULL_REPORT_DIR);
   string stamp=TimeToString(when,TIME_DATE|TIME_MINUTES|TIME_SECONDS);
   StringReplace(stamp,".",""); StringReplace(stamp,":",""); StringReplace(stamp," ","_");
   string safeSymbol=symbol; StringReplace(safeSymbol,".","_");
   string imagePath=POWERFULL_REPORT_DIR+"\\ENTRY_"+safeSymbol+"_"+stamp+"_"+(string)deal+".png";
   long chart=0;
   // Capture only a chart that actually displays this traded symbol on M5.
   for(long candidate=ChartFirst();candidate>=0;candidate=ChartNext(candidate)){
      if(ChartSymbol(candidate)==symbol&&ChartPeriod(candidate)==PERIOD_M5){chart=candidate;break;}
   }
   bool shot=false;
   string shotState="NO_MATCHING_M5_CHART";
   if(chart>0){
      ResetLastError();
      string sessionImage=powerSessionPath(positionId,"SCREENSHOTS\\ENTRY_"+safeSymbol+"_"+stamp+"_"+(string)deal+".png");
      shot=ChartScreenShot(chart,sessionImage,1280,720,ALIGN_RIGHT);
      imagePath=sessionImage;
      shotState=(shot?"SAVED":"FAILED_"+IntegerToString(GetLastError()));
   }

   int h=FileOpen(powerSessionCandlePath(positionId),
      FILE_READ|FILE_WRITE|FILE_CSV|FILE_ANSI|FILE_SHARE_READ|FILE_SHARE_WRITE,';');
   if(h==INVALID_HANDLE){
      Print("RAYYAN ENTRY CANDLE CSV OPEN FAILED deal=",deal," err=",GetLastError(),
            " screenshot=",shotState," path=",imagePath);
      return;
   }
   if(FileSize(h)==0)
      FileWrite(h,"time","deal_ticket","position_id","symbol","side","volume","entry_price",
         "bid_at_record","ask_at_record","m5_open","m5_high","m5_low","m5_close",
         "candle_color","distance_from_extreme","distance_points","sl_price",
         "screenshot_status","screenshot_file");
   FileSeek(h,0,SEEK_END);
   double bid=(hasTick?tick.bid:0.0), ask=(hasTick?tick.ask:0.0);
   FileWrite(h,TimeToString(when,TIME_DATE|TIME_SECONDS),(string)deal,(string)positionId,
      symbol,side,DoubleToString(volume,8),DoubleToString(entryPrice,digits),
      (bid>0.0?DoubleToString(bid,digits):""),(ask>0.0?DoubleToString(ask,digits):""),
      (candleOpen>0.0?DoubleToString(candleOpen,digits):""),
      (candleHigh>0.0?DoubleToString(candleHigh,digits):""),
      (candleLow>0.0?DoubleToString(candleLow,digits):""),
      (candleClose>0.0?DoubleToString(candleClose,digits):""),candleColor,
      DoubleToString(extremeDistance,digits),
      (point>0.0?DoubleToString(extremeDistance/point,1):""),
      (sl>0.0?DoubleToString(sl,digits):""),shotState,(shot?imagePath:""));
   FileClose(h);
   Print("RAYYAN ENTRY CANDLE RECORDED deal=",deal," side=",side,
      " symbol=",symbol," screenshot=",shotState," csv=",powerSessionCandlePath(positionId));
}

void OnTradeTransaction(const MqlTradeTransaction &trans,const MqlTradeRequest &request,const MqlTradeResult &result){
   if(trans.type!=TRADE_TRANSACTION_DEAL_ADD||trans.deal==0)return;
   if(!HistoryDealSelect(trans.deal))return;
   if((long)HistoryDealGetInteger(trans.deal,DEAL_MAGIC)!=POWERFULL_MAGIC)return;
   powerSessionRefresh();
   powerAppendDealRecord(trans.deal);
   powerCaptureEntryCandle(trans.deal); // Post-confirmation diagnostics; never gates or delays an order.
}


#define RAYYAN_DASH_PREFIX "RAYYAN_DASH_"
uint powerDashboardLastMs=0;
bool powerDashboardNeedsRefresh=true;

void powerDashboardLabel(string name,string value,int x,int y,color clr,int size=9){
   if(ObjectFind(0,name)<0)ObjectCreate(0,name,OBJ_LABEL,0,0,0);
   ObjectSetInteger(0,name,OBJPROP_CORNER,CORNER_LEFT_UPPER);
   ObjectSetInteger(0,name,OBJPROP_XDISTANCE,x);
   ObjectSetInteger(0,name,OBJPROP_YDISTANCE,y);
   ObjectSetInteger(0,name,OBJPROP_COLOR,clr);
   ObjectSetInteger(0,name,OBJPROP_ZORDER,10010);
   ObjectSetInteger(0,name,OBJPROP_FONTSIZE,size);
   ObjectSetString(0,name,OBJPROP_FONT,"Arial");
   ObjectSetString(0,name,OBJPROP_TEXT,value);
   ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
}
void powerDashboardBox(string name,int x,int y,int w,int h,color fill,color edge){
   if(ObjectFind(0,name)<0)ObjectCreate(0,name,OBJ_RECTANGLE_LABEL,0,0,0);
   ObjectSetInteger(0,name,OBJPROP_CORNER,CORNER_LEFT_UPPER);
   ObjectSetInteger(0,name,OBJPROP_XDISTANCE,x);
   ObjectSetInteger(0,name,OBJPROP_YDISTANCE,y);
   ObjectSetInteger(0,name,OBJPROP_XSIZE,w);
   ObjectSetInteger(0,name,OBJPROP_YSIZE,h);
   ObjectSetInteger(0,name,OBJPROP_BGCOLOR,fill);
   ObjectSetInteger(0,name,OBJPROP_COLOR,edge);
   ObjectSetInteger(0,name,OBJPROP_ZORDER,10000);
   ObjectSetInteger(0,name,OBJPROP_BORDER_COLOR,edge);
   ObjectSetInteger(0,name,OBJPROP_BORDER_TYPE,BORDER_FLAT);
   ObjectSetInteger(0,name,OBJPROP_BACK,false);
   ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
}
bool powerAlgoTradingEnabled(){
   return (bool)TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) &&
          (bool)MQLInfoInteger(MQL_TRADE_ALLOWED);
}
int powerLiveCandleDirection(string symbol,ENUM_TIMEFRAMES timeframe){
   MqlRates current[];
   ArraySetAsSeries(current,true);
   if(CopyRates(symbol,timeframe,0,1,current)!=1)return 0;
   if(current[0].close>current[0].open)return 1;
   if(current[0].close<current[0].open)return -1;
   return 0;
}
void powerDashboardDelete(){
   ObjectsDeleteAll(0,RAYYAN_DASH_PREFIX);
}
void powerDashboardStats(string symbol,int &entries,int &wins,int &losses,double &totalProfit,double &totalLoss){
   entries=0;wins=0;losses=0;totalProfit=0.0;totalLoss=0.0;
   // OFF state deliberately shows a clean zeroed dashboard; completed session
   // reports remain saved in RAYYAN_REPORTS and are not deleted.
   if(!powerSessionActive||powerSessionStart<=0)return;
   if(!HistorySelect(powerSessionStart,TimeCurrent()))return;
   for(int i=0;i<HistoryDealsTotal();i++){
      ulong deal=HistoryDealGetTicket(i);
      if(deal==0||(long)HistoryDealGetInteger(deal,DEAL_MAGIC)!=POWERFULL_MAGIC)continue;
      if(HistoryDealGetString(deal,DEAL_SYMBOL)!=symbol)continue;
      ENUM_DEAL_ENTRY entry=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(deal,DEAL_ENTRY);
      if(entry==DEAL_ENTRY_IN||entry==DEAL_ENTRY_INOUT)entries++;
      if(entry!=DEAL_ENTRY_OUT&&entry!=DEAL_ENTRY_OUT_BY&&entry!=DEAL_ENTRY_INOUT)continue;
      double net=HistoryDealGetDouble(deal,DEAL_PROFIT)+HistoryDealGetDouble(deal,DEAL_SWAP)+
                 HistoryDealGetDouble(deal,DEAL_COMMISSION)+HistoryDealGetDouble(deal,DEAL_FEE);
      if(net>0.0){wins++;totalProfit+=net;}
      else if(net<0.0){losses++;totalLoss+=net;}
   }
}
string powerDashboardDirectionText(int direction){
   if(direction>0)return "↑ BULLISH";
   if(direction<0)return "↓ BEARISH";
   return "— NEUTRAL";
}
void powerDashboardOpenPositions(string symbol,color bull,color bear,color neutral){
   int row=0;
   for(int i=PositionsTotal()-1;i>=0&&row<4;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0)continue;
      if((long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      if(PositionGetString(POSITION_SYMBOL)!=symbol)continue;
      ENUM_POSITION_TYPE side=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      double volume=PositionGetDouble(POSITION_VOLUME);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN);
      double net=PositionGetDouble(POSITION_PROFIT)+PositionGetDouble(POSITION_SWAP);
      string sideText=(side==POSITION_TYPE_BUY?"↑ BUY":"↓ SELL");
      string line=sideText+"  "+DoubleToString(volume,2)+"  @ "+DoubleToString(entry,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS))+
                  "   "+(net>=0?"+":"")+DoubleToString(net,2);
      color lineColor=(net>0?bull:(net<0?bear:neutral));
      powerDashboardLabel(RAYYAN_DASH_PREFIX+"OPEN"+IntegerToString(row),line,27,350+row*17,lineColor,8);
      row++;
   }
   for(int j=row;j<4;j++)ObjectDelete(0,RAYYAN_DASH_PREFIX+"OPEN"+IntegerToString(j));
   if(row==0)powerDashboardLabel(RAYYAN_DASH_PREFIX+"OPEN0","No open POWERFULL positions",27,350,neutral,8);
}
void powerDashboardRefresh(bool force=false){
   if(!StandaloneD1Mode)return;
   uint now=GetTickCount();
   if(!force&&powerDashboardLastMs>0&&(uint)(now-powerDashboardLastMs)<250)return;
   powerDashboardLastMs=now;
   string symbol=(StringLen(StandaloneSymbol)>0?StandaloneSymbol:_Symbol);
   bool algoOn=powerAlgoTradingEnabled();
   int entries=0,wins=0,losses=0;
   double totalProfit=0.0,totalLoss=0.0;
   if(algoOn)powerDashboardStats(symbol,entries,wins,losses,totalProfit,totalLoss);
   int closed=wins+losses;
   double winrate=(closed>0?100.0*(double)wins/(double)closed:0.0);

   // These indicators follow the live forming candle body, not delayed swing pivots.
   // They can change from bullish to bearish (and back) before the candle closes.
   int m15=powerLiveCandleDirection(symbol,PERIOD_M15);
   int h1=powerLiveCandleDirection(symbol,PERIOD_H1);
   int h4=powerLiveCandleDirection(symbol,PERIOD_H4);
   string m15Text=(m15>0?"BULLISH":(m15<0?"BEARISH":"DOJI"));
   string h1Text=(h1>0?"BULLISH":(h1<0?"BEARISH":"DOJI"));
   string h4Text=(h4>0?"BULLISH":(h4<0?"BEARISH":"DOJI"));
   color bull=C'35,190,115',bear=C'235,82,82',neutral=C'165,175,190';
   color panel=C'20,27,39',card=C'31,42,58',edge=C'65,82,105';
   color statusColor=(algoOn?bull:C'245,170,85');

   // Solid foreground panel masks all chart candles and objects behind the dashboard.
   powerDashboardBox(RAYYAN_DASH_PREFIX+"BG",8,18,306,442,panel,C'83,101,125');
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"TITLE","RAYYAN  /  POWERFULL",19,27,clrWhite,11);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"SYMBOL",symbol,19,47,C'175,195,220',8);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"STATUSBOX",202,25,100,27,
      (algoOn?C'24,74,58':C'83,56,34'),statusColor);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"STATUS",(algoOn?"●  ALGO ON":"●  ALGO OFF"),211,32,statusColor,9);

   powerDashboardLabel(RAYYAN_DASH_PREFIX+"TRENDHEAD","LIVE CANDLE DIRECTION",19,72,C'185,200,220',8);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"M15BOX",18,88,90,43,card,edge);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"H1BOX",114,88,90,43,card,edge);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"H4BOX",210,88,90,43,card,edge);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"M15","M15  "+powerDashboardDirectionText(m15),25,103,(m15>0?bull:(m15<0?bear:neutral)),8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"H1","H1    "+powerDashboardDirectionText(h1),121,103,(h1>0?bull:(h1<0?bear:neutral)),8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"H4","H4    "+powerDashboardDirectionText(h4),217,103,(h4>0?bull:(h4<0?bear:neutral)),8);

   powerDashboardLabel(RAYYAN_DASH_PREFIX+"STATHEAD","SESSION PERFORMANCE",19,143,C'185,200,220',8);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"WINRATEBOX",18,158,138,51,card,edge);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"WINBOX",164,158,65,51,card,edge);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"LOSSBOX",237,158,63,51,card,edge);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"WINRATE","WINRATE",27,166,C'185,200,220',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"WINRATEVAL",DoubleToString(winrate,2)+"%",27,181,(closed>0?bull:neutral),14);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"WINS","WIN",174,166,C'185,200,220',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"WINSVAL",IntegerToString(wins),174,182,bull,13);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"LOSSES","LOSS",246,166,C'185,200,220',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"LOSSESVAL",IntegerToString(losses),246,182,bear,13);

   powerDashboardBox(RAYYAN_DASH_PREFIX+"ENTRYBOX",18,218,138,43,card,edge);
   powerDashboardBox(RAYYAN_DASH_PREFIX+"CLOSEDBOX",164,218,136,43,card,edge);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"ENTRIES","TOTAL ENTRY",27,225,C'185,200,220',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"ENTRIESVAL",IntegerToString(entries),27,239,clrWhite,12);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"CLOSED","CLOSED TRADES",173,225,C'185,200,220',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"CLOSEDVAL",IntegerToString(closed),173,239,clrWhite,12);

   powerDashboardBox(RAYYAN_DASH_PREFIX+"PROFITBOX",18,270,138,43,C'24,61,51',C'52,125,94');
   powerDashboardBox(RAYYAN_DASH_PREFIX+"LOSSVALBOX",164,270,136,43,C'69,39,45',C'143,69,78');
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"PROFIT","TOTAL PROFIT",27,277,C'185,220,200',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"PROFITVAL",DoubleToString(totalProfit,2),27,291,bull,11);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"LOSS","TOTAL LOSS",173,277,C'230,190,195',8);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"LOSSVAL",DoubleToString(totalLoss,2),173,291,bear,11);

   string btn=RAYYAN_DASH_PREFIX+"REFRESH";
   if(ObjectFind(0,btn)<0)ObjectCreate(0,btn,OBJ_BUTTON,0,0,0);
   ObjectSetInteger(0,btn,OBJPROP_CORNER,CORNER_LEFT_UPPER);
   ObjectSetInteger(0,btn,OBJPROP_XDISTANCE,18);ObjectSetInteger(0,btn,OBJPROP_YDISTANCE,418);
   ObjectSetInteger(0,btn,OBJPROP_XSIZE,282);ObjectSetInteger(0,btn,OBJPROP_YSIZE,20);
   ObjectSetInteger(0,btn,OBJPROP_ZORDER,10020);
   ObjectSetString(0,btn,OBJPROP_TEXT,"REFRESH DATA");
   ObjectSetInteger(0,btn,OBJPROP_BGCOLOR,C'52,76,105');
   ObjectSetInteger(0,btn,OBJPROP_COLOR,clrWhite);
   ObjectSetInteger(0,btn,OBJPROP_BORDER_COLOR,C'100,125,155');
   ObjectSetInteger(0,btn,OBJPROP_FONTSIZE,8);
   ObjectSetInteger(0,btn,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,btn,OBJPROP_HIDDEN,true);
   powerDashboardLabel(RAYYAN_DASH_PREFIX+"OPENHEAD","RUNNING ENTRY  /  OPEN POSITIONS",19,326,C'185,200,220',8);
   powerDashboardOpenPositions(symbol,bull,bear,neutral);
   ChartRedraw(0);
}
void OnChartEvent(const int id,const long &lparam,const double &dparam,const string &sparam){
   if(id==CHARTEVENT_OBJECT_CLICK&&sparam==RAYYAN_DASH_PREFIX+"REFRESH"){
      ObjectSetInteger(0,sparam,OBJPROP_STATE,false);
      powerDashboardRefresh(true);
   }
}

int OnInit(){
   // Remove legacy chart-attached EMA/MA indicators and old Rayyan EMA objects.
   // The EA below is the sole owner of the two colored EMA curves.
   cleanupDuplicateEmaLayers();
   hEma50=iMA(_Symbol,_Period,50,0,MODE_EMA,PRICE_CLOSE);
   hEma200=iMA(_Symbol,_Period,200,0,MODE_EMA,PRICE_CLOSE);
   if(StandaloneD1Mode){
      powerSessionRefresh();
      string directSymbol=(StringLen(StandaloneSymbol)>0?StandaloneSymbol:_Symbol);
      powerSymbols[0]=directSymbol; powerSymbols[1]=""; powerSymbols[2]="";
      if(!SymbolSelect(directSymbol,true)){
         Print("D1 STANDALONE CONFIG ERROR: symbol unavailable: ",directSymbol);
         return(INIT_FAILED);
      }
      powerCfg[0].enabled=(StandaloneLot>0.0&&StandaloneSLBuyPoints>0&&StandaloneSLSellPoints>0
         &&StandaloneMaxLayers>=1&&StandaloneMaxLayers<=5);
      powerCfg[0].lot=StandaloneLot;
      powerCfg[0].pipSize=SymbolInfoDouble(directSymbol,SYMBOL_POINT);
      powerCfg[0].lotStep=SymbolInfoDouble(directSymbol,SYMBOL_VOLUME_STEP);
      powerCfg[0].usePointStops=true;
      powerCfg[0].slEntry1Points=StandaloneSLBuyPoints;
      powerCfg[0].slEntry2Points=StandaloneSLSellPoints;
      powerCfg[0].maxLayers=StandaloneMaxLayers;
      powerCfg[0].minProfitLockPoints=5;
      powerCfg[0].minHoldCandles=0;
      powerCfg[0].reason=(powerCfg[0].enabled?"STANDALONE_READY":"STANDALONE_CONFIG_INVALID");
      powerConfigReceivedMs[0]=1;
      FolderCreate(POWERFULL_REPORT_DIR);
      // Session reports are generated only inside the active Algo Trading session.
      int symbolDigits=(int)SymbolInfoInteger(directSymbol,SYMBOL_DIGITS);
      double symbolPoint=SymbolInfoDouble(directSymbol,SYMBOL_POINT);
      Print("D1 STANDALONE MODE symbol=",directSymbol," lot=",DoubleToString(StandaloneLot,2),
         " Digits=",symbolDigits," Point=",DoubleToString(symbolPoint,symbolDigits+2),
         " SLbuy=",StandaloneSLBuyPoints," POINT (price distance=",DoubleToString(StandaloneSLBuyPoints*symbolPoint,symbolDigits),")",
         " SLsell=",StandaloneSLSellPoints," POINT (price distance=",DoubleToString(StandaloneSLSellPoints*symbolPoint,symbolDigits),")",
         " maxLayers=",StandaloneMaxLayers," enabled=",powerCfg[0].enabled,
         " | SL inputs use POINT only; no pip conversion");
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
   if(StandaloneD1Mode){FolderCreate(POWERFULL_REPORT_DIR);powerSessionRefresh();}
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
   if(StandaloneD1Mode){powerSessionRefresh();powerDashboardRefresh(false);}
   if(!StandaloneD1Mode) manageTP2RetraceProtection(); // Standalone mode manages D1 positions only.
   // Opportunistic fast path; no gateway config request is issued from this event.
   managePowerfullAllMarkets(false);
}

void OnTimer(){
   if(StandaloneD1Mode){ updateHarmonicOverlay(); powerSessionRefresh(); powerDashboardRefresh(false); managePowerfullAllMarkets(false); return; }
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


#define RAYYAN_HARMONIC_PREFIX "RAYYAN_HARM_"
struct HarmonicPivot { int shift; datetime time; double price; bool isHigh; };
bool harmonicNear(double value,double target,double tolerance){
   return(MathAbs(value-target)<=MathMax(0.015,tolerance)*target);
}
bool harmonicBetween(double value,double low,double high){
   return(value>=low && value<=high);
}
void clearHarmonicOverlay(){
   int total=ObjectsTotal(0,0,-1);
   for(int i=total-1;i>=0;i--){
      string name=ObjectName(0,i,0,-1);
      if(StringFind(name,RAYYAN_HARMONIC_PREFIX)==0) ObjectDelete(0,name);
   }
}
void harmonicTrend(string name,datetime t1,double p1,datetime t2,double p2,color c,int width=2){
   if(ObjectFind(0,name)<0) ObjectCreate(0,name,OBJ_TREND,0,t1,p1,t2,p2);
   else { ObjectMove(0,name,0,t1,p1); ObjectMove(0,name,1,t2,p2); }
   ObjectSetInteger(0,name,OBJPROP_COLOR,c);
   ObjectSetInteger(0,name,OBJPROP_WIDTH,width);
   ObjectSetInteger(0,name,OBJPROP_RAY_RIGHT,false);
   ObjectSetInteger(0,name,OBJPROP_RAY_LEFT,false);
   ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
}
void harmonicText(string name,datetime t,double p,string label,color c){
   if(ObjectFind(0,name)<0) ObjectCreate(0,name,OBJ_TEXT,0,t,p);
   else ObjectMove(0,name,0,t,p);
   ObjectSetString(0,name,OBJPROP_TEXT,label);
   ObjectSetInteger(0,name,OBJPROP_COLOR,c);
   ObjectSetInteger(0,name,OBJPROP_FONTSIZE,8);
   ObjectSetInteger(0,name,OBJPROP_ANCHOR,ANCHOR_LEFT_LOWER);
   ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
}
void harmonicZone(string name,datetime t1,double top,datetime t2,double bottom,color c){
   if(ObjectFind(0,name)<0) ObjectCreate(0,name,OBJ_RECTANGLE,0,t1,top,t2,bottom);
   else { ObjectMove(0,name,0,t1,top); ObjectMove(0,name,1,t2,bottom); }
   ObjectSetInteger(0,name,OBJPROP_COLOR,c);
   ObjectSetInteger(0,name,OBJPROP_STYLE,STYLE_DOT);
   ObjectSetInteger(0,name,OBJPROP_WIDTH,1);
   ObjectSetInteger(0,name,OBJPROP_FILL,true);
   ObjectSetInteger(0,name,OBJPROP_BACK,true);
   ObjectSetInteger(0,name,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,name,OBJPROP_HIDDEN,true);
}
void harmonicAppendDetection(string symbol,string pattern,string side,
                             datetime dTime,double x,double a,double b,double c,double d,
                             double rAB,double rBC,double rCD,double rAD,
                             double przLow,double przHigh,int h1){
   string file="RAYYAN_HARMONIC_DETECTIONS.csv";
   int h=FileOpen(file,FILE_READ|FILE_WRITE|FILE_CSV|FILE_ANSI|FILE_COMMON,';');
   if(h==INVALID_HANDLE)return;
   if(FileSize(h)==0)
      FileWrite(h,"event","time","symbol","timeframe","pattern","direction",
                "X","A","B","C","D","AB_XA","BC_AB","CD_BC","AD_XA",
                "PRZ_LOW","PRZ_HIGH","H1_STRUCTURE","evaluation");
   string key="RHY_"+symbol+"_"+IntegerToString((long)dTime);
   if(!GlobalVariableCheck(key)){
      FileSeek(h,0,SEEK_END);
      int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);
      FileWrite(h,"DETECTED",TimeToString(TimeCurrent(),TIME_DATE|TIME_SECONDS),symbol,
         EnumToString(_Period),pattern,side,
         DoubleToString(x,digits),DoubleToString(a,digits),DoubleToString(b,digits),
         DoubleToString(c,digits),DoubleToString(d,digits),
         DoubleToString(rAB,4),DoubleToString(rBC,4),DoubleToString(rCD,4),DoubleToString(rAD,4),
         DoubleToString(przLow,digits),DoubleToString(przHigh,digits),
         (h1>0?"BULLISH":(h1<0?"BEARISH":"NEUTRAL")),"PENDING_MANUAL_REVIEW");
      GlobalVariableSet(key,(double)TimeCurrent());
   }
   FileClose(h);
}
void updateHarmonicOverlay(){
   if(!EnableHarmonicOverlay){ clearHarmonicOverlay(); return; }
   int depth=(int)MathMax(2,MathMin(8,HarmonicPivotDepth));
   int bars=(int)MathMax(80,MathMin(600,HarmonicLookbackBars));
   MqlRates rates[];
   ArraySetAsSeries(rates,true);
   int copied=CopyRates(_Symbol,_Period,0,bars,rates);
   if(copied<depth*2+30)return;
   HarmonicPivot pivots[];
   ArrayResize(pivots,0);
   // Closed bars only; a pivot is confirmed after depth bars on both sides.
   for(int i=depth+1;i<copied-depth;i++){
      bool hi=true,lo=true;
      for(int k=1;k<=depth;k++){
         if(rates[i].high<=rates[i-k].high || rates[i].high<rates[i+k].high)hi=false;
         if(rates[i].low>=rates[i-k].low || rates[i].low>rates[i+k].low)lo=false;
      }
      if(hi==lo)continue;
      HarmonicPivot p;
      p.shift=i;p.time=rates[i].time;p.isHigh=hi;p.price=(hi?rates[i].high:rates[i].low);
      int n=ArraySize(pivots);
      if(n>0&&pivots[n-1].isHigh==p.isHigh){
         bool replace=(p.isHigh?p.price>pivots[n-1].price:p.price<pivots[n-1].price);
         if(replace)pivots[n-1]=p;
      }else{ArrayResize(pivots,n+1);pivots[n]=p;}
   }
   int n=ArraySize(pivots);
   if(n<5){clearHarmonicOverlay();return;}
   string pattern="";
   bool bullish=false;
   HarmonicPivot X,A,B,C,D;
   double xa=0,ab=0,bc=0,cd=0,ad=0,rAB=0,rBC=0,rCD=0,rAD=0;
   double tol=MathMax(0.03,MathMin(0.25,HarmonicRatioTolerance));
   // Search newest-to-oldest valid alternating five-pivot sequence. A newer
   // incomplete/invalid sequence must not hide the most recent valid pattern.
   for(int q=n-5;q>=0;q--){
      X=pivots[q];A=pivots[q+1];B=pivots[q+2];C=pivots[q+3];D=pivots[q+4];
      if(X.isHigh==A.isHigh||A.isHigh==B.isHigh||B.isHigh==C.isHigh||C.isHigh==D.isHigh)continue;
      xa=MathAbs(A.price-X.price);ab=MathAbs(B.price-A.price);
      bc=MathAbs(C.price-B.price);cd=MathAbs(D.price-C.price);ad=MathAbs(D.price-A.price);
      if(xa<=_Point*2||ab<=_Point*2||bc<=_Point*2)continue;
      rAB=ab/xa;rBC=bc/ab;rCD=cd/bc;rAD=ad/xa;
      bullish=(!X.isHigh&&A.isHigh&&!B.isHigh&&C.isHigh&&!D.isHigh);
      bool bearish=(X.isHigh&&!A.isHigh&&B.isHigh&&!C.isHigh&&D.isHigh);
      if(!bullish&&!bearish)continue;
      pattern="";
      if(harmonicNear(rAB,0.618,tol)&&harmonicBetween(rBC,0.382-tol,0.886+tol)&&
         harmonicBetween(rCD,1.27-tol,1.618+tol)&&harmonicNear(rAD,0.786,tol))pattern="GARTLEY";
      else if(harmonicBetween(rAB,0.382-tol,0.500+tol)&&harmonicBetween(rBC,0.382-tol,0.886+tol)&&
         harmonicBetween(rCD,1.618-tol,2.618+tol)&&harmonicNear(rAD,0.886,tol))pattern="BAT";
      else if(harmonicNear(rAB,0.786,tol)&&harmonicBetween(rBC,0.382-tol,0.886+tol)&&
         harmonicBetween(rCD,1.618-tol,2.240+tol)&&harmonicBetween(rAD,1.27-tol,1.618+tol))pattern="BUTTERFLY";
      else if(harmonicBetween(rAB,0.382-tol,0.618+tol)&&harmonicBetween(rBC,0.382-tol,0.886+tol)&&
         harmonicBetween(rCD,2.24-tol,3.618+tol)&&harmonicNear(rAD,1.618,tol))pattern="CRAB";
      if(pattern!="")break;
   }
   if(pattern==""){clearHarmonicOverlay();return;}
   // PRZ is the overlap of the XA retracement projection and the BC extension
   // projection. If they do not overlap, do not draw a fabricated PRZ.
   double xaRatio=0.786,bcRatio=1.618;
   if(pattern=="BAT"){xaRatio=0.886;bcRatio=2.0;}
   else if(pattern=="BUTTERFLY"){xaRatio=1.27;bcRatio=1.618;}
   else if(pattern=="CRAB"){xaRatio=1.618;bcRatio=2.618;}
   double xaTarget=(bullish?A.price-xa*xaRatio:A.price+xa*xaRatio);
   double bcTarget=(bullish?C.price-bc*bcRatio:C.price+bc*bcRatio);
   double halfXA=xa*MathMax(0.015,tol*0.5);
   double halfBC=bc*MathMax(0.015,tol*0.5);
   double xaLo=xaTarget-halfXA,xaHi=xaTarget+halfXA;
   double bcLo=bcTarget-halfBC,bcHi=bcTarget+halfBC;
   double przLow=MathMax(xaLo,bcLo),przHigh=MathMin(xaHi,bcHi);
   if(przLow>przHigh){
      // Ratios can vary within valid pattern ranges; use midpoint only when
      // projected bands are close enough to be a meaningful confluence.
      double gap=MathMax(xaLo,bcLo)-MathMin(xaHi,bcHi);
      if(gap>xa*tol){clearHarmonicOverlay();return;}
      double mid=(xaTarget+bcTarget)*0.5;
      double width=MathMax(_Point*5,gap*0.5);
      przLow=mid-width;przHigh=mid+width;
   }
   clearHarmonicOverlay();
   color c=(bullish?C'34,197,94':C'239,68,68');
   string side=(bullish?"BUY":"SELL"),tag=pattern+" "+side;
   harmonicTrend(RAYYAN_HARMONIC_PREFIX+"XA",X.time,X.price,A.time,A.price,c,2);
   harmonicTrend(RAYYAN_HARMONIC_PREFIX+"AB",A.time,A.price,B.time,B.price,c,2);
   harmonicTrend(RAYYAN_HARMONIC_PREFIX+"BC",B.time,B.price,C.time,C.price,c,2);
   harmonicTrend(RAYYAN_HARMONIC_PREFIX+"CD",C.time,C.price,D.time,D.price,c,2);
   harmonicText(RAYYAN_HARMONIC_PREFIX+"X",X.time,X.price,"X",clrGold);
   harmonicText(RAYYAN_HARMONIC_PREFIX+"A",A.time,A.price,"A",clrGold);
   harmonicText(RAYYAN_HARMONIC_PREFIX+"B",B.time,B.price,"B",clrGold);
   harmonicText(RAYYAN_HARMONIC_PREFIX+"C",C.time,C.price,"C",clrGold);
   harmonicText(RAYYAN_HARMONIC_PREFIX+"D",D.time,D.price,"D",clrGold);
   int h1=powerConfirmedSwingStructure(_Symbol,PERIOD_H1);
   string h1Text=(h1>0?"H1 BULLISH":(h1<0?"H1 BEARISH":"H1 NEUTRAL"));
   harmonicText(RAYYAN_HARMONIC_PREFIX+"TITLE",D.time,D.price,
      tag+" | "+h1Text+" | AB/XA "+DoubleToString(rAB,3)+
      " BC/AB "+DoubleToString(rBC,3)+" CD/BC "+DoubleToString(rCD,3)+
      " AD/XA "+DoubleToString(rAD,3),c);
   datetime rightTime=TimeCurrent()+(datetime)(PeriodSeconds(_Period)*18);
   harmonicZone(RAYYAN_HARMONIC_PREFIX+"PRZ",D.time,przHigh,rightTime,przLow,c);
   harmonicText(RAYYAN_HARMONIC_PREFIX+"PRZ_LABEL",rightTime,przHigh,
      "PRZ "+DoubleToString(przLow,_Digits)+" - "+DoubleToString(przHigh,_Digits),c);
   harmonicAppendDetection(_Symbol,pattern,side,D.time,X.price,A.price,B.price,C.price,D.price,
                           rAB,rBC,rCD,rAD,przLow,przHigh,h1);
   ChartRedraw(0);
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
   string maxLayersText=field(body,"maxLayers");
   bool pendingLimitEnabled=(pendingLimitFlag=="1");
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
      &&maxLayers>=1&&maxLayers<=5;
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
         type==ORDER_TYPE_BUY_STOP||type==ORDER_TYPE_SELL_STOP||
         type==ORDER_TYPE_BUY_STOP_LIMIT||type==ORDER_TYPE_SELL_STOP_LIMIT)count++;
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
                    type==ORDER_TYPE_BUY_STOP||type==ORDER_TYPE_SELL_STOP||
                    type==ORDER_TYPE_BUY_STOP_LIMIT||type==ORDER_TYPE_SELL_STOP_LIMIT);
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
void powerTryD1ExtremeEntry(int index,const MqlTick &tick,datetime barTime,
                             double candleOpen,double candleHigh,double candleLow,
                             double askHigh,double askLow,
                             bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return;
   string symbol=powerSymbols[index];

   // M5 closing safety window: do not open a new BUY/SELL during the final 40 seconds.
   // This is a pre-close blackout only; it is NOT a 40-second warm-up after a new bar.
   long tickSeconds=(long)(tick.time_msc/1000);
   long candleElapsed=(long)(tickSeconds-(long)barTime);
   if(barTime>0&&candleElapsed>=260&&candleElapsed<300){
      powerPublishState(index,"D1_FINAL_40S_NO_ENTRY",
         "New entries paused during the final 40 seconds of the current M5 candle; existing positions remain managed",barTime);
      return;
   }

   if(DemoOnly&&AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
      powerPublishState(index,"BLOCKED_REAL_ACCOUNT",
         "D1 standalone is restricted to a demo account while DemoOnly=true",barTime);
      return;
   }

   double point=SymbolInfoDouble(symbol,SYMBOL_POINT);
   double ts=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   // Spread is informational only; it is never an entry filter or blocker.
   if(point<=0.0||ts<=0.0){
      powerPublishState(index,"D1_WAIT_DATA","Invalid MT5 point/tick geometry",barTime);
      return;
   }
   // H1 is the primary directional authority for POWERFULL entries.
   // M15 is contextual structure/pullback information only: it can warn that
   // price is pulling back or temporarily opposing H1, but its direction must
   // never veto an otherwise valid H1-aligned M5 extreme entry.
   int entryM15=powerConfirmedSwingStructure(symbol,PERIOD_M15);
   int entryH1=powerConfirmedSwingStructure(symbol,PERIOD_H1);
   int entryDirection=entryH1;
   if(entryDirection==0){
      powerPublishState(index,"D1_WAIT_H1_DIRECTION",
         "Entry paused: H1 confirmed swing structure is neutral/unavailable; M15 context="+
         IntegerToString(entryM15),barTime);
      return;
   }
   string m15Context=(entryM15==entryH1?"M15 aligned":
                      (entryM15==-entryH1?"M15 pullback/opposing warning":"M15 neutral"));
   Print("D1 ENTRY BIAS symbol=",symbol," H1=",entryDirection>0?"BULLISH":"BEARISH",
         " M15=",entryM15," context=",m15Context,
         " action=H1_authority_M15_nonblocking_M5_extreme_timing");
   if(!powerCfg[index].usePointStops){
      powerPublishState(index,"BLOCKED_SL_POINTS","D1 requires BUY/SELL SL points",barTime);
      return;
   }

   ENUM_ACCOUNT_MARGIN_MODE mm=(ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE);
   if((mm!=ACCOUNT_MARGIN_MODE_RETAIL_HEDGING||AccountInfoInteger(ACCOUNT_HEDGE_ALLOWED)==0)&&
      powerCfg[index].maxLayers>1){
      powerPublishState(index,"BLOCKED_NETTING_ACCOUNT","D1 layers require hedging mode",barTime);
      return;
   }

   int total=0,buyN=0,sellN=0;
   for(int p=PositionsTotal()-1;p>=0;p--){
      ulong ticket=PositionGetTicket(p);
      if(ticket==0||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      total++;
      ENUM_POSITION_TYPE ps=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      if(ps==POSITION_TYPE_BUY)buyN++;
      else if(ps==POSITION_TYPE_SELL)sellN++;
   }

   int cap=MathMax(1,MathMin(5,powerCfg[index].maxLayers));
   if(total>=cap){
      powerPublishState(index,"D1_LAYER_CAP",
         "Layer cap "+IntegerToString(total)+"/"+IntegerToString(cap),barTime);
      return;
   }
   if(buyN>0&&sellN>0){
      powerPublishState(index,"D1_MIXED_SIDE_GUARD",
         "Both directions already exist; no additional entries until the basket is resolved",barTime);
      return;
   }

   // No warm-up timer: evaluate the current M5 candle as soon as live OHLC is valid.

   double range=candleHigh-candleLow;
   if(range<=0.0){
      powerPublishState(index,"D1_CANDLE_BUILDING",
         "Waiting for current M5 candle to establish its live High/Low",barTime);
      return;
   }

   // Reset only the live candle OHLC measurement at a new M5 bar.
   // Last successful BUY Low / SELL High remains persistent across candle changes,
   // so continuation layers require a genuinely newer extreme than the prior fill.
   // No timer, reversal wait, or tick-count confirmation is used.

   // M5 candle colour is a hard entry condition:
   // BUY only while the live M5 candle is RED; SELL only while it is GREEN.
   // First let the matching extreme form, then require price to retreat at least
   // two tradable ticks from that extreme while the candle still has its required colour.
   // This is an extreme-weakening confirmation, not a timer or tick-count pattern.
   bool sellFresh=(powerMomentumLastSellHigh[index]<=0.0||
                   candleHigh>=powerMomentumLastSellHigh[index]+ts);
   bool buyFresh=(powerMomentumLastBuyLow[index]<=0.0||
                  candleLow<=powerMomentumLastBuyLow[index]-ts);

   double upperWick=candleHigh-MathMax(candleOpen,tick.bid);
   double lowerWick=MathMin(candleOpen,tick.bid)-candleLow;
   // Retraction values are diagnostics only; they are NOT entry conditions.
   double buyBidRecovery=tick.bid-candleLow;
   double buyAskRecovery=tick.ask-askLow;
   double sellBidRetreat=candleHigh-tick.bid;
   double sellAskRetreat=askHigh-tick.ask;

   double buyOpenExtension=(candleOpen-candleLow)/range;
   double sellOpenExtension=(candleHigh-candleOpen)/range;
   double bidLocation=(tick.bid-candleLow)/range;
   double askLocation=(tick.ask-candleLow)/range;

   bool candleRed=(tick.bid<candleOpen);
   bool candleGreen=(tick.bid>candleOpen);

   // "Rubber tension" is the Open-to-extreme extension as a share of the
   // current candle range. Enter at >=70% stretch while Bid/Ask remain in
   // the outer 15-20% zone beside the live Low/High.
   const double D1_EXTREME_STRETCH_MIN=0.70;
   const double D1_EXTREME_BUY_BID_EDGE=0.15;
   const double D1_EXTREME_BUY_ASK_EDGE=0.20;
   const double D1_EXTREME_SELL_BID_EDGE=0.85;
   const double D1_EXTREME_SELL_ASK_EDGE=0.80;
   // Layer 1 must first establish the full 70% Open-to-extreme stretch.
   // Continuation layers already have an established basket: do not make them
   // wait for the candle range ratio to stretch again. They still require a
   // genuinely newer Low/High, the same Bid/Ask edge zone, and candle structure.
   bool buyStretchReady=(buyN>0||buyOpenExtension>=D1_EXTREME_STRETCH_MIN);
   bool sellStretchReady=(sellN>0||sellOpenExtension>=D1_EXTREME_STRETCH_MIN);
   bool buyExtremeDistribution=(buyStretchReady&&
                                bidLocation<=D1_EXTREME_BUY_BID_EDGE&&
                                askLocation<=D1_EXTREME_BUY_ASK_EDGE);
   bool sellExtremeDistribution=(sellStretchReady&&
                                 bidLocation>=D1_EXTREME_SELL_BID_EDGE&&
                                 askLocation>=D1_EXTREME_SELL_ASK_EDGE);
   bool buyCandleStructure=candleRed;
   bool sellCandleStructure=candleGreen;
   double weakeningDistance=2.0*ts;
   bool buyExtremeWeakening=(buyBidRecovery>=weakeningDistance&&buyAskRecovery>=weakeningDistance);
   bool sellExtremeWeakening=(sellBidRetreat>=weakeningDistance&&sellAskRetreat>=weakeningDistance);
   bool buyReady=(entryDirection>0&&buyFresh&&buyExtremeDistribution&&buyCandleStructure&&
                  buyExtremeWeakening&&buyN<cap);
   bool sellReady=(entryDirection<0&&sellFresh&&sellExtremeDistribution&&sellCandleStructure&&
                   sellExtremeWeakening&&sellN<cap);
   double buyScore=buyOpenExtension+lowerWick/range+
                   buyBidRecovery/range+buyAskRecovery/range;
   double sellScore=sellOpenExtension+upperWick/range+
                    sellBidRetreat/range+sellAskRetreat/range;
   if(!buyReady&&!sellReady){
      powerPublishState(index,"D1_LIVE_CANDLE_DISTRIBUTION",
         "M5 live distribution | range="+DoubleToString(range/ts,1)+"t"+
         " | Open->Low/High="+DoubleToString(buyOpenExtension*100.0,1)+"/"+
            DoubleToString(sellOpenExtension*100.0,1)+"%"+
         " | MTF direction="+(entryDirection>0?"BUY":"SELL")+
          " | Bid/Ask location="+DoubleToString(bidLocation*100.0,1)+"/"+
            DoubleToString(askLocation*100.0,1)+"%"+
         " | wick L/U="+DoubleToString(lowerWick/range*100.0,1)+"/"+
            DoubleToString(upperWick/range*100.0,1)+"%"+
         " | pullback diagnostic BUY B/A="+DoubleToString(buyBidRecovery/range*100.0,1)+"/"+
            DoubleToString(buyAskRecovery/range*100.0,1)+"%"+
         " | SELL pullback diagnostic B/A="+DoubleToString(sellBidRetreat/range*100.0,1)+"/"+
            DoubleToString(sellAskRetreat/range*100.0,1)+"%"+
         " | M5 colour gate=BUY RED/SELL GREEN"+
         " | extreme weakening BUY="+DoubleToString(MathMin(buyBidRecovery,buyAskRecovery)/ts,1)+
            "t SELL="+DoubleToString(MathMin(sellBidRetreat,sellAskRetreat)/ts,1)+"t (need 2t)"+
         " | rubberStretch=70% first-layer-only; continuation=new-extreme+BidAsk-edge"+
         " | spread(info)="+DoubleToString((tick.ask-tick.bid)/ts,1)+"t"+
         " | layers="+IntegerToString(total)+"/"+IntegerToString(cap),barTime);
      return;
   }
   // Add layers only in the existing basket's direction; no opposite-side hedge.
   if(buyN>0)sellReady=false;
   if(sellN>0)buyReady=false;
   if(!buyReady&&!sellReady)return;

   int side=(buyReady?1:-1);
   int slPts=(side>0?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
   double slDist=slPts*point;
   double minStop=SymbolInfoInteger(symbol,SYMBOL_TRADE_STOPS_LEVEL)*point;
   if(slPts<=0||slDist+1e-10<minStop+ts){
      powerPublishState(index,"BLOCKED_SL_POINTS","SL below broker stop distance",barTime);
      return;
   }

   double lot=powerCfg[index].lot;
   double minLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MIN);
   double maxLot=SymbolInfoDouble(symbol,SYMBOL_VOLUME_MAX);
   double step=SymbolInfoDouble(symbol,SYMBOL_VOLUME_STEP);
   if(step<=0.0||lot<minLot-1e-8||lot>maxLot+1e-8){
      powerPublishState(index,"BLOCKED_LOT","Configured lot outside broker limits",barTime);
      return;
   }

   // BUY executes at Ask; SELL executes at Bid. Signal location remains candle-distribution based.
   double px=(side>0?tick.ask:tick.bid);
   double sl=(side>0?powerFloorToTick(symbol,px-slDist):powerCeilToTick(symbol,px+slDist));
   ENUM_ORDER_TYPE marginOrderType=(side>0?ORDER_TYPE_BUY:ORDER_TYPE_SELL);

   // Use MT5's full request pre-check instead of OrderCalcMargin's isolated estimate.
   // OrderCheck evaluates the proposed deal against the account's current positions,
   // margin and broker request rules; this avoids a false BLOCKED_MARGIN_CALC result.
   long fillingModes=SymbolInfoInteger(symbol,SYMBOL_FILLING_MODE);
   long executionMode=SymbolInfoInteger(symbol,SYMBOL_TRADE_EXEMODE);
   ENUM_ORDER_TYPE_FILLING filling=ORDER_FILLING_RETURN;
   if((fillingModes&SYMBOL_FILLING_FOK)!=0)
      filling=ORDER_FILLING_FOK;
   else if((fillingModes&SYMBOL_FILLING_IOC)!=0)
      filling=ORDER_FILLING_IOC;
   else if(executionMode==SYMBOL_TRADE_EXECUTION_MARKET){
      powerPublishState(index,"BLOCKED_FILLING_MODE","No supported FOK/IOC filling mode for Market Execution",barTime);
      Print("D1 ORDER CHECK BLOCK symbol=",symbol," reason=NO_SUPPORTED_FILLING_MODE",
            " filling_modes=",fillingModes," execution_mode=",executionMode);
      return;
   }

   MqlTradeRequest marginRequest={};
   MqlTradeCheckResult marginCheck={};
   marginRequest.action=TRADE_ACTION_DEAL;
   marginRequest.magic=POWERFULL_MAGIC;
   marginRequest.symbol=symbol;
   marginRequest.volume=lot;
   marginRequest.type=marginOrderType;
   marginRequest.price=px;
   marginRequest.sl=sl;
   marginRequest.deviation=(ulong)powerCfg[index].deviationPoints;
   marginRequest.type_filling=filling;
   marginRequest.type_time=ORDER_TIME_GTC;
   marginRequest.comment=(side>0?"D1-A5-LIVE-RED-LOW-BUY":"D1-A5-LIVE-GREEN-HIGH-SELL");

   ResetLastError();
   bool marginCheckOk=OrderCheck(marginRequest,marginCheck);
   int marginCheckError=GetLastError();
   if(!marginCheckOk||!MathIsValidNumber(marginCheck.margin_free)||marginCheck.margin_free<0.0){
      powerPublishState(index,"BLOCKED_INSUFFICIENT_MARGIN",
         "MT5 OrderCheck rejected the proposed deal or projected free margin is negative",barTime);
      Print("D1 ORDER CHECK FAILED symbol=",symbol," side=",(side>0?"BUY":"SELL"),
            " lot=",DoubleToString(lot,2)," price=",DoubleToString(px,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " check_ok=",(marginCheckOk?"true":"false"),
            " retcode=",marginCheck.retcode,
            " required_margin=",DoubleToString(marginCheck.margin,2),
            " projected_free_margin=",DoubleToString(marginCheck.margin_free,2),
            " projected_equity=",DoubleToString(marginCheck.equity,2),
            " margin_level=",DoubleToString(marginCheck.margin_level,2),
            " comment=",marginCheck.comment,
            " err=",marginCheckError);
      return;
   }
   powerTrade.SetExpertMagicNumber(POWERFULL_MAGIC);
   powerTrade.SetDeviationInPoints(powerCfg[index].deviationPoints);
   powerTrade.SetTypeFillingBySymbol(symbol);
   powerTrade.SetAsyncMode(false);
   ResetLastError();
   bool sent=(side>0?
      powerTrade.Buy(lot,symbol,0.0,sl,0.0,"D1-A5-LIVE-RED-LOW-BUY"):
      powerTrade.Sell(lot,symbol,0.0,sl,0.0,"D1-A5-LIVE-GREEN-HIGH-SELL"));
   uint rc=powerTrade.ResultRetcode();

   if(sent&&(rc==TRADE_RETCODE_DONE||rc==TRADE_RETCODE_PLACED||
             rc==TRADE_RETCODE_DONE_PARTIAL)){
      // Verify the broker-side SL on the newly opened POWERFULL position.
      ulong verifiedTicket=0;
      long newestPositionMs=-1;
      double actualSL=0.0;
      for(int p=PositionsTotal()-1;p>=0;p--){
         ulong pt=PositionGetTicket(p);
         if(pt==0||!PositionSelectByTicket(pt))continue;
         if(PositionGetString(POSITION_SYMBOL)!=symbol||
            (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
         ENUM_POSITION_TYPE pside=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
         if((side>0&&pside!=POSITION_TYPE_BUY)||(side<0&&pside!=POSITION_TYPE_SELL))continue;
         long openedMs=PositionGetInteger(POSITION_TIME_MSC);
         if(openedMs>=newestPositionMs){
            newestPositionMs=openedMs;
            verifiedTicket=pt;
            actualSL=PositionGetDouble(POSITION_SL);
         }
      }
      if(verifiedTicket>0&&(actualSL<=0.0||MathAbs(actualSL-sl)>ts*0.5)){
         ResetLastError();
         bool modified=powerTrade.PositionModify(verifiedTicket,sl,0.0);
         uint modifyRc=powerTrade.ResultRetcode();
         if(PositionSelectByTicket(verifiedTicket))actualSL=PositionGetDouble(POSITION_SL);
         Print("D1 SL VERIFY symbol=",symbol," ticket=",verifiedTicket,
            " modify=",modified," retcode=",modifyRc,
            " requestedSL=",DoubleToString(sl,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " actualSL=",DoubleToString(actualSL,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " status=",(actualSL>0.0&&MathAbs(actualSL-sl)<=ts*0.5?"VERIFIED":"CHECK_REQUIRED"),
            " err=",GetLastError());
      }else if(verifiedTicket==0){
         Print("D1 SL VERIFY PENDING symbol=",symbol," side=",(side>0?"BUY":"SELL"),
               " deal=",powerTrade.ResultDeal()," requestedSL=",
               DoubleToString(sl,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)));
      }
      if(side>0)powerMomentumLastBuyLow[index]=candleLow;
      else powerMomentumLastSellHigh[index]=candleHigh;
      powerMomentumLastEntryBar[index]=barTime;
      Print("D1 A5 LIVE-CANDLE ENTRY OK ",symbol,
            " side=",(side>0?"BUY_RED_LOW":"SELL_GREEN_HIGH"),
            " layer=",total+1,"/",cap,
            " liveRangeTicks=",DoubleToString(range/ts,1),
            " rubberStretchScore=",DoubleToString(side>0?buyScore:sellScore,3),
            " Digits=",(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS),
            " Point=",DoubleToString(point,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)+2),
            " SL=",slPts," POINT (price distance=",DoubleToString(slDist,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " price=",DoubleToString(sl,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),") retcode=",rc);
      powerPublishState(index,"D1_95_LIVE_CANDLE_ENTRY_SENT",
         string(side>0?"BUY on RED candle near Low":"SELL on GREEN candle near High")+
         " | layer="+IntegerToString(total+1)+"/"+IntegerToString(cap)+
         " | live M5 range="+DoubleToString(range/ts,1)+" ticks"+
         " | confirm=correct-M5-colour + 2-tick-extreme-weakening",barTime);
   }else{
      Print("D1 #95 LIVE-CANDLE ENTRY FAILED ",symbol,
            " side=",(side>0?"BUY_RED_LOW":"SELL_GREEN_HIGH"),
            " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription());
      powerPublishState(index,"D1_95_LIVE_CANDLE_ENTRY_FAILED",
         powerTrade.ResultRetcodeDescription(),barTime);
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
   // POSITION_PROFIT is marked to the executable close side (Bid for BUY, Ask for SELL).
   // Add booked swap and signed incurred charges, then reserve estimated exit costs
   // using the actual entry commission and fee for this position.
   double incurredCosts=0.0,estimatedExitCosts=0.0;
   ulong positionId=(ulong)PositionGetInteger(POSITION_IDENTIFIER);
   if(positionId>0&&HistorySelectByPosition(positionId)){
      int deals=HistoryDealsTotal();
      for(int i=0;i<deals;i++){
         ulong deal=HistoryDealGetTicket(i);
         if(deal==0)continue;
         double commission=HistoryDealGetDouble(deal,DEAL_COMMISSION);
         double fee=HistoryDealGetDouble(deal,DEAL_FEE);
         incurredCosts+=commission+fee;
         long entry=HistoryDealGetInteger(deal,DEAL_ENTRY);
         if(entry==DEAL_ENTRY_IN||entry==DEAL_ENTRY_INOUT)
            estimatedExitCosts+=MathAbs(commission)+MathAbs(fee);
      }
   }
   return PositionGetDouble(POSITION_PROFIT)+PositionGetDouble(POSITION_SWAP)
          +incurredCosts-estimatedExitCosts;
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
      string keys[12]={"PW_HMODE_","PW_HBAR_","PW_PEAK_","PW_RISK_ARM_",
                      "PW_RISK_ADVERSE_","PW_RISK_RECOVERY_","PW_RISK_LASTPX_",
                      "PW_RISK_REC_TICKS_","PW_A5_ARM_","PW_A5_ADVERSE_COUNT_",
                      "PW_A5_LAST_BAR_","PW_A5_COOLDOWN_"};
      for(int k=0;k<ArraySize(keys);k++){
         string key=keys[k]+suffix;
         if(GlobalVariableCheck(key))GlobalVariableDel(key);
      }
      return true;
   }
   Print("POWERFULL CLOSE FAILED symbol=",symbol," ticket=",ticket," reason=",reason,
         " retcode=",rc," desc=",powerTrade.ResultRetcodeDescription()," err=",GetLastError());
   return false;
}

bool powerWeekendCloseDue(){
   if(!PowerfullCloseBeforeWeekend)return false;
   datetime serverNow=TimeTradeServer();
   if(serverNow<=0)serverNow=TimeCurrent();
   if(serverNow<=0)return false;
   MqlDateTime st; TimeToStruct(serverNow,st);
   if(st.day_of_week!=5)return false;
   int hh=MathMax(0,MathMin(23,PowerfullFridayCloseHour));
   int mm=MathMax(0,MathMin(59,PowerfullFridayCloseMinute));
   return (st.hour>hh||(st.hour==hh&&st.min>=mm));
}
void powerApplyWeekendClose(string symbol){
   powerCancelPendingForSymbol(symbol);
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      powerClosePosition(ticket,"WEEKEND_FLAT_FRIDAY");
   }
}
bool powerA6StructureBreak(string symbol,ENUM_TIMEFRAMES tf,bool isBuy,int lookback){
   MqlRates rates[]; ArraySetAsSeries(rates,true);
   int need=lookback+1;
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
// Convert USD target into the trading account currency using broker FX symbols.
double powerUsdToAccountRate(){
   string account=AccountInfoString(ACCOUNT_CURRENCY);
   if(account=="USD")return 1.0;
   if(account=="")return 0.0;
   for(int i=0;i<SymbolsTotal(false);i++){
      string fx=SymbolName(i,false);
      if(fx==""||!SymbolSelect(fx,true))continue;
      string base=SymbolInfoString(fx,SYMBOL_CURRENCY_BASE);
      string quote=SymbolInfoString(fx,SYMBOL_CURRENCY_PROFIT);
      MqlTick q;
      if(!SymbolInfoTick(fx,q)||q.bid<=0.0||q.ask<=0.0)continue;
      if(base=="USD"&&quote==account)return q.bid;
      if(base==account&&quote=="USD")return 1.0/q.ask;
   }
   return 0.0; // Do not guess an exchange rate.
}
double powerLossRescueTargetNet(double volume){
   double rate=powerUsdToAccountRate();
   if(rate<=0.0)return DBL_MAX;
   return rate*(volume/0.01); // USD 1 per 0.01 lot, scaled linearly.
}
bool powerManageLossRescue(int index,const MqlTick &tick){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   string symbol=powerSymbols[index];
   ulong chosenTicket=0;
   double chosenDistance=DBL_MAX,chosenNet=0.0,chosenPeak=0.0,chosenTarget=0.0;
   string chosenReason="";
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||
         PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      string suffix=IntegerToString((long)ticket);
      string modeKey="PW_HMODE_"+suffix;
      if(!GlobalVariableCheck(modeKey)||(int)GlobalVariableGet(modeKey)!=POWERFULL_MODE_LOSS_RESCUE)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double net=powerProjectedNet(ticket),volume=PositionGetDouble(POSITION_VOLUME);
      double target=powerLossRescueTargetNet(volume);
      string wasLossKey="PW_LR_WAS_NEG_"+suffix,peakKey="PW_LR_PEAK_NET_"+suffix;
      if(net<=0.0){
         if(!GlobalVariableCheck(wasLossKey)){
            GlobalVariableSet(wasLossKey,1.0);GlobalVariableSet(peakKey,0.0);
         }
         continue;
      }
      if(!GlobalVariableCheck(wasLossKey)||GlobalVariableGet(wasLossKey)<=0.5)continue;
      double peak=(GlobalVariableCheck(peakKey)?GlobalVariableGet(peakKey):0.0);
      if(net>peak){peak=net;GlobalVariableSet(peakKey,peak);}
      // Meaningful recovery: retain at least 55% of peak net profit (45% retracement).
      if(target>=DBL_MAX||peak<target||net>peak*0.55)continue;
      double entry=PositionGetDouble(POSITION_PRICE_OPEN);
      double executable=(isBuy?tick.bid:tick.ask),distance=MathAbs(entry-executable);
      if(distance<chosenDistance){
         chosenDistance=distance;chosenTicket=ticket;chosenNet=net;chosenPeak=peak;chosenTarget=target;
         chosenReason="LOSS_RESCUE_PROFIT_LOCK_45_PERCENT";
      }
   }
   if(chosenTicket==0)return false;
   Print("POWERFULL LOSS_RESCUE EXIT READY symbol=",symbol," ticket=",chosenTicket,
         " net=",DoubleToString(chosenNet,2)," peak_net=",DoubleToString(chosenPeak,2),
         " min_profit=",DoubleToString(chosenTarget,2)," giveback=45% retained=55%",
         " reason=",chosenReason);
   powerClosePosition(chosenTicket,chosenReason);
   return true;
}

// Confirmed market structure: two closed candles on each side of a swing pivot.
// Returns +1 for HH+HL, -1 for LH+LL, 0 when structure is mixed/not confirmed.
int powerConfirmedSwingStructure(string symbol,ENUM_TIMEFRAMES tf){
   MqlRates rates[]; ArraySetAsSeries(rates,true);
   int copied=CopyRates(symbol,tf,1,180,rates);
   if(copied<20)return 0;
   double recentHigh=0.0,olderHigh=0.0,recentLow=0.0,olderLow=0.0;
   int highCount=0,lowCount=0;
   // Series index 2 is the newest pivot candidate with two newer and two older
   // closed candles available. Scan toward older bars; first pivot is newest.
   for(int i=2;i<copied-2 && (highCount<2||lowCount<2);i++){
      bool swingHigh=true,swingLow=true;
      for(int k=1;k<=2;k++){
         if(rates[i].high<=rates[i-k].high||rates[i].high<=rates[i+k].high)swingHigh=false;
         if(rates[i].low>=rates[i-k].low||rates[i].low>=rates[i+k].low)swingLow=false;
      }
      if(swingHigh){
         if(highCount==0)recentHigh=rates[i].high;
         else if(highCount==1)olderHigh=rates[i].high;
         highCount++;
      }
      if(swingLow){
         if(lowCount==0)recentLow=rates[i].low;
         else if(lowCount==1)olderLow=rates[i].low;
         lowCount++;
      }
   }
   if(highCount<2||lowCount<2)return 0;
   bool higherHigh=(recentHigh>olderHigh),higherLow=(recentLow>olderLow);
   bool lowerHigh=(recentHigh<olderHigh),lowerLow=(recentLow<olderLow);
   if(higherHigh&&higherLow)return 1;
   if(lowerHigh&&lowerLow)return -1;
   return 0;
}
// Dynamic support/resistance from the newest confirmed swing pivot on a
// closed H1/H4 candle. Levels are recalculated from recent price structure;
// they are zones/confirmation references, never standalone close commands.
bool powerLatestSwingLevels(string symbol,ENUM_TIMEFRAMES tf,double &support,double &resistance,double &lastClose){
   support=0.0;resistance=0.0;lastClose=0.0;
   MqlRates rates[];ArraySetAsSeries(rates,true);
   int copied=CopyRates(symbol,tf,1,100,rates);
   if(copied<12)return false;
   lastClose=rates[0].close;
   bool haveSupport=false,haveResistance=false;
   for(int i=2;i<copied-2&&(!haveSupport||!haveResistance);i++){
      bool swingHigh=true,swingLow=true;
      for(int k=1;k<=2;k++){
         if(rates[i].high<=rates[i-k].high||rates[i].high<=rates[i+k].high)swingHigh=false;
         if(rates[i].low>=rates[i-k].low||rates[i].low>=rates[i+k].low)swingLow=false;
      }
      if(swingHigh&&!haveResistance){resistance=rates[i].high;haveResistance=true;}
      if(swingLow&&!haveSupport){support=rates[i].low;haveSupport=true;}
   }
   return (haveSupport&&haveResistance);
}
int powerTrendHoldStructure(string symbol,bool isBuy){
   int h4=powerConfirmedSwingStructure(symbol,PERIOD_H4);
   int h1=powerConfirmedSwingStructure(symbol,PERIOD_H1);
   int side=(isBuy?1:-1);
   double s1=0.0,r1=0.0,c1=0.0,s4=0.0,r4=0.0,c4=0.0;
   bool levels1=powerLatestSwingLevels(symbol,PERIOD_H1,s1,r1,c1);
   bool levels4=powerLatestSwingLevels(symbol,PERIOD_H4,s4,r4,c4);
   double zone=2.0*SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(zone<=0.0||!levels1||!levels4)return 0;
   // A BUY reversal needs both H1 and H4 to confirm bearish structure AND
   // closed prices to break their dynamic support zones. SELL is symmetric.
   bool supportBroken=(c1<s1-zone&&c4<s4-zone);
   bool resistanceBroken=(c1>r1+zone&&c4>r4+zone);
   if(h1==-side&&h4==-side){
      if((isBuy&&supportBroken)||(!isBuy&&resistanceBroken))return -side;
      return 0; // Structure warns, but S/R has not confirmed the break: keep holding.
   }
   if(h1==side&&h4==side)return side;
   return 0;
}

bool powerManageTrendHold(int index,const MqlTick &tick){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   string symbol=powerSymbols[index];
   double ts=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(ts<=0.0)return false;
   // TREND_HOLD only records the favorable extreme. No 30% giveback or
   // short-window tick-flow exit is permitted; structural reversal is handled
   // by powerManagePositions after H1 and H4 both confirm against the ticket.
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||
         PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      string suffix=IntegerToString((long)ticket),modeKey="PW_HMODE_"+suffix;
      if(!GlobalVariableCheck(modeKey)||(int)GlobalVariableGet(modeKey)!=POWERFULL_MODE_TREND_HOLD)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN),executable=(isBuy?tick.bid:tick.ask);
      string peakKey="PW_PEAK_"+suffix;
      if(!GlobalVariableCheck(peakKey))GlobalVariableSet(peakKey,entry);
      double peak=GlobalVariableGet(peakKey);
      if(isBuy?executable>peak+ts*0.1:executable<peak-ts*0.1)
         GlobalVariableSet(peakKey,executable);
      powerTrendHoldStructure(symbol,isBuy); // M15 warning; H1+H4 confirmation only.
   }
   return false;
}

void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool candleJustChanged,bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return;
   string symbol=powerSymbols[index];
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT),ts=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(point<=0.0||ts<=0.0)return;
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN),executable=(isBuy?tick.bid:tick.ask);
      int slPts=(isBuy?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
      double hard=(powerCfg[index].usePointStops?slPts*point:powerCfg[index].stopPips*powerCfg[index].pipSize);
      double adverse=(isBuy?entry-executable:executable-entry);
      if(hard>0.0&&adverse+point*0.1>=hard){powerClosePosition(ticket,"HARD_LOSS_GUARD");continue;}
      string suffix=IntegerToString((long)ticket),modeKey="PW_HMODE_"+suffix;
      string lossSinceKey="PW_RISK_ARM_"+suffix;
      int mode=(GlobalVariableCheck(modeKey)?(int)GlobalVariableGet(modeKey):0);
      double net=powerProjectedNet(ticket);
      int side=(isBuy?1:-1),structure=powerTrendHoldStructure(symbol,isBuy);
      // If H1+H4 turn back in this position's direction, restore TREND_HOLD.
      if(mode==POWERFULL_MODE_LOSS_RESCUE&&structure==side){
         GlobalVariableSet(modeKey,(double)POWERFULL_MODE_TREND_HOLD);
         if(GlobalVariableCheck(lossSinceKey))GlobalVariableDel(lossSinceKey);
         Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=TREND_HOLD reason=H1_H4_structure_returned_to_position_direction",
               " projected_net=",DoubleToString(net,2)," action=continue_trend_hold");
         continue;
      }
      if(mode==POWERFULL_MODE_TREND_HOLD){
         if(structure==-side){
            GlobalVariableSet(modeKey,(double)POWERFULL_MODE_LOSS_RESCUE);
            Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
                  " mode=LOSS_RESCUE reason=confirmed_H1_H4_structure_reversal",
                  " projected_net=",DoubleToString(net,2),
                  " action=monitor_recovery_peak_and_lock_45_percent_giveback");
         }
         continue;
      }
      if(mode!=POWERFULL_MODE_LOSS_RESCUE&&structure==side){
         GlobalVariableSet(modeKey,(double)POWERFULL_MODE_TREND_HOLD);
         if(GlobalVariableCheck(lossSinceKey))GlobalVariableDel(lossSinceKey);
         Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=TREND_HOLD reason=H1_H4_structure_supports_position");
         continue;
      }
      if(mode!=POWERFULL_MODE_LOSS_RESCUE&&structure==-side){
         GlobalVariableSet(modeKey,(double)POWERFULL_MODE_LOSS_RESCUE);
         Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=LOSS_RESCUE reason=confirmed_H1_H4_structure_opposes_position",
               " projected_net=",DoubleToString(net,2));
      }
   }
   if(powerManageLossRescue(index,tick))return;
   powerManageTrendHold(index,tick);
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

   // POWERFULL direct-market entry is evaluated on every fresh MT5 tick.
   // The legacy forecast/pending engine is not used while POWERFULL is enabled.
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

   // POWERFULL is market-entry only. Never activate the legacy ATR/Pending LIMIT planner.
   if(true){
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
