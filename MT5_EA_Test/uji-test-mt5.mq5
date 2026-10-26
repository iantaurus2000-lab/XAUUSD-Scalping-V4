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
input int    MaxEntriesPerM5Candle = 2;
input int    MinDistancePoints = 30;
input int    DistributionLookback = 20;
input double RangeOffsetFraction = 0.20; // retained for compatibility; endpoint entries are used
input double ReversalATRFactor = 0.25;
input int    MinProfitLockPoints = 5;
input int    ExhaustionMinScore = 3; // out of 4 independent clues
input int    TickSamplesForAnalysis = 12;
input int    DeviationPoints = 20;

string P = "RYSAP_";
bool running = false;
double lotSize = 0.01;
int slEntry1Points = 0; // BUY pending SL; 0 = disabled
int slEntry2Points = 0; // SELL pending SL; 0 = disabled
datetime currentBar = 0;
int atrHandle = INVALID_HANDLE;
int rsiHandle = INVALID_HANDLE, bandsHandle = INVALID_HANDLE, adxHandle = INVALID_HANDLE;
double tickPrices[32];
int tickWriteIndex = 0, tickSampleCount = 0;
string lastSignalText = "WAITING FOR TICK DATA";
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
   rsiHandle = iRSI(_Symbol, PERIOD_M5, 14, PRICE_CLOSE);
   bandsHandle = iBands(_Symbol, PERIOD_M5, 20, 0, 2.0, PRICE_CLOSE);
   adxHandle = iADX(_Symbol, PERIOD_M5, 14);
   if(atrHandle == INVALID_HANDLE || rsiHandle == INVALID_HANDLE || bandsHandle == INVALID_HANDLE || adxHandle == INVALID_HANDLE) return INIT_FAILED;

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
   if(rsiHandle != INVALID_HANDLE) IndicatorRelease(rsiHandle);
   if(bandsHandle != INVALID_HANDLE) IndicatorRelease(bandsHandle);
   if(adxHandle != INVALID_HANDLE) IndicatorRelease(adxHandle);
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
void OnTimer() { Panel(); if(running) RunStrategy(); }

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

   if(OurPosition()) { CancelPendings(); return; }
   if(EntriesThisBar(bar) >= MaxEntriesPerM5Candle) { CancelPendings(); return; }

   double hi = iHigh(_Symbol, PERIOD_M5, 0);
   double lo = iLow(_Symbol, PERIOD_M5, 0);
   if(hi <= 0 || lo <= 0 || hi < lo) return;

   double projectedRange = ProjectedRange();
   if(projectedRange <= 0) return;
   int stopLevel = (int)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   double gap = MathMax(MinDistancePoints, stopLevel + 1) * _Point;

   // Predict candle endpoints from the distribution of completed M5 candles.
   double predictedUpper = NormalizePrice(MathMax(hi, lo + projectedRange));
   double predictedLower = NormalizePrice(MathMin(lo, hi - projectedRange));
   datetime expiry = bar + PeriodSeconds(PERIOD_M5);
   if(expiry <= TimeCurrent() + 2) return;
   bool allowBuy = false, allowSell = false;
   int buyScore = 0, sellScore = 0;
   DetectExhaustion(t, allowBuy, allowSell, buyScore, sellScore);
   lastSignalText = "BUY " + IntegerToString(buyScore) + "/4 | SELL " + IntegerToString(sellScore) + "/4";
   if(allowBuy) UpdatePendingAtEndpoint(true, predictedLower, expiry, t, gap);
   else CancelPendingSide(true);
   if(allowSell) UpdatePendingAtEndpoint(false, predictedUpper, expiry, t, gap);
   else CancelPendingSide(false);
}

double ProjectedRange()
{
   double atrBuf[1], atr = 0;
   if(CopyBuffer(atrHandle, 0, 1, 1, atrBuf) == 1) atr = atrBuf[0];
   double ranges[200]; int used = 0;
   int count = (int)MathMax(1, MathMin(DistributionLookback, 200));
   double upperWickSum = 0, lowerWickSum = 0;
   for(int i = 1; i <= count; i++)
   {
      double h = iHigh(_Symbol, PERIOD_M5, i), l = iLow(_Symbol, PERIOD_M5, i);
      double o = iOpen(_Symbol, PERIOD_M5, i), cl = iClose(_Symbol, PERIOD_M5, i);
      if(h <= 0 || l <= 0 || h < l || o <= 0 || cl <= 0) continue;
      ranges[used++] = h - l;
      upperWickSum += MathMax(0.0, h - MathMax(o, cl));
      lowerWickSum += MathMax(0.0, MathMin(o, cl) - l);
   }
   if(used <= 0) return atr;
   for(int a = 0; a < used - 1; a++)
      for(int b = a + 1; b < used; b++)
         if(ranges[b] < ranges[a]) { double tmp = ranges[a]; ranges[a] = ranges[b]; ranges[b] = tmp; }
   double avgRange = 0;
   for(int k = 0; k < used; k++) avgRange += ranges[k];
   avgRange /= used;
   double q75 = ranges[(int)MathMin(used - 1, MathFloor((used - 1) * 0.75))];
   double wickBias = 0;
   if(upperWickSum + lowerWickSum > 0) wickBias = (upperWickSum - lowerWickSum) / (upperWickSum + lowerWickSum);
   double distRange = (0.65 * q75 + 0.35 * avgRange) * (1.0 + MathMax(-0.08, MathMin(0.08, wickBias * 0.08)));
   if(atr > 0) return 0.5 * atr + 0.5 * distRange;
   return distRange;
}

