//+------------------------------------------------------------------+
//| Rayyan Smart Auto-Powerfull - MT5 standalone demo EA              |
//| Independent EA test. Does not change the mobile APK.              |
//+------------------------------------------------------------------+
#property strict
#property version   "1.10"
#property description "Rayyan M5 projected-range pending EA; START/STOP and SL only"

#include <Trade/Trade.mqh>
CTrade trade;

input bool   DemoAccountOnly = true;
input ulong  MagicNumber = 26102601;
input double InitialLot = 0.01;
input int    InitialSLPoints = 300;
input int    TakeProfitPoints = 450;
input int    ProjectionLookback = 6;
input double BuyLevelPercent = 0.1;
input double SellLevelPercent = 98.9;
input int    MinDistancePoints = 30;
input int    DeviationPoints = 20;
input int    MaxEntriesPerM5Candle = 2;

string P = "RYSAP_";
bool running = false;
double lotSize = 0.01;
int slPoints = 300;
datetime currentBar = 0;
datetime lastPendingBar = 0;

int OnInit()
{
   lotSize = NormalizeLot(InitialLot);
   slPoints = MathMax(1, InitialSLPoints);
   trade.SetExpertMagicNumber(MagicNumber);
   trade.SetDeviationInPoints(DeviationPoints);
   trade.SetTypeFillingBySymbol(_Symbol);

   ChartSetInteger(0, CHART_COLOR_CANDLE_BULL, clrLimeGreen);
   ChartSetInteger(0, CHART_COLOR_CANDLE_BEAR, clrRed);
   ChartSetInteger(0, CHART_COLOR_CHART_UP, clrLimeGreen);
   ChartSetInteger(0, CHART_COLOR_CHART_DOWN, clrRed);

   MakeButton("START", "START", 10, 20, 76, 26, clrDarkGreen);
   MakeButton("STOP",  "STOP",  92, 20, 76, 26, clrFireBrick);
   MakeButton("SL",    "SL",    174,20, 76, 26, clrDarkSlateGray);

   ObjectCreate(0, P+"INFO", OBJ_LABEL, 0, 0, 0);
   ObjectSetInteger(0, P+"INFO", OBJPROP_CORNER, CORNER_LEFT_UPPER);
   ObjectSetInteger(0, P+"INFO", OBJPROP_XDISTANCE, 10);
   ObjectSetInteger(0, P+"INFO", OBJPROP_YDISTANCE, 54);
   ObjectSetInteger(0, P+"INFO", OBJPROP_COLOR, clrWhite);
   ObjectSetInteger(0, P+"INFO", OBJPROP_FONTSIZE, 9);

   currentBar = iTime(_Symbol, PERIOD_M5, 0);
   EventSetTimer(1);
   Panel();
   Print("Rayyan SAP v1.10 initialized. Only chart buttons: START, STOP, SL. Demo-only=", DemoAccountOnly);
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   EventKillTimer();
   for(int i=ObjectsTotal(0)-1; i>=0; i--)
   {
      string n=ObjectName(0,i);
      if(StringFind(n,P)==0) ObjectDelete(0,n);
   }
}

void OnTick() { RunStrategy(); }
void OnTimer() { Panel(); if(running) RunStrategy(); }

void OnChartEvent(const int id, const long &lp, const double &dp, const string &sp)
{
   if(id != CHARTEVENT_OBJECT_CLICK) return;
   if(sp == P+"START")
   {
      running = true;
      // Force calculation at next tick; do not duplicate existing pending orders.
      Print("START pressed.");
   }
   else if(sp == P+"STOP")
   {
      running = false;
      // STOP cancels this EA's pending orders; open positions retain their SL/TP.
      CancelPendings();
      Print("STOP pressed; this EA's pending orders cancelled. Open positions are not force-closed.");
   }
   else if(sp == P+"SL")
   {
      // One SL button only: cycle loss distance in 50-point steps, 50..1000.
      slPoints += 50;
      if(slPoints > 1000) slPoints = 50;
      // Update SL on this EA's pending orders; never alter TP.
      UpdatePendingStopsOnly();
      Print("SL distance changed to ", slPoints, " points.");
   }
   Panel();
}

