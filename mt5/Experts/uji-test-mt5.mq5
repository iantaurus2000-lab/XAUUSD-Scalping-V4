//+------------------------------------------------------------------+
//| uji-test-mt5.mq5 - Adaptive M5 pending-order test EA              |
//+------------------------------------------------------------------+
#property strict
#property version "1.20"
#property description "Adaptive M5 wick-distribution EA with reversal profit management"

#include <Trade/Trade.mqh>
CTrade trade;

input bool   DemoAccountOnly = true;
input ulong  MagicNumber = 26102601;
input double InitialLot = 0.01;
input double LotStep = 0.01;
input int    MinDistancePoints = 30;
input int    DistributionLookback = 20;
input double RangeOffsetFraction = 0.20; // retained for compatibility; endpoint entries are used
input double ReversalATRFactor = 0.25;
input int    MinProfitLockPoints = 5;
input int    TickSamplesForAnalysis = 12;
input double MomentumStrongPoints = 4.0; // minimum tick displacement in points
input int    DeviationPoints = 20;
input int    PendingPlacementSecondsBeforeClose = 3; // place new pending only in final seconds of M5 candle
input double MaxPendingDistanceATR = 1.5; // cancel stale pending if market moves too far away
input int    MinHoldCandlesBeforeProfitClose = 2; // wait for several M5 candles before profit exit

string P = "RYSAP_";
bool running = false;
double lotSize = 0.01;
int slEntry1Points = 0; // BUY pending SL; 0 = disabled
int slEntry2Points = 0; // SELL pending SL; 0 = disabled
datetime currentBar = 0;
int atrHandle = INVALID_HANDLE;
double tickPrices[32];
int tickWriteIndex = 0, tickSampleCount = 0;
string lastSignalText = "WAITING FOR TICK DATA";
string lastForecastText = "NEXT CANDLE: WAITING FOR DATA";
string edit1Name, edit2Name;
string lotButtonName;
string timerCircleName, timerTextName;

int OnInit()
{
   lotSize = NormalizeLot(InitialLot);
   trade.SetExpertMagicNumber(MagicNumber);
   trade.SetDeviationInPoints(DeviationPoints);
   trade.SetTypeFillingBySymbol(_Symbol);
   atrHandle = iATR(_Symbol, PERIOD_M5, 14);
   if(atrHandle == INVALID_HANDLE) return INIT_FAILED;

   ApplyCandleColors();
   MakeButton("TOGGLE", "START", 10, 20, 90, 26, clrDarkGreen);
   MakeButton("LM", "LOT -", 10, 53, 58, 22, clrDimGray);
   lotButtonName = P + "LOTVALUE";
   MakeButton("LOTVALUE", "LOT: " + DoubleToString(lotSize, 2), 72, 53, 100, 22, clrDarkSlateBlue);
   MakeButton("LP", "LOT +", 176, 53, 58, 22, clrDimGray);
   MakeButton("CLOSEALL", "CLOSE ALL ORDER", 10, 208, 224, 26, clrFireBrick);

   MakeLabel("SL1LBL", "SL Entry 1 / BUY (points; 0=OFF)", 10, 83);
   edit1Name = P + "SL1EDIT";
   MakeEdit(edit1Name, "0", 10, 101, 125, 23);
   MakeButton("SL1SET", "SET SL1", 140, 101, 75, 23, clrSteelBlue);

   MakeLabel("SL2LBL", "SL Entry 2 / SELL (points; 0=OFF)", 10, 130);
   edit2Name = P + "SL2EDIT";
   MakeEdit(edit2Name, "0", 10, 148, 125, 23);
   MakeButton("SL2SET", "SET SL2", 140, 148, 75, 23, clrSteelBlue);

   MakeLabel("INFO", "", 10, 242);
   timerCircleName = P + "M5CIRCLE";
   timerTextName = P + "M5TIMER";
   MakeLabel("M5CIRCLE", "●", 10, 430);
   ObjectSetInteger(0, timerCircleName, OBJPROP_FONTSIZE, 72);
   ObjectSetInteger(0, timerCircleName, OBJPROP_COLOR, clrSteelBlue);
   MakeLabel("M5TIMER", "05:00", 38, 463);
   ObjectSetInteger(0, timerTextName, OBJPROP_FONTSIZE, 16);
   ObjectSetInteger(0, timerTextName, OBJPROP_COLOR, clrWhite);
   currentBar = iTime(_Symbol, PERIOD_M5, 0);
   EventSetTimer(1);
   Panel();
   Print("uji-test-mt5 initialized. Press START to enable. Demo-only default; M5 analysis.");
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   EventKillTimer();
   if(atrHandle != INVALID_HANDLE) IndicatorRelease(atrHandle);
   for(int i=ObjectsTotal(0)-1; i>=0; i--)
   {
      string n = ObjectName(0, i);
      if(StringFind(n, P) == 0) ObjectDelete(0, n);
   }
}

