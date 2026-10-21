#property strict
// Rayyan8 native MT5 chart indicator controller.
// Independent queue: does NOT poll /next and cannot consume trade commands.
input string GatewayUrl="http://192.168.0.109:8787";
input int PollSeconds=1;
input int SRLookbackBars=80;
input int FiboLookbackBars=60;

int hEma50=INVALID_HANDLE, hEma200=INVALID_HANDLE;
bool onEma50=true,onEma200=true,onSR=true,onFibo=true;
string lastCommandId="";
uint lastScreenshotTick=0;
datetime lastBar=0;

int OnInit(){
   hEma50=iMA(_Symbol,_Period,50,0,MODE_EMA,PRICE_CLOSE);
   hEma200=iMA(_Symbol,_Period,200,0,MODE_EMA,PRICE_CLOSE);
   if(hEma50==INVALID_HANDLE||hEma200==INVALID_HANDLE){
      Print("RAYYAN8 INDICATOR INIT ERROR handles=",hEma50,",",hEma200);
      return INIT_FAILED;
   }
   EventSetTimer(MathMax(1,PollSeconds));
   applyAll();
   Print("RAYYAN8 native indicators controller started. Separate /indicator_next queue; trade EA untouched.");
   return INIT_SUCCEEDED;
}
void OnDeinit(const int reason){
   EventKillTimer();
   ObjectDelete(0,"RAYYAN8_SR_RESISTANCE");
   ObjectDelete(0,"RAYYAN8_SR_SUPPORT");
   ObjectDelete(0,"RAYYAN8_FIBO");
   if(hEma50!=INVALID_HANDLE)IndicatorRelease(hEma50);
   if(hEma200!=INVALID_HANDLE)IndicatorRelease(hEma200);
}
void OnTick(){}
void OnTimer(){
   string cmd="";
   if(httpGet("/indicator_next",cmd)&&StringLen(cmd)>5){
      string id=field(cmd,"id");
      if(id!=""&&id!=lastCommandId){
         string targetSymbol=field(cmd,"symbol");
         ENUM_TIMEFRAMES targetPeriod=parsePeriod(field(cmd,"tf"));
         if(targetSymbol!=""&&targetPeriod!=PERIOD_CURRENT&&
            (ChartSymbol(0)!=targetSymbol||ChartPeriod(0)!=targetPeriod)){
            if(SymbolSelect(targetSymbol,true)&&ChartSetSymbolPeriod(0,targetSymbol,targetPeriod)){
               Print("RAYYAN8 chart sync requested ",targetSymbol," ",field(cmd,"tf"));
               uploadChartSnapshot();
               return;
            }
            Print("RAYYAN8 chart sync failed target=",targetSymbol," tf=",field(cmd,"tf")," err=",GetLastError());
         }
         onEma50=(field(cmd,"ema50")=="1");
         onEma200=(field(cmd,"ema200")=="1");
         onSR=(field(cmd,"sr")=="1");
         onFibo=(field(cmd,"fibo")=="1");
         applyAll();
         lastCommandId=id;
         string ack="";
         httpGet("/indicator_ack?id="+id,ack);
         Print("RAYYAN8 indicators ACK id=",id," EMA50=",onEma50," EMA200=",onEma200,
               " SR=",onSR," FIBO=",onFibo);
      }
   }
   datetime bar=iTime(_Symbol,_Period,0);
   if(bar!=0&&bar!=lastBar){lastBar=bar;applyAll();}
   uploadChartSnapshot();
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
void uploadChartSnapshot(){
   if(GetTickCount()-lastScreenshotTick<900)return;
   lastScreenshotTick=GetTickCount();
   string filename="rayyan8_chart.png";
   ResetLastError();
   if(!ChartScreenShot(0,filename,1000,500,ALIGN_RIGHT)){
      Print("RAYYAN8 ChartScreenShot failed err=",GetLastError());return;
   }
   Sleep(120);
   int fh=FileOpen(filename,FILE_READ|FILE_BIN|FILE_SHARE_READ);
   if(fh==INVALID_HANDLE){Print("RAYYAN8 screenshot file open failed err=",GetLastError());return;}
   ulong size=FileSize(fh);
   if(size<8||size>2000000){FileClose(fh);Print("RAYYAN8 screenshot size invalid=",size);return;}
   char data[];ArrayResize(data,(int)size);
   uint read=FileReadArray(fh,data,0,(uint)size);
   FileClose(fh);
   if(read!=size){Print("RAYYAN8 screenshot read mismatch ",read,"/",size);return;}
   char result[];string responseHeaders="";
   string headers="Content-Type: image/png\r\n";
   ResetLastError();
   int code=WebRequest("POST",GatewayUrl+"/chart/upload",headers,2500,data,result,responseHeaders);
   if(code!=200)Print("RAYYAN8 chart upload failed HTTP=",code," err=",GetLastError());
}

bool httpGet(string path,string &out){
   char data[],result[];
   string responseHeaders="";
   ResetLastError();
   int code=WebRequest("GET",GatewayUrl+path,"",1800,data,result,responseHeaders);
   if(code<0){Print("RAYYAN8 indicator gateway error=",GetLastError()," path=",path);return false;}
   out=CharArrayToString(result,0,-1,CP_UTF8);
   return code==200;
}
string field(string source,string key){
   string prefix=key+"=";int a=StringFind(source,prefix);if(a<0)return "";
   a+=StringLen(prefix);int b=StringFind(source,";",a);
   if(b<0)b=StringLen(source);
   return StringSubstr(source,a,b-a);
}
void applyAll(){
   setEma(hEma50,50,onEma50);
   setEma(hEma200,200,onEma200);
   updateSR();
   updateFibo();
   ChartRedraw(0);
}
void setEma(int handle,int period,bool enabled){
   if(handle==INVALID_HANDLE)return;
   string periodText="("+IntegerToString(period)+")";
   string foundName="";
   int total=ChartIndicatorsTotal(0,0);
   for(int i=total-1;i>=0;i--){
      string name=ChartIndicatorName(0,0,i);
      if(StringFind(name,periodText)>=0){
         foundName=name;
         break;
      }
   }
   bool present=(foundName!="");
   if(enabled&&!present){
      if(!ChartIndicatorAdd(0,0,handle))
         Print("RAYYAN8 EMA add failed period=",period," err=",GetLastError());
   }else if(!enabled&&present){
      if(!ChartIndicatorDelete(0,0,foundName))
         Print("RAYYAN8 EMA delete failed period=",period," name=",foundName," err=",GetLastError());
   }
}
void updateSR(){
   if(!onSR){ObjectDelete(0,"RAYYAN8_SR_RESISTANCE");ObjectDelete(0,"RAYYAN8_SR_SUPPORT");return;}
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
   datetime t1=th<tl?th:tl;
   datetime t2=th<tl?tl:th;
   double p1=th<tl?ph:pl;
   double p2=th<tl?pl:ph;
   if(ObjectFind(0,"RAYYAN8_FIBO")<0)
      ObjectCreate(0,"RAYYAN8_FIBO",OBJ_FIBO,0,t1,p1,t2,p2);
   else{
      ObjectMove(0,"RAYYAN8_FIBO",0,t1,p1);
      ObjectMove(0,"RAYYAN8_FIBO",1,t2,p2);
   }
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_COLOR,clrGold);
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_RAY_RIGHT,true);
   ObjectSetInteger(0,"RAYYAN8_FIBO",OBJPROP_LEVELS,6);
   double levels[]={0.0,0.236,0.382,0.5,0.618,1.0};
   string labels[]={"0","23.6","38.2","50","61.8","100"};
   for(int i=0;i<6;i++){
      ObjectSetDouble(0,"RAYYAN8_FIBO",OBJPROP_LEVELVALUE,i,levels[i]);
      ObjectSetString(0,"RAYYAN8_FIBO",OBJPROP_LEVELTEXT,i,labels[i]);
   }
}