void RunStrategy()
{
   if(!running) return;
   if(DemoAccountOnly && (ENUM_ACCOUNT_TRADE_MODE)AccountInfoInteger(ACCOUNT_TRADE_MODE) != ACCOUNT_TRADE_MODE_DEMO)
      return;
   if(!TerminalInfoInteger(TERMINAL_CONNECTED) || !TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) ||
      !MQLInfoInteger(MQL_TRADE_ALLOWED) || !AccountInfoInteger(ACCOUNT_TRADE_ALLOWED))
      return;

   MqlTick tick;
   if(!SymbolInfoTick(_Symbol, tick) || tick.ask <= 0 || tick.bid <= 0) return;

   datetime bar = iTime(_Symbol, PERIOD_M5, 0);
   if(bar <= 0) return;

   // If a position is open, remove opposite pending orders to avoid double exposure.
   if(OurPosition())
   {
      CancelPendings();
      ManageOpenPosition(tick);
      currentBar = bar;
      return;
   }

   if(EntriesThisBar(bar) >= MaxEntriesPerM5Candle)
   {
      CancelPendings();
      currentBar = bar;
      return;
   }

   // Build the next candle's projected range from the current open and the
   // average true range of completed M5 candles only (no moving intrabar target).
   // Recalculate and place orders once per new M5 candle, then keep them fixed
   // until fill, cancellation or expiration at the end of that candle.
   if(bar != currentBar)
   {
      currentBar = bar;
      lastPendingBar = 0;
   }
   if(lastPendingBar == bar) return;

   double avgRange = AverageCompletedRange(ProjectionLookback);
   if(avgRange <= 0) return;

   double open = iOpen(_Symbol, PERIOD_M5, 0);
   if(open <= 0) return;

   double projectedLow  = open - avgRange * 0.5;
   double projectedHigh = open + avgRange * 0.5;
   double projectedRange = projectedHigh - projectedLow;
   if(projectedRange <= 0) return;

   double buy = NormalizePrice(projectedLow + projectedRange * BuyLevelPercent / 100.0);
   double sell = NormalizePrice(projectedLow + projectedRange * SellLevelPercent / 100.0);

   double minGap = MathMax(MinDistancePoints,
                    (int)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL) + 1) * _Point;

   // Buy Limit must be below Ask; Sell Limit must be above Bid.
   if(buy >= tick.ask - minGap) buy = NormalizePrice(tick.ask - minGap);
   if(sell <= tick.bid + minGap) sell = NormalizePrice(tick.bid + minGap);

   datetime expiry = bar + PeriodSeconds(PERIOD_M5);
   if(expiry <= TimeCurrent() + 2) return;

   // Clear stale pending orders from an older candle before placing new ones.
   CancelPendings();
   bool buyOK = PlaceLimit(ORDER_TYPE_BUY_LIMIT, buy, expiry);
   bool sellOK = PlaceLimit(ORDER_TYPE_SELL_LIMIT, sell, expiry);
   if(buyOK || sellOK) lastPendingBar = bar;

   Print("M5 projection: open=", DoubleToString(open,_Digits),
         " projectedLow=", DoubleToString(projectedLow,_Digits),
         " projectedHigh=", DoubleToString(projectedHigh,_Digits),
         " BuyLimit=", DoubleToString(buy,_Digits),
         " SellLimit=", DoubleToString(sell,_Digits),
         " rangeAvg=", DoubleToString(avgRange,_Digits));
}

double AverageCompletedRange(int lookback)
{
   lookback = MathMax(2, MathMin(lookback, 50));
   if(Bars(_Symbol, PERIOD_M5) < lookback + 2) return 0;
   double sum = 0;
   int used = 0;
   for(int shift=1; shift<=lookback; shift++)
   {
      double hi = iHigh(_Symbol, PERIOD_M5, shift);
      double lo = iLow(_Symbol, PERIOD_M5, shift);
      double prevClose = iClose(_Symbol, PERIOD_M5, shift+1);
      if(hi<=0 || lo<=0 || prevClose<=0 || hi<lo) continue;
      double tr = MathMax(hi-lo, MathMax(MathAbs(hi-prevClose), MathAbs(lo-prevClose)));
      sum += tr;
      used++;
   }
   return used > 0 ? sum / used : 0;
}