void UpdatePendingAtEndpoint(bool isBuy, double target, datetime expiry, MqlTick &t, double gap)
{
   ENUM_ORDER_TYPE type;
   if(isBuy) type = (target < t.ask - gap ? ORDER_TYPE_BUY_LIMIT : ORDER_TYPE_BUY_STOP);
   else type = (target > t.bid + gap ? ORDER_TYPE_SELL_LIMIT : ORDER_TYPE_SELL_STOP);
   target = NormalizePrice(target);
   if(isBuy && type == ORDER_TYPE_BUY_LIMIT && target >= t.ask - gap) target = NormalizePrice(t.ask - gap);
   if(isBuy && type == ORDER_TYPE_BUY_STOP && target <= t.ask + gap) target = NormalizePrice(t.ask + gap);
   if(!isBuy && type == ORDER_TYPE_SELL_LIMIT && target <= t.bid + gap) target = NormalizePrice(t.bid + gap);
   if(!isBuy && type == ORDER_TYPE_SELL_STOP && target >= t.bid - gap) target = NormalizePrice(t.bid - gap);
   UpdateLimit(type, target, expiry);
}

void UpdateLimit(ENUM_ORDER_TYPE type, double price, datetime expiry)
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

   ulong ticket = FindPending(type);
   if(ticket != 0)
   {
      if(!OrderSelect(ticket)) return;
      ENUM_ORDER_TYPE existingType = (ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(existingType != type)
      {
         if(!trade.OrderDelete(ticket)) { Print("Pending type switch delete failed: ", trade.ResultRetcodeDescription()); return; }
         ticket = 0;
      }
      if(ticket == 0) { /* create the correctly typed pending order below */ }
      else
      {
      double old = OrderGetDouble(ORDER_PRICE_OPEN);
      datetime oldExp = (datetime)OrderGetInteger(ORDER_TIME_EXPIRATION);
      if(MathAbs(old - price) >= 2 * _Point || oldExp != expiry ||
         MathAbs(OrderGetDouble(ORDER_SL) - sl) >= _Point)
      {
         if(!trade.OrderModify(ticket, price, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, 0))
            Print("OrderModify failed: ", trade.ResultRetcodeDescription());
      }
      return;
      }
   }

   bool ok = false;
   if(type == ORDER_TYPE_BUY_LIMIT)
      ok = trade.BuyLimit(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, "uji-test-mt5 BUY");
   else if(type == ORDER_TYPE_BUY_STOP)
      ok = trade.BuyStop(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, "uji-test-mt5 BUY");
   else if(type == ORDER_TYPE_SELL_LIMIT)
      ok = trade.SellLimit(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, "uji-test-mt5 SELL");
   else
      ok = trade.SellStop(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, "uji-test-mt5 SELL");

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

int EntriesThisBar(datetime bar)
{
   if(!HistorySelect(bar, TimeCurrent())) return 0;
   int count = 0;
   for(int i = 0; i < HistoryDealsTotal(); i++)
   {
      ulong d = HistoryDealGetTicket(i);
      if(d == 0) continue;
      if(HistoryDealGetString(d, DEAL_SYMBOL) != _Symbol ||
         (ulong)HistoryDealGetInteger(d, DEAL_MAGIC) != MagicNumber) continue;
      ENUM_DEAL_ENTRY e = (ENUM_DEAL_ENTRY)HistoryDealGetInteger(d, DEAL_ENTRY);
      if(e == DEAL_ENTRY_IN || e == DEAL_ENTRY_INOUT) count++;
   }
   return count;
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

double EstimateMoney(double points)
{
   if(points <= 0 || lotSize <= 0) return 0.0;
   double openPrice = SymbolInfoDouble(_Symbol, SYMBOL_ASK);
   if(openPrice <= 0) return 0.0;
   double result = 0.0;
   if(!OrderCalcProfit(ORDER_TYPE_BUY, _Symbol, lotSize, openPrice, openPrice + points * _Point, result)) return 0.0;
   return MathAbs(result);
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
   double rsi[3], bandsUpper[1], bandsLower[1], adx[2], plusDI[2], minusDI[2], atrBuf[1];
   if(CopyBuffer(rsiHandle, 0, 0, 3, rsi) < 3 ||
      CopyBuffer(bandsHandle, 1, 0, 1, bandsUpper) < 1 ||
      CopyBuffer(bandsHandle, 2, 0, 1, bandsLower) < 1 ||
      CopyBuffer(adxHandle, 0, 0, 2, adx) < 2 ||
      CopyBuffer(adxHandle, 1, 0, 2, plusDI) < 2 ||
      CopyBuffer(adxHandle, 2, 0, 2, minusDI) < 2 ||
      CopyBuffer(atrHandle, 0, 1, 1, atrBuf) < 1) { lastSignalText = "INDICATORS LOADING"; return; }

   double hi = iHigh(_Symbol, PERIOD_M5, 0), lo = iLow(_Symbol, PERIOD_M5, 0);
   double op = iOpen(_Symbol, PERIOD_M5, 0), cl = iClose(_Symbol, PERIOD_M5, 0);
   double range = MathMax(hi - lo, _Point);
   double lowerWick = MathMax(0.0, MathMin(op, cl) - lo);
   double upperWick = MathMax(0.0, hi - MathMax(op, cl));
   double atr = atrBuf[0];

   bool tickTurnUp = false, tickTurnDown = false, tickSlowing = false;
   int required = (int)MathMax(8, MathMin(24, TickSamplesForAnalysis));
   if(tickSampleCount >= required)
   {
      double recentNet = TickPriceAgo(0) - TickPriceAgo(3);
      double priorNet = TickPriceAgo(4) - TickPriceAgo(7);
      double olderNet = TickPriceAgo(7) - TickPriceAgo(MathMin(required - 1, 11));
      double turnThreshold = MathMax(2.0 * _Point, atr * 0.005);
      tickTurnUp = (olderNet < -turnThreshold && recentNet > 0 && priorNet <= turnThreshold);
      tickTurnDown = (olderNet > turnThreshold && recentNet < 0 && priorNet >= -turnThreshold);
      tickSlowing = (MathAbs(recentNet) < MathAbs(olderNet) * 0.55);
   }

   bool lowerExtreme = (lo <= bandsLower[0] + MathMax(atr * 0.12, 2 * _Point)) || (lowerWick / range >= 0.35);
   bool upperExtreme = (hi >= bandsUpper[0] - MathMax(atr * 0.12, 2 * _Point)) || (upperWick / range >= 0.35);
   bool rsiBuy = (rsi[0] <= 45.0 && rsi[0] > rsi[1]) || (rsi[1] < 30.0 && rsi[0] > rsi[1]);
   bool rsiSell = (rsi[0] >= 55.0 && rsi[0] < rsi[1]) || (rsi[1] > 70.0 && rsi[0] < rsi[1]);
   bool directionBuy = (plusDI[0] > plusDI[1]) || (adx[0] < adx[1] && plusDI[0] >= minusDI[0]);
   bool directionSell = (minusDI[0] > minusDI[1]) || (adx[0] < adx[1] && minusDI[0] >= plusDI[0]);

   if(lowerExtreme) buyScore++;
   if(rsiBuy) buyScore++;
   if(tickTurnUp) buyScore++;
   if(directionBuy && tickSlowing) buyScore++;
   if(upperExtreme) sellScore++;
   if(rsiSell) sellScore++;
   if(tickTurnDown) sellScore++;
   if(directionSell && tickSlowing) sellScore++;

   // A real tick turn is mandatory; other evidence must reach the configurable confluence score.
   allowBuy = tickTurnUp && buyScore >= (int)MathMax(2, MathMin(4, ExhaustionMinScore));
   allowSell = tickTurnDown && sellScore >= (int)MathMax(2, MathMin(4, ExhaustionMinScore));
   if(allowBuy && allowSell) { allowBuy = false; allowSell = false; }
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
      if(profit > 0.0 && oppositeReversal)
      {
         if(!trade.PositionClose(ticket)) Print("Signal reversal close failed: ", trade.ResultRetcodeDescription());
         else GlobalVariableDel(key);
         continue;
      }
      if(profit > 0.0 && lockedMove >= MinProfitLockPoints * _Point && retrace >= trailDistance)
      {
         if(!trade.PositionClose(ticket)) Print("Reversal close failed: ", trade.ResultRetcodeDescription());
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
      "\nProjected range: " + DoubleToString(projectedPts, 1) + " points (~" + AccountInfoString(ACCOUNT_CURRENCY) + " " + DoubleToString(projectedMoney, 2) + ")" +
      "\nSL Entry 1 / BUY: " + IntegerToString(slEntry1Points) + " points" +
      "\nSL Entry 2 / SELL: " + IntegerToString(slEntry2Points) + " points" +
      "\nTP: OFF" +
      "\nEntry: predicted wick endpoints" +
      "\nExhaustion: " + lastSignalText;
   ObjectSetString(0, P + "INFO", OBJPROP_TEXT, txt);
   if(ObjectFind(0, lotButtonName) >= 0) ObjectSetString(0, lotButtonName, OBJPROP_TEXT, "LOT: " + DoubleToString(lotSize, 2));
   UpdateM5Timer();
}