void OnTick()
{
   MqlTick tick;
   if(SymbolInfoTick(_Symbol, tick) && tick.bid > 0 && tick.ask > 0) RecordTickSample((tick.bid + tick.ask) * 0.5);
   RunStrategy();
}
void OnTimer() { ApplyCandleColors(); Panel(); if(running) RunStrategy(); }

void OnChartEvent(const int id, const long &lp, const double &dp, const string &sp)
{
   if(id != CHARTEVENT_OBJECT_CLICK) return;
   if(sp == P + "TOGGLE")
   {
      running = !running;
      ObjectSetString(0, P + "TOGGLE", OBJPROP_TEXT, running ? "STOP" : "START");
      ObjectSetInteger(0, P + "TOGGLE", OBJPROP_BGCOLOR, running ? clrFireBrick : clrDarkGreen);
      if(!running) CancelPendings();
   }
   else if(sp == P + "LM") lotSize = NormalizeLot(lotSize - LotStep);
   else if(sp == P + "LP") lotSize = NormalizeLot(lotSize + LotStep);
   else if(sp == P + "CLOSEALL") CloseAllOurTrades();
   else if(sp == P + "SL1SET") ReadSLField(1);
   else if(sp == P + "SL2SET") ReadSLField(2);
   Panel();
}

void ReadSLField(int which)
{
   string name = (which == 1 ? edit1Name : edit2Name);
   string value = ObjectGetString(0, name, OBJPROP_TEXT);
   StringTrimLeft(value);
   StringTrimRight(value);
   long parsed = StringToInteger(value);
   if(value == "" || parsed < 0 || parsed > 100000000)
   {
      Print("Invalid SL value for entry ", which, ". Enter non-negative points.");
      return;
   }
   if(which == 1) slEntry1Points = (int)parsed;
   else slEntry2Points = (int)parsed;
   Panel();
   Print("SL Entry ", which, " set to ", parsed, " points; 0 means SL disabled.");
}

void RunStrategy()
{
   if(!TerminalInfoInteger(TERMINAL_CONNECTED) ||
      !TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) ||
      !MQLInfoInteger(MQL_TRADE_ALLOWED) ||
      !AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)) return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0) return;
   ManageOpenPositions(t);
   if(!running) { CancelPendings(); return; }
   if(DemoAccountOnly && !IsDemo()) return;
   datetime bar = iTime(_Symbol, PERIOD_M5, 0);
   if(bar <= 0) return;
   currentBar = bar;
   CancelDistantPendings(t);
   int secondsLeft = (int)(bar + PeriodSeconds(PERIOD_M5) - TimeCurrent());
   secondsLeft = (int)MathMax(0, MathMin(PeriodSeconds(PERIOD_M5), secondsLeft));
   // New pending orders are submitted only in the final seconds of the live M5 candle.
   if(secondsLeft > (int)MathMax(0, PendingPlacementSecondsBeforeClose)) return;

   double hi = iHigh(_Symbol, PERIOD_M5, 0);
   double lo = iLow(_Symbol, PERIOD_M5, 0);
   if(hi <= 0 || lo <= 0 || hi < lo) return;

   double predictedUpperWick = 0.0, predictedLowerWick = 0.0, projectedRange = 0.0;
   ProjectedWicks(predictedUpperWick, predictedLowerWick, projectedRange);
   if(projectedRange <= 0) return;
   int stopLevel = (int)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   double gap = MathMax(MinDistancePoints, stopLevel + 1) * _Point;

   // Predict wick endpoints from separate historical upper/lower-wick distributions.
   double op = iOpen(_Symbol, PERIOD_M5, 0), cl = iClose(_Symbol, PERIOD_M5, 0);
   if(op <= 0 || cl <= 0) return;
   // Current candle OHLC is the live signal; projected wick endpoints refer to the next M5 candle.
   double nextCandleAnchor = cl;
   double predictedUpper = NormalizePrice(nextCandleAnchor + predictedUpperWick);
   double predictedLower = NormalizePrice(nextCandleAnchor - predictedLowerWick);
   lastForecastText = "NEXT M5 WICKS: BUY " + DoubleToString(predictedLower, _Digits) +
                      " | SELL " + DoubleToString(predictedUpper, _Digits);
   bool allowBuy = false, allowSell = false;
   int buyScore = 0, sellScore = 0;
   DetectExhaustion(t, allowBuy, allowSell, buyScore, sellScore);
   lastSignalText = "BUY " + IntegerToString(buyScore) + "/3 | SELL " + IntegerToString(sellScore) + "/3";
   // Keep pending orders after placement; temporary signal changes do not cancel them.
   if(allowBuy) UpdatePendingAtEndpoint(true, predictedLower, t, gap);
   if(allowSell) UpdatePendingAtEndpoint(false, predictedUpper, t, gap);
}

