//+------------------------------------------------------------------+
//| Rayyan Smart Auto-Powerfull - Standalone MT5 Test EA              |
//| Independent MT5 test file. Does not modify the mobile APK.        |
//+------------------------------------------------------------------+
#property strict
#property version "1.00"
#property description "Standalone M5 adaptive pending EA with confirmed-reversal profit protection"

#include <Trade/Trade.mqh>
CTrade trade;

input bool   DemoAccountOnly = true;
input ulong  MagicNumber = 26102601;
input double InitialLot = 0.01;
input double LotStep = 0.01;
input int    StopLossPoints = 300;
input int    TakeProfitPoints = 450;
input int    MaxEntriesPerM5Candle = 2;
input int    MinDistancePoints = 30;
input double RangeOffsetFraction = 0.20;
input double ATROffsetFraction = 0.05;
input int    DeviationPoints = 20;

string P = "RYSAP_";
bool running = false;
double lotSize;
int slPoints, tpPoints;
datetime currentBar = 0;
int atrHandle = INVALID_HANDLE;
int emaFastHandle = INVALID_HANDLE;
int emaSlowHandle = INVALID_HANDLE;

int OnInit()
{
   lotSize = NormalizeLot(InitialLot);
   slPoints = MathMax(1, StopLossPoints);
   tpPoints = MathMax(1, TakeProfitPoints);
   trade.SetExpertMagicNumber(MagicNumber);
   trade.SetDeviationInPoints(DeviationPoints);
   trade.SetTypeFillingBySymbol(_Symbol);
   atrHandle = iATR(_Symbol, PERIOD_M5, 14);
   emaFastHandle = iMA(_Symbol, PERIOD_M5, 9, 0, MODE_EMA, PRICE_CLOSE);
   emaSlowHandle = iMA(_Symbol, PERIOD_M5, 21, 0, MODE_EMA, PRICE_CLOSE);
   if(atrHandle == INVALID_HANDLE || emaFastHandle == INVALID_HANDLE || emaSlowHandle == INVALID_HANDLE)
      return INIT_FAILED;

   ChartSetInteger(0, CHART_COLOR_CANDLE_BULL, clrLimeGreen);
   ChartSetInteger(0, CHART_COLOR_CANDLE_BEAR, clrRed);
   ChartSetInteger(0, CHART_COLOR_CHART_UP, clrLimeGreen);
   ChartSetInteger(0, CHART_COLOR_CHART_DOWN, clrRed);

   MakeButton("START","START",10,20,70,24,clrDarkGreen);
   MakeButton("STOP","STOP",84,20,70,24,clrFireBrick);
   MakeButton("LM","LOT -",10,50,65,22,clrDimGray);
   MakeButton("LP","LOT +",80,50,65,22,clrDimGray);
   MakeButton("SM","SL -50",10,77,65,22,clrDimGray);
   MakeButton("SP","SL +50",80,77,65,22,clrDimGray);
   MakeButton("TM","TP -50",10,104,65,22,clrDimGray);
   MakeButton("TP","TP +50",80,104,65,22,clrDimGray);

   ObjectCreate(0,P+"INFO",OBJ_LABEL,0,0,0);
   ObjectSetInteger(0,P+"INFO",OBJPROP_CORNER,CORNER_LEFT_UPPER);
   ObjectSetInteger(0,P+"INFO",OBJPROP_XDISTANCE,10);
   ObjectSetInteger(0,P+"INFO",OBJPROP_YDISTANCE,132);
   ObjectSetInteger(0,P+"INFO",OBJPROP_COLOR,clrWhite);
   ObjectSetInteger(0,P+"INFO",OBJPROP_FONTSIZE,9);

   currentBar = iTime(_Symbol,PERIOD_M5,0);
   EventSetTimer(1);
   Panel();
   Print("Rayyan standalone EA initialized. Press START to enable; analysis fixed at M5.");
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   EventKillTimer();
   if(atrHandle != INVALID_HANDLE) IndicatorRelease(atrHandle);
   if(emaFastHandle != INVALID_HANDLE) IndicatorRelease(emaFastHandle);
   if(emaSlowHandle != INVALID_HANDLE) IndicatorRelease(emaSlowHandle);
   for(int i=ObjectsTotal(0)-1;i>=0;i--)
   {
      string n=ObjectName(0,i);
      if(StringFind(n,P)==0) ObjectDelete(0,n);
   }
}

void OnTick(){ RunStrategy(); }
void OnTimer(){ Panel(); if(running) RunStrategy(); }

void OnChartEvent(const int id,const long &lp,const double &dp,const string &sp)
{
   if(id!=CHARTEVENT_OBJECT_CLICK) return;
   if(sp==P+"START") running=true;
   else if(sp==P+"STOP") running=false;
   else if(sp==P+"LM") lotSize=NormalizeLot(lotSize-LotStep);
   else if(sp==P+"LP") lotSize=NormalizeLot(lotSize+LotStep);
   else if(sp==P+"SM") slPoints=(int)MathMax(1,slPoints-50);
   else if(sp==P+"SP") slPoints+=50;
   else if(sp==P+"TM") tpPoints=(int)MathMax(1,tpPoints-50);
   else if(sp==P+"TP") tpPoints+=50;
   Panel();
}

