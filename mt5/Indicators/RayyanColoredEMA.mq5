#property strict
#property indicator_chart_window
#property indicator_buffers 2
#property indicator_plots   2

#property indicator_label1  "EMA 50"
#property indicator_type1   DRAW_LINE
#property indicator_color1  clrOrange
#property indicator_style1  STYLE_SOLID
#property indicator_width1  2

#property indicator_label2  "EMA 200"
#property indicator_type2   DRAW_LINE
#property indicator_color2  clrMediumOrchid
#property indicator_style2  STYLE_SOLID
#property indicator_width2  2

input int  InpEMA50Period = 50;
input int  InpEMA200Period = 200;
input bool InpShowEMA50 = true;
input bool InpShowEMA200 = true;

double Ema50Buffer[];
double Ema200Buffer[];
int hEMA50 = INVALID_HANDLE;
int hEMA200 = INVALID_HANDLE;

int OnInit()
{
   if(InpEMA50Period < 1 || InpEMA200Period < 1)
      return INIT_PARAMETERS_INCORRECT;

   SetIndexBuffer(0, Ema50Buffer, INDICATOR_DATA);
   SetIndexBuffer(1, Ema200Buffer, INDICATOR_DATA);
   ArraySetAsSeries(Ema50Buffer, true);
   ArraySetAsSeries(Ema200Buffer, true);

   PlotIndexSetInteger(0, PLOT_DRAW_TYPE, InpShowEMA50 ? DRAW_LINE : DRAW_NONE);
   PlotIndexSetInteger(1, PLOT_DRAW_TYPE, InpShowEMA200 ? DRAW_LINE : DRAW_NONE);
   PlotIndexSetInteger(0, PLOT_LINE_COLOR, clrOrange);
   PlotIndexSetInteger(1, PLOT_LINE_COLOR, clrMediumOrchid);
   PlotIndexSetInteger(0, PLOT_LINE_WIDTH, 2);
   PlotIndexSetInteger(1, PLOT_LINE_WIDTH, 2);
   PlotIndexSetString(0, PLOT_LABEL, "EMA " + IntegerToString(InpEMA50Period));
   PlotIndexSetString(1, PLOT_LABEL, "EMA " + IntegerToString(InpEMA200Period));
   IndicatorSetString(INDICATOR_SHORTNAME, "Rayyan Colored EMA (" +
                      IntegerToString(InpEMA50Period) + "/" +
                      IntegerToString(InpEMA200Period) + ")");

   hEMA50 = iMA(_Symbol, _Period, InpEMA50Period, 0, MODE_EMA, PRICE_CLOSE);
   hEMA200 = iMA(_Symbol, _Period, InpEMA200Period, 0, MODE_EMA, PRICE_CLOSE);
   if(hEMA50 == INVALID_HANDLE || hEMA200 == INVALID_HANDLE)
   {
      Print("RAYYAN COLORED EMA init failed handles=", hEMA50, ",", hEMA200);
      return INIT_FAILED;
   }
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   if(hEMA50 != INVALID_HANDLE) IndicatorRelease(hEMA50);
   if(hEMA200 != INVALID_HANDLE) IndicatorRelease(hEMA200);
}

int OnCalculate(const int rates_total,
                const int prev_calculated,
                const datetime &time[],
                const double &open[],
                const double &high[],
                const double &low[],
                const double &close[],
                const long &tick_volume[],
                const long &volume[],
                const int &spread[])
{
   if(rates_total <= 0) return 0;
   int copied50 = CopyBuffer(hEMA50, 0, 0, rates_total, Ema50Buffer);
   int copied200 = CopyBuffer(hEMA200, 0, 0, rates_total, Ema200Buffer);
   if(copied50 <= 0 || copied200 <= 0)
      return prev_calculated;
   return rates_total;
}