double ProjectedRange()
{
   double upperWick = 0.0, lowerWick = 0.0, range = 0.0;
   ProjectedWicks(upperWick, lowerWick, range);
   return range;
}

void ProjectedWicks(double &upperWick, double &lowerWick, double &projectedRange)
{
   upperWick = 0.0; lowerWick = 0.0; projectedRange = 0.0;
   double ranges[200], upperWicks[200], lowerWicks[200];
   int used = 0;
   int count = (int)MathMax(1, MathMin(DistributionLookback, 200));
   for(int i = 1; i <= count; i++)
   {
      double h = iHigh(_Symbol, PERIOD_M5, i), l = iLow(_Symbol, PERIOD_M5, i);
      double o = iOpen(_Symbol, PERIOD_M5, i), cl = iClose(_Symbol, PERIOD_M5, i);
      if(h <= 0 || l <= 0 || h < l || o <= 0 || cl <= 0) continue;
      ranges[used] = h - l;
      upperWicks[used] = MathMax(0.0, h - MathMax(o, cl));
      lowerWicks[used] = MathMax(0.0, MathMin(o, cl) - l);
      used++;
   }
   if(used <= 0)
   {
      double atrBuf[1];
      if(CopyBuffer(atrHandle, 0, 1, 1, atrBuf) == 1) projectedRange = atrBuf[0];
      return;
   }
   for(int a = 0; a < used - 1; a++)
   {
      for(int b = a + 1; b < used; b++)
      {
         if(ranges[b] < ranges[a]) { double v=ranges[a]; ranges[a]=ranges[b]; ranges[b]=v; }
         if(upperWicks[b] < upperWicks[a]) { double v=upperWicks[a]; upperWicks[a]=upperWicks[b]; upperWicks[b]=v; }
         if(lowerWicks[b] < lowerWicks[a]) { double v=lowerWicks[a]; lowerWicks[a]=lowerWicks[b]; lowerWicks[b]=v; }
      }
   }
   int q = (int)MathMin(used - 1, MathFloor((used - 1) * 0.75));
   double avgRange = 0.0;
   for(int k = 0; k < used; k++) avgRange += ranges[k];
   avgRange /= used;
   projectedRange = 0.65 * ranges[q] + 0.35 * avgRange;
   upperWick = upperWicks[q];
   lowerWick = lowerWicks[q];
   double atrBuf[1];
   if(CopyBuffer(atrHandle, 0, 1, 1, atrBuf) == 1 && atrBuf[0] > 0)
      projectedRange = 0.5 * projectedRange + 0.5 * atrBuf[0];
   if(upperWick + lowerWick > projectedRange)
   {
      double scale = projectedRange / (upperWick + lowerWick);
      upperWick *= scale; lowerWick *= scale;
   }
}

void UpdatePendingAtEndpoint(bool isBuy, double target, MqlTick &t, double gap)
{
   ENUM_ORDER_TYPE type;
   if(isBuy) type = (target < t.ask - gap ? ORDER_TYPE_BUY_LIMIT : ORDER_TYPE_BUY_STOP);
   else type = (target > t.bid + gap ? ORDER_TYPE_SELL_LIMIT : ORDER_TYPE_SELL_STOP);
   target = NormalizePrice(target);
   if(isBuy && type == ORDER_TYPE_BUY_LIMIT && target >= t.ask - gap) target = NormalizePrice(t.ask - gap);
   if(isBuy && type == ORDER_TYPE_BUY_STOP && target <= t.ask + gap) target = NormalizePrice(t.ask + gap);
   if(!isBuy && type == ORDER_TYPE_SELL_LIMIT && target <= t.bid + gap) target = NormalizePrice(t.bid + gap);
   if(!isBuy && type == ORDER_TYPE_SELL_STOP && target >= t.bid - gap) target = NormalizePrice(t.bid - gap);
   UpdateLimit(type, target);
}