void RunStrategy()
{
   if(!running) return;
   if(DemoAccountOnly && (ENUM_ACCOUNT_TRADE_MODE)AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO)
      return;
   if(!TerminalInfoInteger(TERMINAL_CONNECTED) || !TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) ||
      !MQLInfoInteger(MQL_TRADE_ALLOWED) || !AccountInfoInteger(ACCOUNT_TRADE_ALLOWED)) return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol,t) || t.ask<=0 || t.bid<=0) return;
   datetime bar=iTime(_Symbol,PERIOD_M5,0);
   if(bar<=0) return;
   currentBar=bar;

   ManageOpenPositions();
   if(OurPosition())
   {
      CancelPendings();
      return;
   }
   if(EntriesThisBar(bar)>=MaxEntriesPerM5Candle)
   {
      CancelPendings();
      return;
   }

   double hi=iHigh(_Symbol,PERIOD_M5,0), lo=iLow(_Symbol,PERIOD_M5,0);
   if(hi<=0 || lo<=0 || hi<lo) return;
   double atrBuf[1], atr=0;
   if(CopyBuffer(atrHandle,0,0,1,atrBuf)==1) atr=atrBuf[0];
   double offset=MathMax((hi-lo)*RangeOffsetFraction,atr*ATROffsetFraction);
   offset=MathMax(offset,MinDistancePoints*_Point);
   int stopLevel=(int)SymbolInfoInteger(_Symbol,SYMBOL_TRADE_STOPS_LEVEL);
   double gap=MathMax(MinDistancePoints,stopLevel+1)*_Point;
   double buy=NormalizePrice(MathMin(lo+offset,t.ask-gap));
   double sell=NormalizePrice(MathMax(hi-offset,t.bid+gap));
   datetime expiry=bar+PeriodSeconds(PERIOD_M5);
   if(expiry<=TimeCurrent()+2) return;

   UpdateLimit(ORDER_TYPE_BUY_LIMIT,buy,expiry);
   UpdateLimit(ORDER_TYPE_SELL_LIMIT,sell,expiry);
}

void UpdateLimit(ENUM_ORDER_TYPE type,double price,datetime expiry)
{
   ulong ticket=FindPending(type);
   double sl,tp;
   if(type==ORDER_TYPE_BUY_LIMIT)
   {
      sl=NormalizePrice(price-slPoints*_Point);
      tp=NormalizePrice(price+tpPoints*_Point);
   }
   else
   {
      sl=NormalizePrice(price+slPoints*_Point);
      tp=NormalizePrice(price-tpPoints*_Point);
   }

   if(ticket!=0)
   {
      if(!OrderSelect(ticket)) return;
      double old=OrderGetDouble(ORDER_PRICE_OPEN);
      datetime oldExp=(datetime)OrderGetInteger(ORDER_TIME_EXPIRATION);
      if(MathAbs(old-price)>=20*_Point || oldExp!=expiry)
         if(!trade.OrderModify(ticket,price,sl,tp,ORDER_TIME_SPECIFIED,expiry,0))
            Print("OrderModify failed: ",trade.ResultRetcodeDescription());
      return;
   }

   bool ok=false;
   if(type==ORDER_TYPE_BUY_LIMIT)
      ok=trade.BuyLimit(lotSize,price,_Symbol,sl,tp,ORDER_TIME_SPECIFIED,expiry,"Rayyan SAP M5");
   else
      ok=trade.SellLimit(lotSize,price,_Symbol,sl,tp,ORDER_TIME_SPECIFIED,expiry,"Rayyan SAP M5");

   if(!ok) Print("Pending placement failed: ",trade.ResultRetcode()," ",trade.ResultRetcodeDescription());
   else Print("Pending placed: ",EnumToString(type)," price=",DoubleToString(price,_Digits),
              " lot=",DoubleToString(lotSize,2)," SLpts=",slPoints," TPpts=",tpPoints);
}

