bool powerManageLossRescue(int index,const MqlTick &tick,bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   string symbol=powerSymbols[index];
   double tickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(tickSize<=0.0)return false;
   bool closedAny=false;
   int closedCount=0;
   uint nowMs=GetTickCount();
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||
         PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      string suffix=IntegerToString((long)ticket);
      string modeKey="PW_HMODE_"+suffix;
      if(!GlobalVariableCheck(modeKey)||(int)GlobalVariableGet(modeKey)!=POWERFULL_MODE_LOSS_RESCUE)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double executable=(isBuy?tick.bid:tick.ask);
      string liveNetKey="PW_NET_LIVE_"+suffix;
      double net=(GlobalVariableCheck(liveNetKey)?GlobalVariableGet(liveNetKey):powerProjectedNet(ticket));
      string peakNetKey="PW_PEAK_NET_"+suffix;
      double peakNet=(GlobalVariableCheck(peakNetKey)?GlobalVariableGet(peakNetKey):MathMax(0.0,net));
      bool newPeak=false;
      if(net>peakNet){peakNet=net;GlobalVariableSet(peakNetKey,peakNet);newPeak=true;}
      string peakPriceKey="PW_PEAK_"+suffix;
      double peakPrice=(GlobalVariableCheck(peakPriceKey)?GlobalVariableGet(peakPriceKey):PositionGetDouble(POSITION_PRICE_OPEN));
      if(isBuy?executable>peakPrice:executable<peakPrice){
         peakPrice=executable;GlobalVariableSet(peakPriceKey,peakPrice);
      }
      double pullbackTicks=(isBuy?(peakPrice-executable):(executable-peakPrice))/tickSize;
      if(pullbackTicks<0.0)pullbackTicks=0.0;
      double previousExecutable=(isBuy?previousBid:previousAsk);
      double adverseMoveTicks=0.0;
      int adverseTicks=0;
      string adverseKey="PW_LR_ADVERSE_TICKS_"+suffix;
      if(GlobalVariableCheck(adverseKey))adverseTicks=(int)GlobalVariableGet(adverseKey);
      if(hasPreviousTick&&previousExecutable>0.0){
         double signedMove=(isBuy?previousExecutable-executable:executable-previousExecutable);
         // Only movement against the open position counts as a reversal.
         // A two-tick recovery in the favorable direction must not trigger exit.
         if(signedMove>=tickSize*0.5){
            adverseMoveTicks=signedMove/tickSize;
            adverseTicks++;
         }else if(signedMove<=-tickSize*0.5){
            adverseTicks=0;
            adverseMoveTicks=0.0;
         }
      }
      if(pullbackTicks<0.5)adverseTicks=0;
      GlobalVariableSet(adverseKey,(double)adverseTicks);
      // Live quote reversal; no fixed target/giveback and no delayed H1/M15 close confirmation.
      bool reversalConfirmed=(net>0.0&&pullbackTicks>=2.0&&
                             (adverseTicks>=2||adverseMoveTicks>=2.0));
      string logKey="PW_LR_LOG_MS_"+suffix;
      bool logDue=(!GlobalVariableCheck(logKey)||
                   (uint)(nowMs-(uint)GlobalVariableGet(logKey))>=1000);
      if(reversalConfirmed||logDue){
         string eventName=(reversalConfirmed?"PROFIT_REVERSAL_EXIT_SIGNAL":
                           (net<=0.0?"LOSS_ZONE_MONITOR":
                            (newPeak?"NEW_NET_PROFIT_PEAK":"PROFIT_RECOVERY_MONITOR")));
         double giveback=MathMax(0.0,peakNet-net);
         Print("POWERFULL LOSS_RESCUE MONITOR symbol=",symbol," ticket=",ticket,
            " side=",(isBuy?"BUY":"SELL"),
            " projected_net=",DoubleToString(net,2),
            " peak_net_tracked=",DoubleToString(peakNet,2),
            " giveback_from_peak=",DoubleToString(giveback,2),
            " executable_price=",DoubleToString(executable,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " peak_price=",DoubleToString(peakPrice,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " pullback_ticks=",DoubleToString(pullbackTicks,1),
            " adverse_ticks=",adverseTicks,
            " adverse_move_ticks=",DoubleToString(adverseMoveTicks,1),
            " decision=",eventName);
         powerWriteLossRescueLog(ticket,symbol,isBuy,net,peakNet,peakPrice,executable,
                                  pullbackTicks,adverseTicks,eventName);
         GlobalVariableSet(logKey,(double)nowMs);
      }
      if(!reversalConfirmed)continue;
      Print("POWERFULL LOSS_RESCUE EXIT REQUEST symbol=",symbol," ticket=",ticket,
            " projected_net=",DoubleToString(net,2),
            " peak_net_tracked=",DoubleToString(peakNet,2),
            " giveback=",DoubleToString(MathMax(0.0,peakNet-net),2),
            " pullback_ticks=",DoubleToString(pullbackTicks,1),
            " adverse_ticks=",adverseTicks,
            " reason=LIVE_PEAK_REVERSAL_NET_POSITIVE");
      if(powerClosePosition(ticket,"LOSS_RESCUE_LIVE_PEAK_REVERSAL_NET_POSITIVE")){
         closedAny=true;closedCount++;
      }else{
         powerWriteLossRescueLog(ticket,symbol,isBuy,net,peakNet,peakPrice,executable,
                                 pullbackTicks,adverseTicks,"CLOSE_REQUEST_FAILED");
      }
   }
   if(closedAny)Print("POWERFULL LOSS_RESCUE CLOSED COUNT symbol=",symbol," count=",closedCount);
   return closedAny;
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
int powerCachedConsensusStructure(int index,string symbol){
   if(index<0||index>=ArraySize(powerSymbols))return 0;
   datetime h1Bar=iTime(symbol,PERIOD_H1,0),h4Bar=iTime(symbol,PERIOD_H4,0);
   if(h1Bar<=0||h4Bar<=0)return 0;
   if(powerCachedH1Bar[index]!=h1Bar&&
      (bool)SeriesInfoInteger(symbol,PERIOD_H1,SERIES_SYNCHRONIZED)&&Bars(symbol,PERIOD_H1)>=20){
      powerCachedH1Structure[index]=powerConfirmedSwingStructure(symbol,PERIOD_H1);
      powerCachedH1Bar[index]=h1Bar;
   }
   if(powerCachedH4Bar[index]!=h4Bar&&
      (bool)SeriesInfoInteger(symbol,PERIOD_H4,SERIES_SYNCHRONIZED)&&Bars(symbol,PERIOD_H4)>=20){
      powerCachedH4Structure[index]=powerConfirmedSwingStructure(symbol,PERIOD_H4);
      powerCachedH4Bar[index]=h4Bar;
   }
   if(powerCachedH1Bar[index]!=h1Bar||powerCachedH4Bar[index]!=h4Bar)return 0;
   return (powerCachedH1Structure[index]==powerCachedH4Structure[index]?powerCachedH1Structure[index]:0);
}



void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool candleJustChanged,bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return;
   string symbol=powerSymbols[index];
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT),ts=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(point<=0.0||ts<=0.0)return;
   int consensusStructure=powerCachedConsensusStructure(index,symbol);
   MqlRates liveM5[],liveM15[];
   ArraySetAsSeries(liveM5,true); ArraySetAsSeries(liveM15,true);
   bool haveM5=(CopyRates(symbol,PERIOD_M5,0,1,liveM5)==1);
   bool haveM15=(CopyRates(symbol,PERIOD_M15,0,1,liveM15)==1);
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN),executable=(isBuy?tick.bid:tick.ask);
      string suffix=IntegerToString((long)ticket),peakPriceKey="PW_PEAK_"+suffix;
      if(!GlobalVariableCheck(peakPriceKey))GlobalVariableSet(peakPriceKey,entry);
      double peakPrice=GlobalVariableGet(peakPriceKey);
      if(isBuy?executable>peakPrice+ts*0.1:executable<peakPrice-ts*0.1)
         GlobalVariableSet(peakPriceKey,executable);
      double currentNet=powerProjectedNet(ticket);
      string peakNetKey="PW_PEAK_NET_"+suffix;
      if(!GlobalVariableCheck(peakNetKey))GlobalVariableSet(peakNetKey,MathMax(0.0,currentNet));
      double peakNet=GlobalVariableGet(peakNetKey);
      if(currentNet>peakNet){peakNet=currentNet;GlobalVariableSet(peakNetKey,peakNet);}
      GlobalVariableSet("PW_NET_LIVE_"+suffix,currentNet);
      int slPts=(isBuy?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
      double hard=(powerCfg[index].usePointStops?slPts*point:powerCfg[index].stopPips*powerCfg[index].pipSize);
      double adverse=(isBuy?entry-executable:executable-entry);
      if(hard>0.0&&adverse+point*0.1>=hard){
         Print("POWERFULL HARD LOSS GUARD symbol=",symbol," ticket=",ticket,
            " projected_net=",DoubleToString(currentNet,2)," peak_net_tracked=",DoubleToString(peakNet,2),
            " adverse_price_distance=",DoubleToString(adverse,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " hard_limit=",DoubleToString(hard,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)));
         powerClosePosition(ticket,"HARD_LOSS_GUARD");continue;
      }
      string modeKey="PW_HMODE_"+suffix,adverseKey="PW_LR_ADVERSE_TICKS_"+suffix;
      int mode=(GlobalVariableCheck(modeKey)?(int)GlobalVariableGet(modeKey):0);
      int side=(isBuy?1:-1);
      int structure=consensusStructure; // +1 bullish, -1 bearish; symmetric for BUY and SELL.
      bool m5Against=(haveM5&&(isBuy?tick.bid<liveM5[0].open-ts*2.0:tick.ask>liveM5[0].open+ts*2.0));
      bool m15Against=(haveM15&&(isBuy?tick.bid<liveM15[0].open-ts*2.0:tick.ask>liveM15[0].open+ts*2.0));
      double lossProgress=(hard>0.0?MathMax(0.0,adverse)/hard:0.0);
      // H1/H4 remains the macro context, not a veto: sustained M5/M15 pressure
      // or meaningful SL proximity can arm rescue before a confirmed H1/H4 swing flips.
      bool localRisk=(m5Against&&lossProgress>=0.45)||
                     (m5Against&&m15Against&&(currentNet<=0.0||lossProgress>=0.20));
      bool rescueRequired=(structure==-side||localRisk);
      if(mode==POWERFULL_MODE_LOSS_RESCUE){
         if(structure==side&&!m5Against&&!m15Against){
            GlobalVariableSet(modeKey,(double)POWERFULL_MODE_TREND_HOLD);
            if(GlobalVariableCheck(adverseKey))GlobalVariableDel(adverseKey);
            Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=TREND_HOLD reason=H1_AND_H4_STRUCTURE_SUPPORTS_POSITION_AGAIN",
               " projected_net=",DoubleToString(currentNet,2)," peak_net_tracked=",DoubleToString(peakNet,2),
               " action=hold_trend_no_profit_trail_exit");
         }
         continue;
      }
      if(mode==POWERFULL_MODE_TREND_HOLD){
         if(rescueRequired){
            GlobalVariableSet(modeKey,(double)POWERFULL_MODE_LOSS_RESCUE);
            if(GlobalVariableCheck(adverseKey))GlobalVariableDel(adverseKey);
            Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=LOSS_RESCUE reason=",(structure==-side?"H1_H4_CONFIRMED_REVERSAL":"EARLY_M5_M15_COUNTERTREND_RISK"),
               " h1_h4_structure=",structure," m5_against=",m5Against," m15_against=",m15Against,
               " sl_progress=",DoubleToString(lossProgress*100.0,1),"% projected_net=",DoubleToString(currentNet,2),
               " peak_net_tracked=",DoubleToString(peakNet,2),
               " action=arm_adaptive_loss_rescue_without_forced_close");
         }
         continue;
      }
      int initialMode=(rescueRequired?POWERFULL_MODE_LOSS_RESCUE:POWERFULL_MODE_TREND_HOLD);
      GlobalVariableSet(modeKey,(double)initialMode);
      Print("POWERFULL MODE INIT symbol=",symbol," ticket=",ticket,
         " mode=",(initialMode==POWERFULL_MODE_LOSS_RESCUE?"LOSS_RESCUE":"TREND_HOLD"),
         " h1_h4_structure=",structure," m5_against=",m5Against," m15_against=",m15Against,
         " sl_progress=",DoubleToString(lossProgress*100.0,1),"% projected_net=",DoubleToString(currentNet,2),
         " peak_net_tracked=",DoubleToString(peakNet,2));
   }
   powerManageLossRescue(index,tick,hasPreviousTick,previousBid,previousAsk);
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
   // This EA services several symbols from one chart's OnTick. Do not rerun
   // history/position management for a symbol if its executable quote did
   // not change; every changed Bid/Ask snapshot still passes immediately.
   double quoteTickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(powerLastTickMs[index]>0&&quoteTickSize>0.0&&
      MathAbs(tick.bid-powerLastBid[index])<quoteTickSize*0.1&&
      MathAbs(tick.ask-powerLastAsk[index])<quoteTickSize*0.1)return;
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
bool powerManageLossRescue(int index,const MqlTick &tick,bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return false;
   string symbol=powerSymbols[index];
   double tickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(tickSize<=0.0)return false;
   MqlRates liveM5[]; ArraySetAsSeries(liveM5,true);
   bool haveM5=(CopyRates(symbol,PERIOD_M5,0,1,liveM5)==1);
   bool closedAny=false;
   int closedCount=0;
   uint nowMs=GetTickCount();
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||
         PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      string suffix=IntegerToString((long)ticket);
      string modeKey="PW_HMODE_"+suffix;
      if(!GlobalVariableCheck(modeKey)||(int)GlobalVariableGet(modeKey)!=POWERFULL_MODE_LOSS_RESCUE)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double executable=(isBuy?tick.bid:tick.ask);
      string liveNetKey="PW_NET_LIVE_"+suffix;
      double net=(GlobalVariableCheck(liveNetKey)?GlobalVariableGet(liveNetKey):powerProjectedNet(ticket));
      string peakNetKey="PW_PEAK_NET_"+suffix;
      double peakNet=(GlobalVariableCheck(peakNetKey)?GlobalVariableGet(peakNetKey):MathMax(0.0,net));
      bool newPeak=false;
      if(net>peakNet){peakNet=net;GlobalVariableSet(peakNetKey,peakNet);newPeak=true;}
      string peakPriceKey="PW_PEAK_"+suffix;
      double peakPrice=(GlobalVariableCheck(peakPriceKey)?GlobalVariableGet(peakPriceKey):PositionGetDouble(POSITION_PRICE_OPEN));
      if(isBuy?executable>peakPrice:executable<peakPrice){
         peakPrice=executable;GlobalVariableSet(peakPriceKey,peakPrice);
      }
      double pullbackTicks=(isBuy?(peakPrice-executable):(executable-peakPrice))/tickSize;
      if(pullbackTicks<0.0)pullbackTicks=0.0;
      double entryPrice=PositionGetDouble(POSITION_PRICE_OPEN);
      double favorableExcursionTicks=(isBuy?(peakPrice-entryPrice):(entryPrice-peakPrice))/tickSize;
      if(favorableExcursionTicks<0.0)favorableExcursionTicks=0.0;
      double requiredPullbackTicks=MathMax(2.0,favorableExcursionTicks*0.45);
      bool m5Against=(haveM5&&(isBuy?tick.bid<liveM5[0].open-tickSize*2.0:tick.ask>liveM5[0].open+tickSize*2.0));
      long heldSeconds=(long)(TimeCurrent()-(datetime)PositionGetInteger(POSITION_TIME));
      double previousExecutable=(isBuy?previousBid:previousAsk);
      double adverseMoveTicks=0.0;
      int adverseTicks=0;
      string adverseKey="PW_LR_ADVERSE_TICKS_"+suffix;
      if(GlobalVariableCheck(adverseKey))adverseTicks=(int)GlobalVariableGet(adverseKey);
      if(hasPreviousTick&&previousExecutable>0.0){
         double signedMove=(isBuy?previousExecutable-executable:executable-previousExecutable);
         // Only movement against the open position counts as a reversal.
         // A two-tick recovery in the favorable direction must not trigger exit.
         if(signedMove>=tickSize*0.5){
            adverseMoveTicks=signedMove/tickSize;
            adverseTicks++;
         }else if(signedMove<=-tickSize*0.5){
            adverseTicks=0;
            adverseMoveTicks=0.0;
         }
      }
      if(pullbackTicks<0.5||!m5Against)adverseTicks=0;
      GlobalVariableSet(adverseKey,(double)adverseTicks);
      // Close only after the minimum three M5-candle hold, a live M5 countertrend,
      // positive projected net, and 45% retracement of the favorable price excursion.
      // If the reversal is cancelled by renewed trend pressure, the exit signal is reset.
      bool reversalConfirmed=(net>0.0&&heldSeconds>=POWERFULL_MIN_TREND_HOLD_SECONDS&&
                             m5Against&&pullbackTicks>=requiredPullbackTicks&&
                             (adverseTicks>=2||adverseMoveTicks>=2.0));
      string logKey="PW_LR_LOG_MS_"+suffix;
      bool logDue=(!GlobalVariableCheck(logKey)||
                   (uint)(nowMs-(uint)GlobalVariableGet(logKey))>=1000);
      if(reversalConfirmed||logDue){
         string eventName=(reversalConfirmed?"PROFIT_REVERSAL_EXIT_SIGNAL":
                           (net<=0.0?"LOSS_ZONE_MONITOR":
                            (newPeak?"NEW_NET_PROFIT_PEAK":"PROFIT_RECOVERY_MONITOR")));
         double giveback=MathMax(0.0,peakNet-net);
         Print("POWERFULL LOSS_RESCUE MONITOR symbol=",symbol," ticket=",ticket,
            " side=",(isBuy?"BUY":"SELL"),
            " projected_net=",DoubleToString(net,2),
            " peak_net_tracked=",DoubleToString(peakNet,2),
            " giveback_from_peak=",DoubleToString(giveback,2),
            " executable_price=",DoubleToString(executable,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " peak_price=",DoubleToString(peakPrice,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " pullback_ticks=",DoubleToString(pullbackTicks,1),
            " required_pullback_ticks=",DoubleToString(requiredPullbackTicks,1),
            " held_seconds=",heldSeconds," m5_against=",m5Against,
            " adverse_ticks=",adverseTicks,
            " adverse_move_ticks=",DoubleToString(adverseMoveTicks,1),
            " decision=",eventName);
         powerWriteLossRescueLog(ticket,symbol,isBuy,net,peakNet,peakPrice,executable,
                                  pullbackTicks,adverseTicks,eventName);
         GlobalVariableSet(logKey,(double)nowMs);
      }
      if(!reversalConfirmed)continue;
      Print("POWERFULL LOSS_RESCUE EXIT REQUEST symbol=",symbol," ticket=",ticket,
            " projected_net=",DoubleToString(net,2),
            " peak_net_tracked=",DoubleToString(peakNet,2),
            " giveback=",DoubleToString(MathMax(0.0,peakNet-net),2),
            " pullback_ticks=",DoubleToString(pullbackTicks,1),
            " required_pullback_ticks=",DoubleToString(requiredPullbackTicks,1),
            " held_seconds=",heldSeconds," m5_against=",m5Against,
            " adverse_ticks=",adverseTicks,
            " reason=ADAPTIVE_45_PERCENT_RETRACE_NET_POSITIVE");
      if(powerClosePosition(ticket,"LOSS_RESCUE_ADAPTIVE_45_PERCENT_RETRACE")){
         closedAny=true;closedCount++;
      }else{
         powerWriteLossRescueLog(ticket,symbol,isBuy,net,peakNet,peakPrice,executable,
                                 pullbackTicks,adverseTicks,"CLOSE_REQUEST_FAILED");
      }
   }
   if(closedAny)Print("POWERFULL LOSS_RESCUE CLOSED COUNT symbol=",symbol," count=",closedCount);
   return closedAny;
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
int powerCachedConsensusStructure(int index,string symbol){
   if(index<0||index>=ArraySize(powerSymbols))return 0;
   datetime h1Bar=iTime(symbol,PERIOD_H1,0),h4Bar=iTime(symbol,PERIOD_H4,0);
   if(h1Bar<=0||h4Bar<=0)return 0;
   if(powerCachedH1Bar[index]!=h1Bar&&
      (bool)SeriesInfoInteger(symbol,PERIOD_H1,SERIES_SYNCHRONIZED)&&Bars(symbol,PERIOD_H1)>=20){
      powerCachedH1Structure[index]=powerConfirmedSwingStructure(symbol,PERIOD_H1);
      powerCachedH1Bar[index]=h1Bar;
   }
   if(powerCachedH4Bar[index]!=h4Bar&&
      (bool)SeriesInfoInteger(symbol,PERIOD_H4,SERIES_SYNCHRONIZED)&&Bars(symbol,PERIOD_H4)>=20){
      powerCachedH4Structure[index]=powerConfirmedSwingStructure(symbol,PERIOD_H4);
      powerCachedH4Bar[index]=h4Bar;
   }
   if(powerCachedH1Bar[index]!=h1Bar||powerCachedH4Bar[index]!=h4Bar)return 0;
   return (powerCachedH1Structure[index]==powerCachedH4Structure[index]?powerCachedH1Structure[index]:0);
}



void powerManagePositions(int index,const MqlTick &tick,datetime barTime,double candleOpen,
                          bool candleJustChanged,bool hasPreviousTick,double previousBid,double previousAsk){
   if(index<0||index>=ArraySize(powerSymbols))return;
   string symbol=powerSymbols[index];
   double point=SymbolInfoDouble(symbol,SYMBOL_POINT),ts=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(point<=0.0||ts<=0.0)return;
   int consensusStructure=powerCachedConsensusStructure(index,symbol);
   MqlRates liveM5[],liveM15[];
   ArraySetAsSeries(liveM5,true); ArraySetAsSeries(liveM15,true);
   bool haveM5=(CopyRates(symbol,PERIOD_M5,0,1,liveM5)==1);
   bool haveM15=(CopyRates(symbol,PERIOD_M15,0,1,liveM15)==1);
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0||!PositionSelectByTicket(ticket)||PositionGetString(POSITION_SYMBOL)!=symbol||
         (long)PositionGetInteger(POSITION_MAGIC)!=POWERFULL_MAGIC)continue;
      bool isBuy=((ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE)==POSITION_TYPE_BUY);
      double entry=PositionGetDouble(POSITION_PRICE_OPEN),executable=(isBuy?tick.bid:tick.ask);
      string suffix=IntegerToString((long)ticket),peakPriceKey="PW_PEAK_"+suffix;
      if(!GlobalVariableCheck(peakPriceKey))GlobalVariableSet(peakPriceKey,entry);
      double peakPrice=GlobalVariableGet(peakPriceKey);
      if(isBuy?executable>peakPrice+ts*0.1:executable<peakPrice-ts*0.1)
         GlobalVariableSet(peakPriceKey,executable);
      double currentNet=powerProjectedNet(ticket);
      string peakNetKey="PW_PEAK_NET_"+suffix;
      if(!GlobalVariableCheck(peakNetKey))GlobalVariableSet(peakNetKey,MathMax(0.0,currentNet));
      double peakNet=GlobalVariableGet(peakNetKey);
      if(currentNet>peakNet){peakNet=currentNet;GlobalVariableSet(peakNetKey,peakNet);}
      GlobalVariableSet("PW_NET_LIVE_"+suffix,currentNet);
      int slPts=(isBuy?powerCfg[index].slEntry1Points:powerCfg[index].slEntry2Points);
      double hard=(powerCfg[index].usePointStops?slPts*point:powerCfg[index].stopPips*powerCfg[index].pipSize);
      double adverse=(isBuy?entry-executable:executable-entry);
      if(hard>0.0&&adverse+point*0.1>=hard){
         Print("POWERFULL HARD LOSS GUARD symbol=",symbol," ticket=",ticket,
            " projected_net=",DoubleToString(currentNet,2)," peak_net_tracked=",DoubleToString(peakNet,2),
            " adverse_price_distance=",DoubleToString(adverse,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)),
            " hard_limit=",DoubleToString(hard,(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS)));
         powerClosePosition(ticket,"HARD_LOSS_GUARD");continue;
      }
      string modeKey="PW_HMODE_"+suffix,adverseKey="PW_LR_ADVERSE_TICKS_"+suffix;
      int mode=(GlobalVariableCheck(modeKey)?(int)GlobalVariableGet(modeKey):0);
      int side=(isBuy?1:-1);
      int structure=consensusStructure; // +1 bullish, -1 bearish; symmetric for BUY and SELL.
      bool m5Against=(haveM5&&(isBuy?tick.bid<liveM5[0].open-ts*2.0:tick.ask>liveM5[0].open+ts*2.0));
      bool m15Against=(haveM15&&(isBuy?tick.bid<liveM15[0].open-ts*2.0:tick.ask>liveM15[0].open+ts*2.0));
      double lossProgress=(hard>0.0?MathMax(0.0,adverse)/hard:0.0);
      // H1/H4 remains the macro context, not a veto: sustained M5/M15 pressure
      // or meaningful SL proximity can arm rescue before a confirmed H1/H4 swing flips.
      bool localRisk=(m5Against&&lossProgress>=0.45)||
                     (m5Against&&m15Against&&(currentNet<=0.0||lossProgress>=0.20));
      bool rescueRequired=(structure==-side||localRisk);
      if(mode==POWERFULL_MODE_LOSS_RESCUE){
         if(structure==side&&!m5Against&&!m15Against){
            GlobalVariableSet(modeKey,(double)POWERFULL_MODE_TREND_HOLD);
            if(GlobalVariableCheck(adverseKey))GlobalVariableDel(adverseKey);
            Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=TREND_HOLD reason=H1_AND_H4_STRUCTURE_SUPPORTS_POSITION_AGAIN",
               " projected_net=",DoubleToString(currentNet,2)," peak_net_tracked=",DoubleToString(peakNet,2),
               " action=hold_trend_no_profit_trail_exit");
         }
         continue;
      }
      if(mode==POWERFULL_MODE_TREND_HOLD){
         if(rescueRequired){
            GlobalVariableSet(modeKey,(double)POWERFULL_MODE_LOSS_RESCUE);
            if(GlobalVariableCheck(adverseKey))GlobalVariableDel(adverseKey);
            Print("POWERFULL MODE symbol=",symbol," ticket=",ticket,
               " mode=LOSS_RESCUE reason=",(structure==-side?"H1_H4_CONFIRMED_REVERSAL":"EARLY_M5_M15_COUNTERTREND_RISK"),
               " h1_h4_structure=",structure," m5_against=",m5Against," m15_against=",m15Against,
               " sl_progress=",DoubleToString(lossProgress*100.0,1),"% projected_net=",DoubleToString(currentNet,2),
               " peak_net_tracked=",DoubleToString(peakNet,2),
               " action=arm_adaptive_loss_rescue_without_forced_close");
         }
         continue;
      }
      int initialMode=(rescueRequired?POWERFULL_MODE_LOSS_RESCUE:POWERFULL_MODE_TREND_HOLD);
      GlobalVariableSet(modeKey,(double)initialMode);
      Print("POWERFULL MODE INIT symbol=",symbol," ticket=",ticket,
         " mode=",(initialMode==POWERFULL_MODE_LOSS_RESCUE?"LOSS_RESCUE":"TREND_HOLD"),
         " h1_h4_structure=",structure," m5_against=",m5Against," m15_against=",m15Against,
         " sl_progress=",DoubleToString(lossProgress*100.0,1),"% projected_net=",DoubleToString(currentNet,2),
         " peak_net_tracked=",DoubleToString(peakNet,2));
   }
   powerManageLossRescue(index,tick,hasPreviousTick,previousBid,previousAsk);
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
   // This EA services several symbols from one chart's OnTick. Do not rerun
   // history/position management for a symbol if its executable quote did
   // not change; every changed Bid/Ask snapshot still passes immediately.
   double quoteTickSize=SymbolInfoDouble(symbol,SYMBOL_TRADE_TICK_SIZE);
   if(powerLastTickMs[index]>0&&quoteTickSize>0.0&&
      MathAbs(tick.bid-powerLastBid[index])<quoteTickSize*0.1&&
      MathAbs(tick.ask-powerLastAsk[index])<quoteTickSize*0.1)return;
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