void CancelDistantPendings(MqlTick &t)
{
   double atrBuf[1];
   double atr = 0.0;
   if(CopyBuffer(atrHandle, 0, 1, 1, atrBuf) == 1) atr = atrBuf[0];
   if(atr <= 0.0) atr = ProjectedRange();
   if(atr <= 0.0 || MaxPendingDistanceATR <= 0.0) return;
   double maxDistance = atr * MaxPendingDistanceATR;
   for(int i = OrdersTotal() - 1; i >= 0; i--)
   {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0) continue;
      if(OrderGetString(ORDER_SYMBOL) != _Symbol || (ulong)OrderGetInteger(ORDER_MAGIC) != MagicNumber) continue;
      ENUM_ORDER_TYPE ty = (ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      bool buy = (ty == ORDER_TYPE_BUY_LIMIT || ty == ORDER_TYPE_BUY_STOP);
      bool sell = (ty == ORDER_TYPE_SELL_LIMIT || ty == ORDER_TYPE_SELL_STOP);
      if(!buy && !sell) continue;
      double orderPrice = OrderGetDouble(ORDER_PRICE_OPEN);
      double marketPrice = (buy ? t.ask : t.bid);
      if(MathAbs(orderPrice - marketPrice) > maxDistance)
      {
         if(trade.OrderDelete(ticket))
            Print("Distant pending cancelled: ", EnumToString(ty), " distancePts=", DoubleToString(MathAbs(orderPrice-marketPrice)/_Point, 1),
                  " maxPts=", DoubleToString(maxDistance/_Point, 1));
         else
            Print("Distant pending cancellation failed: ", trade.ResultRetcodeDescription());
      }
   }
}

void UpdateLimit(ENUM_ORDER_TYPE type, double price)
{
   // Entry 1 = BUY pending SL; Entry 2 = SELL pending SL. TP is always disabled.
   bool buySide = (type == ORDER_TYPE_BUY_LIMIT || type == ORDER_TYPE_BUY_STOP);
   int slPts = (buySide ? slEntry1Points : slEntry2Points);
   double sl = 0.0;
   if(slPts > 0)
   {
      if(buySide) sl = NormalizePrice(price - slPts * _Point);
      else sl = NormalizePrice(price + slPts * _Point);
   }

   // Freeze the first pending order on each side; do not move/delete it on later ticks.
   ulong ticket = FindPending(type);
   if(ticket != 0) return;

   bool ok = false;
   if(type == ORDER_TYPE_BUY_LIMIT)
      ok = trade.BuyLimit(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_GTC, 0, "uji-test-mt5 BUY");
   else if(type == ORDER_TYPE_BUY_STOP)
      ok = trade.BuyStop(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_GTC, 0, "uji-test-mt5 BUY");
   else if(type == ORDER_TYPE_SELL_LIMIT)
      ok = trade.SellLimit(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_GTC, 0, "uji-test-mt5 SELL");
   else
      ok = trade.SellStop(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_GTC, 0, "uji-test-mt5 SELL");

   if(!ok)
      Print("Pending placement failed: ", trade.ResultRetcode(), " ", trade.ResultRetcodeDescription());
   else
      Print("Pending placed: ", EnumToString(type), " price=", DoubleToString(price, _Digits),
            " lot=", DoubleToString(lotSize, 2), " SLpts=", slPts, " TP=OFF",
            " projectedRangePts=", DoubleToString(ProjectedRange() / _Point, 1));
}

bool OurPosition()
{
   for(int i = PositionsTotal()-1; i >= 0; i--)
   {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      if(PositionGetString(POSITION_SYMBOL) == _Symbol &&
         (ulong)PositionGetInteger(POSITION_MAGIC) == MagicNumber) return true;
   }
   return false;
}

ulong FindPending(ENUM_ORDER_TYPE type)
{
   bool wantBuy = (type == ORDER_TYPE_BUY_LIMIT || type == ORDER_TYPE_BUY_STOP);
   for(int i = OrdersTotal()-1; i >= 0; i--)
   {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0) continue;
      if(OrderGetString(ORDER_SYMBOL) != _Symbol || (ulong)OrderGetInteger(ORDER_MAGIC) != MagicNumber) continue;
      ENUM_ORDER_TYPE existing = (ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      bool existingBuy = (existing == ORDER_TYPE_BUY_LIMIT || existing == ORDER_TYPE_BUY_STOP);
      bool existingPending = (existing == ORDER_TYPE_BUY_LIMIT || existing == ORDER_TYPE_BUY_STOP || existing == ORDER_TYPE_SELL_LIMIT || existing == ORDER_TYPE_SELL_STOP);
      if(existingPending && existingBuy == wantBuy) return ticket;
   }
   return 0;
}