// Close only profitable positions after a confirmed M5 momentum reversal.
// A single opposite candle is not enough: require EMA(9)/EMA(21) crossover
// on the last closed candle plus a matching candle direction.
void ManageOpenPositions()
{
   double fast[2], slow[2];
   if(CopyBuffer(emaFastHandle,0,1,2,fast)!=2 ||
      CopyBuffer(emaSlowHandle,0,1,2,slow)!=2) return;

   // CopyBuffer stores the oldest requested value at index 0:
   // index 0 = shift 2 (older closed candle), index 1 = shift 1 (latest closed candle).
   bool bearishReversal = (fast[0] >= slow[0] && fast[1] < slow[1] &&
                           iClose(_Symbol,PERIOD_M5,1) < iOpen(_Symbol,PERIOD_M5,1));
   bool bullishReversal = (fast[0] <= slow[0] && fast[1] > slow[1] &&
                           iClose(_Symbol,PERIOD_M5,1) > iOpen(_Symbol,PERIOD_M5,1));
   if(!bearishReversal && !bullishReversal) return;

   for(int i=PositionsTotal()-1;i>=0;i--)
   {
      ulong ticket=PositionGetTicket(i);
      if(ticket==0) continue;
      if(PositionGetString(POSITION_SYMBOL)!=_Symbol ||
         (ulong)PositionGetInteger(POSITION_MAGIC)!=MagicNumber) continue;

      double netProfit=PositionGetDouble(POSITION_PROFIT)+PositionGetDouble(POSITION_SWAP);
      if(netProfit<=0.0) continue; // Never close a losing position by this rule.

      ENUM_POSITION_TYPE type=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      bool closeNow=(type==POSITION_TYPE_BUY && bearishReversal) ||
                    (type==POSITION_TYPE_SELL && bullishReversal);
      if(closeNow)
      {
         if(trade.PositionClose(ticket))
            Print("Profit protection: closed ",EnumToString(type)," after confirmed M5 reversal. Net profit before close=",DoubleToString(netProfit,2));
         else
            Print("Profit-protection close failed: ",trade.ResultRetcodeDescription());
      }
   }
}

bool OurPosition()
{
   for(int i=PositionsTotal()-1;i>=0;i--)
   {
      ulong ticket=PositionGetTicket(i);
      if(ticket==0) continue;
      if(PositionGetString(POSITION_SYMBOL)==_Symbol &&
         (ulong)PositionGetInteger(POSITION_MAGIC)==MagicNumber) return true;
   }
   return false;
}

ulong FindPending(ENUM_ORDER_TYPE type)
{
   for(int i=OrdersTotal()-1;i>=0;i--)
   {
      ulong ticket=OrderGetTicket(i);
      if(ticket==0) continue;
      if(OrderGetString(ORDER_SYMBOL)!=_Symbol ||
         (ulong)OrderGetInteger(ORDER_MAGIC)!=MagicNumber) continue;
      if((ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE)==type) return ticket;
   }
   return 0;
}

void CancelPendings()
{
   for(int i=OrdersTotal()-1;i>=0;i--)
   {
      ulong ticket=OrderGetTicket(i);
      if(ticket==0) continue;
      if(OrderGetString(ORDER_SYMBOL)!=_Symbol ||
         (ulong)OrderGetInteger(ORDER_MAGIC)!=MagicNumber) continue;
      ENUM_ORDER_TYPE ty=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
      if(ty==ORDER_TYPE_BUY_LIMIT || ty==ORDER_TYPE_SELL_LIMIT)
         if(!trade.OrderDelete(ticket)) Print("OrderDelete failed: ",trade.ResultRetcodeDescription());
   }
}

int EntriesThisBar(datetime bar)
{
   if(!HistorySelect(bar,TimeCurrent())) return 0;
   int count=0;
   for(int i=0;i<HistoryDealsTotal();i++)
   {
      ulong d=HistoryDealGetTicket(i);
      if(d==0) continue;
      if(HistoryDealGetString(d,DEAL_SYMBOL)!=_Symbol ||
         (ulong)HistoryDealGetInteger(d,DEAL_MAGIC)!=MagicNumber) continue;
      ENUM_DEAL_ENTRY e=(ENUM_DEAL_ENTRY)HistoryDealGetInteger(d,DEAL_ENTRY);
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
   double minv=SymbolInfoDouble(_Symbol,SYMBOL_VOLUME_MIN);
   double maxv=SymbolInfoDouble(_Symbol,SYMBOL_VOLUME_MAX);
   double step=SymbolInfoDouble(_Symbol,SYMBOL_VOLUME_STEP);
   if(step<=0) step=0.01;
   v=MathMax(minv,MathMin(maxv,v));
   v=MathFloor(v/step+1e-8)*step;
   return NormalizeDouble(v,4);
}

double NormalizePrice(double p)
{
   double ts=SymbolInfoDouble(_Symbol,SYMBOL_TRADE_TICK_SIZE);
   if(ts<=0) ts=_Point;
   return NormalizeDouble(MathRound(p/ts)*ts,_Digits);
}

void MakeButton(string id,string caption,int x,int y,int w,int h,color bg)
{
   string n=P+id;
   ObjectCreate(0,n,OBJ_BUTTON,0,0,0);
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
   string st=running ? "RUNNING" : "STOPPED";
   if(DemoAccountOnly && !IsDemo()) st+=" / DEMO REQUIRED";
   string txt="SMART AUTO-POWERFULL (M5)\nStatus: "+st+
      "\nSymbol: "+_Symbol+"\nLot: "+DoubleToString(lotSize,2)+
      "\nSL: "+IntegerToString(slPoints)+" points"+
      "\nTP: "+IntegerToString(tpPoints)+" points"+
      "\nMax entries/candle: "+IntegerToString(MaxEntriesPerM5Candle);
   ObjectSetString(0,P+"INFO",OBJPROP_TEXT,txt);
}