bool PlaceLimit(ENUM_ORDER_TYPE type, double price, datetime expiry)
{
   double sl = 0, tp = 0;
   if(type == ORDER_TYPE_BUY_LIMIT)
   {
      sl = NormalizePrice(price - slPoints * _Point);
      if(TakeProfitPoints > 0) tp = NormalizePrice(price + TakeProfitPoints * _Point);
   }
   else
   {
      sl = NormalizePrice(price + slPoints * _Point);
      if(TakeProfitPoints > 0) tp = NormalizePrice(price - TakeProfitPoints * _Point);
   }

   bool ok = false;
   if(type == ORDER_TYPE_BUY_LIMIT)
      ok = trade.BuyLimit(lotSize, price, _Symbol, sl, tp, ORDER_TIME_SPECIFIED, expiry, "Rayyan SAP M5");
   else
      ok = trade.SellLimit(lotSize, price, _Symbol, sl, tp, ORDER_TIME_SPECIFIED, expiry, "Rayyan SAP M5");

   if(!ok)
      Print("Pending failed ", EnumToString(type), " price=", DoubleToString(price,_Digits),
            " retcode=", trade.ResultRetcode(), " ", trade.ResultRetcodeDescription());
   else
      Print("Pending placed ", EnumToString(type), " price=", DoubleToString(price,_Digits),
            " SL=", DoubleToString(sl,_Digits), " TP=", DoubleToString(tp,_Digits),
            " expires=", TimeToString(expiry));
   return ok;
}

void ManageOpenPosition(const MqlTick &tick)
{
   // Tick-by-tick protection: close if the latest completed M5 candle's direction
   // conflicts with the open position and the position is not in loss.
   // The broker-side SL/TP remain the primary hard protection.
   for(int i=PositionsTotal()-1; i>=0; i--)
   {
      ulong ticket = PositionGetTicket(i);
      if(ticket==0) continue;
      if(PositionGetString(POSITION_SYMBOL)!=_Symbol ||
         (ulong)PositionGetInteger(POSITION_MAGIC)!=MagicNumber) continue;

      ENUM_POSITION_TYPE type=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      double profit=PositionGetDouble(POSITION_PROFIT)+PositionGetDouble(POSITION_SWAP);
      double prevOpen=iOpen(_Symbol, PERIOD_M5, 1);
      double prevClose=iClose(_Symbol, PERIOD_M5, 1);
      bool reversed = (type==POSITION_TYPE_BUY && prevClose<prevOpen) ||
                      (type==POSITION_TYPE_SELL && prevClose>prevOpen);
      if(reversed && profit>0.0)
      {
         if(!trade.PositionClose(ticket))
            Print("Momentum close failed: ", trade.ResultRetcodeDescription());
         else
            Print("Position closed on opposite completed M5 candle; profit=", DoubleToString(profit,2));
      }
      return;
   }
}

void UpdatePendingStopsOnly()
{
   for(int i=OrdersTotal()-1; i>=0; i--)
   {
      ulong ticket=OrderGetTicket(i);
      if(ticket==0) continue;
      if(OrderGetString(ORDER_SYMBOL)!=_Symbol ||
         (ulong)OrderGetInteger(ORDER_MAGIC)!=MagicNumber) continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(type!=ORDER_TYPE_BUY_LIMIT && type!=ORDER_TYPE_SELL_LIMIT) continue;

      double price=OrderGetDouble(ORDER_PRICE_OPEN);
      double tp=OrderGetDouble(ORDER_TP);
      datetime expiry=(datetime)OrderGetInteger(ORDER_TIME_EXPIRATION);
      double sl=(type==ORDER_TYPE_BUY_LIMIT)
         ? NormalizePrice(price-slPoints*_Point)
         : NormalizePrice(price+slPoints*_Point);
      if(!trade.OrderModify(ticket, price, sl, tp, ORDER_TIME_SPECIFIED, expiry, 0))
         Print("SL update failed: ", trade.ResultRetcodeDescription());
   }
}

bool OurPosition()
{
   for(int i=PositionsTotal()-1; i>=0; i--)
   {
      ulong ticket=PositionGetTicket(i);
      if(ticket==0) continue;
      if(PositionGetString(POSITION_SYMBOL)==_Symbol &&
         (ulong)PositionGetInteger(POSITION_MAGIC)==MagicNumber) return true;
   }
   return false;
}