void CancelPendingSide(bool buySide)
{
   for(int i = OrdersTotal()-1; i >= 0; i--)
   {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0) continue;
      if(OrderGetString(ORDER_SYMBOL) != _Symbol || (ulong)OrderGetInteger(ORDER_MAGIC) != MagicNumber) continue;
      ENUM_ORDER_TYPE ty = (ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      bool pending = (ty == ORDER_TYPE_BUY_LIMIT || ty == ORDER_TYPE_BUY_STOP || ty == ORDER_TYPE_SELL_LIMIT || ty == ORDER_TYPE_SELL_STOP);
      bool isBuy = (ty == ORDER_TYPE_BUY_LIMIT || ty == ORDER_TYPE_BUY_STOP);
      if(pending && isBuy == buySide && !trade.OrderDelete(ticket)) Print("Cancel side pending failed: ", trade.ResultRetcodeDescription());
   }
}

void CancelPendings()
{
   for(int i = OrdersTotal()-1; i >= 0; i--)
   {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0) continue;
      if(OrderGetString(ORDER_SYMBOL) != _Symbol ||
         (ulong)OrderGetInteger(ORDER_MAGIC) != MagicNumber) continue;
      ENUM_ORDER_TYPE ty = (ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(ty == ORDER_TYPE_BUY_LIMIT || ty == ORDER_TYPE_SELL_LIMIT || ty == ORDER_TYPE_BUY_STOP || ty == ORDER_TYPE_SELL_STOP)
         if(!trade.OrderDelete(ticket)) Print("OrderDelete failed: ", trade.ResultRetcodeDescription());
   }
}

bool IsDemo()
{
   return (ENUM_ACCOUNT_TRADE_MODE)AccountInfoInteger(ACCOUNT_TRADE_MODE) == ACCOUNT_TRADE_MODE_DEMO;
}

double NormalizeLot(double v)
{
   double minv = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MIN);
   double maxv = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MAX);
   double step = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP);
   if(step <= 0) step = 0.01;
   v = MathMax(minv, MathMin(maxv, v));
   v = MathFloor(v / step + 1e-8) * step;
   return NormalizeDouble(v, 4);
}

double NormalizePrice(double p)
{
   double ts = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE);
   if(ts <= 0) ts = _Point;
   return NormalizeDouble(MathRound(p / ts) * ts, _Digits);
}

void MakeButton(string id, string caption, int x, int y, int w, int h, color bg)
{
   string n = P + id;
   ObjectCreate(0, n, OBJ_BUTTON, 0, 0, 0);
   ObjectSetInteger(0, n, OBJPROP_CORNER, CORNER_LEFT_UPPER);
   ObjectSetInteger(0, n, OBJPROP_XDISTANCE, x);
   ObjectSetInteger(0, n, OBJPROP_YDISTANCE, y);
   ObjectSetInteger(0, n, OBJPROP_XSIZE, w);
   ObjectSetInteger(0, n, OBJPROP_YSIZE, h);
   ObjectSetInteger(0, n, OBJPROP_BGCOLOR, bg);
   ObjectSetInteger(0, n, OBJPROP_COLOR, clrWhite);
   ObjectSetInteger(0, n, OBJPROP_BORDER_COLOR, clrSilver);
   ObjectSetInteger(0, n, OBJPROP_FONTSIZE, 9);
   ObjectSetString(0, n, OBJPROP_TEXT, caption);
   ObjectSetInteger(0, n, OBJPROP_SELECTABLE, false);
   ObjectSetInteger(0, n, OBJPROP_HIDDEN, true);
}

void MakeLabel(string id, string caption, int x, int y)
{
   string n = P + id;
   ObjectCreate(0, n, OBJ_LABEL, 0, 0, 0);
   ObjectSetInteger(0, n, OBJPROP_CORNER, CORNER_LEFT_UPPER);
   ObjectSetInteger(0, n, OBJPROP_XDISTANCE, x);
   ObjectSetInteger(0, n, OBJPROP_YDISTANCE, y);
   ObjectSetInteger(0, n, OBJPROP_COLOR, clrWhite);
   ObjectSetInteger(0, n, OBJPROP_FONTSIZE, 9);
   ObjectSetString(0, n, OBJPROP_TEXT, caption);
   ObjectSetInteger(0, n, OBJPROP_HIDDEN, true);
}

