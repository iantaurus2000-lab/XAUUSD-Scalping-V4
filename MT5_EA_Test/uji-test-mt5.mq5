//+------------------------------------------------------------------+
//| uji-test-mt5.mq5 - Adaptive M5 pending-order test EA              |
//+------------------------------------------------------------------+
#property strict
#property version "1.10"
#property description "Adaptive M5 pending EA; editable SL per side; no TP"

#include <Trade/Trade.mqh>
CTrade trade;

input bool   DemoAccountOnly = true;
input ulong  MagicNumber = 26102601;
input double InitialLot = 0.01;
input double LotStep = 0.01;
input int    MaxEntriesPerM5Candle = 2;
input int    MinDistancePoints = 30;
input int    DistributionLookback = 20;
input double RangeOffsetFraction = 0.20;
input int    DeviationPoints = 20;

string P = "RYSAP_";
bool running = false;
double lotSize = 0.01;
int slEntry1Points = 0; // BUY pending SL; 0 = disabled
int slEntry2Points = 0; // SELL pending SL; 0 = disabled
datetime currentBar = 0;
int atrHandle = INVALID_HANDLE;
string edit1Name, edit2Name;

int OnInit()
{
   lotSize = NormalizeLot(InitialLot);
   trade.SetExpertMagicNumber(MagicNumber);
   trade.SetDeviationInPoints(DeviationPoints);
   trade.SetTypeFillingBySymbol(_Symbol);
   atrHandle = iATR(_Symbol, PERIOD_M5, 14);
   if(atrHandle == INVALID_HANDLE) return INIT_FAILED;

   MakeButton("TOGGLE", "START", 10, 20, 90, 26, clrDarkGreen);
   MakeButton("LM", "LOT -", 10, 53, 65, 22, clrDimGray);
   MakeButton("LP", "LOT +", 80, 53, 65, 22, clrDimGray);

   MakeLabel("SL1LBL", "SL Entry 1 / BUY (points; 0=OFF)", 10, 83);
   edit1Name = P + "SL1EDIT";
   MakeEdit(edit1Name, "0", 10, 101, 125, 23);
   MakeButton("SL1SET", "SET SL1", 140, 101, 75, 23, clrSteelBlue);

   MakeLabel("SL2LBL", "SL Entry 2 / SELL (points; 0=OFF)", 10, 130);
   edit2Name = P + "SL2EDIT";
   MakeEdit(edit2Name, "0", 10, 148, 125, 23);
   MakeButton("SL2SET", "SET SL2", 140, 148, 75, 23, clrSteelBlue);

   MakeLabel("INFO", "", 10, 180);
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

void OnTick() { RunStrategy(); }
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
   if(!running) return;
   if(DemoAccountOnly && !IsDemo()) return;
   if(!TerminalInfoInteger(TERMINAL_CONNECTED) ||
      !TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) ||
      !MQLInfoInteger(MQL_TRADE_ALLOWED) ||
      !AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)) return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0) return;
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
   double offset = MathMax(projectedRange * RangeOffsetFraction, MinDistancePoints * _Point);
   int stopLevel = (int)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   double gap = MathMax(MinDistancePoints, stopLevel + 1) * _Point;

   // BUY is referenced to the lower wick; SELL to the upper wick.
   double buy = NormalizePrice(MathMin(lo + offset, t.ask - gap));
   double sell = NormalizePrice(MathMax(hi - offset, t.bid + gap));
   if(buy >= t.ask) buy = NormalizePrice(t.ask - gap);
   if(sell <= t.bid) sell = NormalizePrice(t.bid + gap);

   datetime expiry = bar + PeriodSeconds(PERIOD_M5);
   if(expiry <= TimeCurrent() + 2) return;
   UpdateLimit(ORDER_TYPE_BUY_LIMIT, buy, expiry);
   UpdateLimit(ORDER_TYPE_SELL_LIMIT, sell, expiry);
}

double ProjectedRange()
{
   double atrBuf[1], atr = 0;
   if(CopyBuffer(atrHandle, 0, 1, 1, atrBuf) == 1) atr = atrBuf[0];
   double sum = 0;
   int used = 0;
   int count = (int)MathMax(1, MathMin(DistributionLookback, 200));
   for(int i = 1; i <= count; i++)
   {
      double h = iHigh(_Symbol, PERIOD_M5, i);
      double l = iLow(_Symbol, PERIOD_M5, i);
      if(h > 0 && l > 0 && h >= l) { sum += h - l; used++; }
   }
   double avgRange = (used > 0 ? sum / used : 0);
   if(atr > 0 && avgRange > 0) return 0.5 * atr + 0.5 * avgRange;
   if(atr > 0) return atr;
   return avgRange;
}

void UpdateLimit(ENUM_ORDER_TYPE type, double price, datetime expiry)
{
   // Entry 1 = BUY pending SL; Entry 2 = SELL pending SL. TP is always disabled.
   int slPts = (type == ORDER_TYPE_BUY_LIMIT ? slEntry1Points : slEntry2Points);
   double sl = 0.0;
   if(slPts > 0)
   {
      if(type == ORDER_TYPE_BUY_LIMIT) sl = NormalizePrice(price - slPts * _Point);
      else sl = NormalizePrice(price + slPts * _Point);
   }

   ulong ticket = FindPending(type);
   if(ticket != 0)
   {
      if(!OrderSelect(ticket)) return;
      double old = OrderGetDouble(ORDER_PRICE_OPEN);
      datetime oldExp = (datetime)OrderGetInteger(ORDER_TIME_EXPIRATION);
      if(MathAbs(old - price) >= 20 * _Point || oldExp != expiry ||
         MathAbs(OrderGetDouble(ORDER_SL) - sl) >= _Point)
      {
         if(!trade.OrderModify(ticket, price, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, 0))
            Print("OrderModify failed: ", trade.ResultRetcodeDescription());
      }
      return;
   }

   bool ok = false;
   if(type == ORDER_TYPE_BUY_LIMIT)
      ok = trade.BuyLimit(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, "uji-test-mt5 BUY");
   else
      ok = trade.SellLimit(lotSize, price, _Symbol, sl, 0.0, ORDER_TIME_SPECIFIED, expiry, "uji-test-mt5 SELL");

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
   for(int i = OrdersTotal()-1; i >= 0; i--)
   {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0) continue;
      if(OrderGetString(ORDER_SYMBOL) != _Symbol ||
         (ulong)OrderGetInteger(ORDER_MAGIC) != MagicNumber) continue;
      if((ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE) == type) return ticket;
   }
   return 0;
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
      if(ty == ORDER_TYPE_BUY_LIMIT || ty == ORDER_TYPE_SELL_LIMIT)
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

void Panel()
{
   if(ObjectFind(0, P + "INFO") < 0) return;
   string st = running ? "RUNNING" : "STOPPED";
   if(DemoAccountOnly && !IsDemo()) st += " / DEMO REQUIRED";
   string txt = "UJI TEST MT5 - ADAPTIVE M5\nStatus: " + st +
      "\nSymbol: " + _Symbol +
      "\nLot: " + DoubleToString(lotSize, 2) +
      "\nSL Entry 1 / BUY: " + IntegerToString(slEntry1Points) + " points" +
      "\nSL Entry 2 / SELL: " + IntegerToString(slEntry2Points) + " points" +
      "\nTP: OFF" +
      "\nProjected full range: " + DoubleToString(ProjectedRange() / _Point, 1) + " points";
   ObjectSetString(0, P + "INFO", OBJPROP_TEXT, txt);
}