void CancelPendings()
{
   for(int i=OrdersTotal()-1; i>=0; i--)
   {
      ulong ticket=OrderGetTicket(i);
      if(ticket==0) continue;
      if(OrderGetString(ORDER_SYMBOL)!=_Symbol ||
         (ulong)OrderGetInteger(ORDER_MAGIC)!=MagicNumber) continue;
      ENUM_ORDER_TYPE type=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(type==ORDER_TYPE_BUY_LIMIT || type==ORDER_TYPE_SELL_LIMIT)
         if(!trade.OrderDelete(ticket))
            Print("Pending cancel failed: ", trade.ResultRetcodeDescription());
   }
}

int EntriesThisBar(datetime bar)
{
   if(!HistorySelect(bar, TimeCurrent())) return 0;
   int count=0;
   for(int i=0; i<HistoryDealsTotal(); i++)
   {
      ulong d=HistoryDealGetTicket(i);
      if(d==0) continue;
      if(HistoryDealGetString(d, DEAL_SYMBOL)!=_Symbol ||
         (ulong)HistoryDealGetInteger(d, DEAL_MAGIC)!=MagicNumber) continue;
      ENUM_DEAL_ENTRY e=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(d, DEAL_ENTRY);
      if(e==DEAL_ENTRY_IN || e==DEAL_ENTRY_INOUT) count++;
   }
   return count;
}

bool IsDemo()
{
   return (ENUM_ACCOUNT_TRADE_MODE)AccountInfoInteger(ACCOUNT_TRADE_MODE)==ACCOUNT_TRADE_MODE_DEMO;
}

double NormalizeLot(double v)
{
   double minv=SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MIN);
   double maxv=SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MAX);
   double step=SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP);
   if(step<=0) step=0.01;
   v=MathMax(minv, MathMin(maxv, v));
   v=MathFloor(v/step+1e-8)*step;
   return NormalizeDouble(v, 4);
}

double NormalizePrice(double p)
{
   double ts=SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE);
   if(ts<=0) ts=_Point;
   return NormalizeDouble(MathRound(p/ts)*ts, _Digits);
}

void MakeButton(string id, string caption, int x, int y, int w, int h, color bg)
{
   string n=P+id;
   ObjectCreate(0, n, OBJ_BUTTON, 0, 0, 0);
   ObjectSetInteger(0,n,OBJPROP_CORNER,CORNER_LEFT_UPPER);
   ObjectSetInteger(0,n,OBJPROP_XDISTANCE,x);
   ObjectSetInteger(0,n,OBJPROP_YDISTANCE,y);
   ObjectSetInteger(0,n,OBJPROP_XSIZE,w);
   ObjectSetInteger(0,n,OBJPROP_YSIZE,h);
   ObjectSetInteger(0,n,OBJPROP_BGCOLOR,bg);
   ObjectSetInteger(0,n,OBJPROP_COLOR,clrWhite);
   ObjectSetInteger(0,n,OBJPROP_BORDER_COLOR,clrSilver);
   ObjectSetInteger(0,n,OBJPROP_FONTSIZE,9);
   ObjectSetString(0,n,OBJPROP_TEXT,caption);
   ObjectSetInteger(0,n,OBJPROP_SELECTABLE,false);
   ObjectSetInteger(0,n,OBJPROP_HIDDEN,true);
}

void Panel()
{
   if(ObjectFind(0,P+"INFO")<0) return;
   string status=running ? "RUNNING" : "STOPPED";
   if(DemoAccountOnly && !IsDemo()) status += " / DEMO REQUIRED";
   ObjectSetString(0,P+"SL",OBJPROP_TEXT,"SL "+IntegerToString(slPoints));
   string txt="RAYYAN SMART AUTO-POWERFULL M5\nStatus: "+status+
      "\nSymbol: "+_Symbol+"  Lot: "+DoubleToString(lotSize,2)+
      "\nSL loss distance: "+IntegerToString(slPoints)+" points"+
      "\nTP: "+IntegerToString(TakeProfitPoints)+" points"+
      "\nPending: projected M5 range (0.1% / 98.9%)";
   ObjectSetString(0,P+"INFO",OBJPROP_TEXT,txt);
}