void MakeEdit(string name, string value, int x, int y, int w, int h)
{
   ObjectCreate(0, name, OBJ_EDIT, 0, 0, 0);
   ObjectSetInteger(0, name, OBJPROP_CORNER, CORNER_LEFT_UPPER);
   ObjectSetInteger(0, name, OBJPROP_XDISTANCE, x);
   ObjectSetInteger(0, name, OBJPROP_YDISTANCE, y);
   ObjectSetInteger(0, name, OBJPROP_XSIZE, w);
   ObjectSetInteger(0, name, OBJPROP_YSIZE, h);
   ObjectSetInteger(0, name, OBJPROP_COLOR, clrBlack);
   ObjectSetInteger(0, name, OBJPROP_BGCOLOR, clrWhite);
   ObjectSetInteger(0, name, OBJPROP_BORDER_COLOR, clrSilver);
   ObjectSetInteger(0, name, OBJPROP_FONTSIZE, 9);
   ObjectSetString(0, name, OBJPROP_TEXT, value);
   ObjectSetInteger(0, name, OBJPROP_HIDDEN, true);
}

double ConvertAccountToUSD(double amount)
{
   string accountCurrency = AccountInfoString(ACCOUNT_CURRENCY);
   if(accountCurrency == "USD") return amount;
   int total = SymbolsTotal(false);
   for(int i = 0; i < total; i++)
   {
      string symbol = SymbolName(i, false);
      string base = SymbolInfoString(symbol, SYMBOL_CURRENCY_BASE);
      string profit = SymbolInfoString(symbol, SYMBOL_CURRENCY_PROFIT);
      MqlTick fx;
      if(!SymbolInfoTick(symbol, fx) || fx.bid <= 0 || fx.ask <= 0) continue;
      if(base == accountCurrency && profit == "USD") return amount * fx.bid;
      if(base == "USD" && profit == accountCurrency) return amount / fx.ask;
   }
   return -1.0;
}

double EstimateMoney(double points)
{
   if(points <= 0 || lotSize <= 0) return -1.0;
   double openPrice = SymbolInfoDouble(_Symbol, SYMBOL_ASK);
   if(openPrice <= 0) return -1.0;
   double result = 0.0;
   if(!OrderCalcProfit(ORDER_TYPE_BUY, _Symbol, lotSize, openPrice, openPrice + points * _Point, result)) return -1.0;
   return ConvertAccountToUSD(MathAbs(result));
}

void ApplyCandleColors()
{
   ChartSetInteger(0, CHART_MODE, CHART_CANDLES);
   ChartSetInteger(0, CHART_COLOR_CANDLE_BULL, C'34,197,94');
   ChartSetInteger(0, CHART_COLOR_CANDLE_BEAR, C'239,68,68');
   ChartSetInteger(0, CHART_COLOR_CHART_UP, C'34,197,94');
   ChartSetInteger(0, CHART_COLOR_CHART_DOWN, C'239,68,68');
}

void UpdateM5Timer()
{
   if(ObjectFind(0, timerTextName) < 0) return;
   long chartHeight = ChartGetInteger(0, CHART_HEIGHT_IN_PIXELS, 0);
   int circleY = (int)MathMax(120, chartHeight - 125);
   ObjectSetInteger(0, timerCircleName, OBJPROP_YDISTANCE, circleY);
   ObjectSetInteger(0, timerTextName, OBJPROP_YDISTANCE, circleY + 33);
   datetime bar = iTime(_Symbol, PERIOD_M5, 0);
   int remain = (int)(bar + PeriodSeconds(PERIOD_M5) - TimeCurrent());
   remain = (int)MathMax(0, MathMin(PeriodSeconds(PERIOD_M5), remain));
   string ss = IntegerToString(remain % 60);
   if(StringLen(ss) < 2) ss = "0" + ss;
   ObjectSetString(0, timerTextName, OBJPROP_TEXT, IntegerToString(remain / 60) + ":" + ss);
   ChartRedraw(0);
}

void CloseAllOurTrades()
{
   for(int i = PositionsTotal() - 1; i >= 0; i--)
   {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      if(PositionGetString(POSITION_SYMBOL) != _Symbol || (ulong)PositionGetInteger(POSITION_MAGIC) != MagicNumber) continue;
      if(!trade.PositionClose(ticket)) Print("Close position failed: ", trade.ResultRetcodeDescription());
   }
   CancelPendings();
}

void RecordTickSample(double mid)
{
   tickPrices[tickWriteIndex] = mid;
   tickWriteIndex = (tickWriteIndex + 1) % 32;
   if(tickSampleCount < 32) tickSampleCount++;
}

double TickPriceAgo(int ago)
{
   if(ago < 0 || ago >= tickSampleCount) return 0.0;
   int idx = tickWriteIndex - 1 - ago;
   while(idx < 0) idx += 32;
   return tickPrices[idx];
}

void DetectExhaustion(MqlTick &t, bool &allowBuy, bool &allowSell, int &buyScore, int &sellScore)
{
   allowBuy = false; allowSell = false; buyScore = 0; sellScore = 0;
   int required = (int)MathMax(12, MathMin(24, TickSamplesForAnalysis));
   if(tickSampleCount < required)
   {
      lastSignalText = "ACTUAL M5: COLLECTING TICKS";
      return;
   }
   double recentNet = TickPriceAgo(0) - TickPriceAgo(3);
   double priorNet = TickPriceAgo(4) - TickPriceAgo(7);
   double olderNet = TickPriceAgo(7) - TickPriceAgo(MathMin(required - 1, 11));
   double threshold = MathMax(2.0 * _Point, MomentumStrongPoints * _Point);
   bool momentumUpStrong = (recentNet >= threshold && priorNet > 0.0);
   bool momentumDownStrong = (recentNet <= -threshold && priorNet < 0.0);
   bool tickTurnUp = (olderNet < -threshold && recentNet > 0.0 && priorNet <= 0.0);
   bool tickTurnDown = (olderNet > threshold && recentNet < 0.0 && priorNet >= 0.0);
   bool tickSlowing = (MathAbs(recentNet) < MathAbs(olderNet) * 0.55);
   double hi = iHigh(_Symbol, PERIOD_M5, 0), lo = iLow(_Symbol, PERIOD_M5, 0);
   double op = iOpen(_Symbol, PERIOD_M5, 0), cl = iClose(_Symbol, PERIOD_M5, 0);
   if(hi <= 0 || lo <= 0 || op <= 0 || cl <= 0 || hi < lo) return;
   double range = MathMax(hi - lo, _Point);
   double upperWick = MathMax(0.0, hi - MathMax(op, cl));
   double lowerWick = MathMax(0.0, MathMin(op, cl) - lo);
   bool lowerWickExhaustion = (lowerWick > upperWick && lowerWick / range >= 0.30);
   bool upperWickExhaustion = (upperWick > lowerWick && upperWick / range >= 0.30);
   // A reversal signal requires tick direction to turn plus wick rejection or tick deceleration.
   allowBuy = tickTurnUp && (lowerWickExhaustion || tickSlowing);
   allowSell = tickTurnDown && (upperWickExhaustion || tickSlowing);
   if(allowBuy && allowSell) { allowBuy = false; allowSell = false; }
   buyScore = (tickTurnUp ? 1 : 0) + (tickSlowing ? 1 : 0) + (lowerWickExhaustion ? 1 : 0);
   sellScore = (tickTurnDown ? 1 : 0) + (tickSlowing ? 1 : 0) + (upperWickExhaustion ? 1 : 0);
   string momentum = momentumUpStrong ? "MOMENTUM UP STRONG" :
                     momentumDownStrong ? "MOMENTUM DOWN STRONG" :
                     tickSlowing ? "TICK SLOWING" : "MOMENTUM NOT STRONG";
   lastSignalText = "ACTUAL M5: BUY " + IntegerToString(buyScore) + "/3 | SELL " +
                    IntegerToString(sellScore) + "/3 | " + momentum;
}
void ManageOpenPositions(MqlTick &t)
{
   double atrBuf[1], atr = 0.0;
   if(CopyBuffer(atrHandle, 0, 1, 1, atrBuf) == 1) atr = atrBuf[0];
   double trailDistance = MathMax(MinProfitLockPoints * _Point, atr * ReversalATRFactor);
   for(int i = PositionsTotal() - 1; i >= 0; i--)
   {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      if(PositionGetString(POSITION_SYMBOL) != _Symbol || (ulong)PositionGetInteger(POSITION_MAGIC) != MagicNumber) continue;
      ENUM_POSITION_TYPE side = (ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      double entry = PositionGetDouble(POSITION_PRICE_OPEN);
      double profit = PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);
      string key = "RYSAP_PEAK_" + (string)ticket;
      double favorable = (side == POSITION_TYPE_BUY ? t.bid : t.ask);
      if(!GlobalVariableCheck(key)) GlobalVariableSet(key, favorable);
      double extreme = GlobalVariableGet(key);
      if((side == POSITION_TYPE_BUY && favorable > extreme) || (side == POSITION_TYPE_SELL && favorable < extreme))
      { extreme = favorable; GlobalVariableSet(key, extreme); }
      double retrace = (side == POSITION_TYPE_BUY ? extreme - favorable : favorable - extreme);
      double lockedMove = (side == POSITION_TYPE_BUY ? favorable - entry : entry - favorable);
      bool allowBuy = false, allowSell = false; int buyScore = 0, sellScore = 0;
      DetectExhaustion(t, allowBuy, allowSell, buyScore, sellScore);
      bool oppositeReversal = (side == POSITION_TYPE_BUY ? allowSell : allowBuy);
      datetime openedAt = (datetime)PositionGetInteger(POSITION_TIME);
      int barsHeld = iBarShift(_Symbol, PERIOD_M5, openedAt, false);
      if(barsHeld < 0) barsHeld = 0;
      int requiredHoldBars = (int)MathMax(0, MinHoldCandlesBeforeProfitClose);
      double recentNet = TickPriceAgo(0) - TickPriceAgo(3);
      double priorNet = TickPriceAgo(4) - TickPriceAgo(7);
      double threshold = MathMax(2.0 * _Point, MomentumStrongPoints * _Point);
      bool momentumStillStrong = (side == POSITION_TYPE_BUY ?
                                  (recentNet >= threshold && priorNet > 0.0) :
                                  (recentNet <= -threshold && priorNet < 0.0));
      if(barsHeld >= requiredHoldBars && profit > 0.0 && oppositeReversal)
      {
         if(!trade.PositionClose(ticket)) Print("Signal reversal close failed: ", trade.ResultRetcodeDescription());
         else GlobalVariableDel(key);
         continue;
      }
      if(barsHeld >= requiredHoldBars && profit > 0.0 && !momentumStillStrong &&
         lockedMove >= MinProfitLockPoints * _Point && retrace >= trailDistance)
      {
         if(!trade.PositionClose(ticket)) Print("Profit-lock close failed: ", trade.ResultRetcodeDescription());
         else GlobalVariableDel(key);
      }
   }
}

void Panel()
{
   if(ObjectFind(0, P + "INFO") < 0) return;
   string st = running ? "RUNNING" : "STOPPED";
   if(DemoAccountOnly && !IsDemo()) st += " / DEMO REQUIRED";
   double projectedPts = ProjectedRange() / _Point;
   double projectedMoney = EstimateMoney(projectedPts);
   string txt = "UJI TEST MT5 - ADAPTIVE M5\nStatus: " + st +
      "\nSymbol: " + _Symbol +
      "\nLot: " + DoubleToString(lotSize, 2) +
      "\nProjected range: " + DoubleToString(projectedPts, 1) + " points (~" + (projectedMoney >= 0.0 ? "USD " + DoubleToString(projectedMoney, 2) : "USD unavailable: FX pair not found") + ")" +
      "\nSL Entry 1 / BUY: " + IntegerToString(slEntry1Points) + " points" +
      "\nSL Entry 2 / SELL: " + IntegerToString(slEntry2Points) + " points" +
      "\nTP: OFF" +
      "\nEntry: final " + IntegerToString(PendingPlacementSecondsBeforeClose) + " sec of M5 candle" +
      "\nPending max distance: " + DoubleToString(MaxPendingDistanceATR, 1) + " ATR" +
      "\nMin profit hold: " + IntegerToString(MinHoldCandlesBeforeProfitClose) + " M5 candles" +
      "\nActual signal: " + lastSignalText +
      "\nForecast: " + lastForecastText +
      "\nExhaustion: tick momentum + slowing + wick rejection";
   ObjectSetString(0, P + "INFO", OBJPROP_TEXT, txt);
   if(ObjectFind(0, lotButtonName) >= 0) ObjectSetString(0, lotButtonName, OBJPROP_TEXT, "LOT: " + DoubleToString(lotSize, 2));
   UpdateM5Timer();
}
