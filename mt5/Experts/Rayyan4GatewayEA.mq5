#property strict
#include <Trade/Trade.mqh>
CTrade trade;

input string GatewayUrl="http://192.168.0.109:8787";
input string AllowedSymbol="XAUUSDm";
input bool DemoOnly=true;
input int PollSeconds=1;

string lastId="";
datetime lastDiag=0;

int OnInit(){
   EventSetTimer(MathMax(1,PollSeconds));
   Print("RAYYAN4 START Gateway=",GatewayUrl," Symbol=",AllowedSymbol);
   return(INIT_SUCCEEDED);
}
void OnDeinit(const int r){ EventKillTimer(); }
void OnTick(){}

void OnTimer(){
   string url=GatewayUrl+"/next";
   char data[],result[]; string headers;
   ResetLastError();
   int code=WebRequest("GET",url,"","",3000,data,0,result,headers);

   if(code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY ERROR HTTP=",code," err=",GetLastError()," URL=",url);
      }
      return;
   }

   string cmd=CharArrayToString(result);

   if(StringLen(cmd)<5){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY OK - waiting for command");
      }
      return;
   }

   string id=field(cmd,"id");
   if(id=="" || id==lastId)return;

   string type=field(cmd,"type");
   string side=field(cmd,"side");
   if(side=="TEST") { Print("RAYYAN4 REJECT legacy TEST command; use LIMIT only"); return; }
   double vol=StringToDouble(field(cmd,"volume"));
   double entry=StringToDouble(field(cmd,"entry"));
   double sl=StringToDouble(field(cmd,"sl"));
   double tp=StringToDouble(field(cmd,"tp"));

   bool ok=false;
   string resultText="REJECT";
   string detail="";
   string sym=AllowedSymbol;
   SymbolSelect(sym,true);

   MqlTick tick;
   bool haveTick=SymbolInfoTick(sym,tick);
   int digits=(int)SymbolInfoInteger(sym,SYMBOL_DIGITS);
   double point=SymbolInfoDouble(sym,SYMBOL_POINT);
   double minVol=SymbolInfoDouble(sym,SYMBOL_VOLUME_MIN);
   double maxVol=SymbolInfoDouble(sym,SYMBOL_VOLUME_MAX);
   double stepVol=SymbolInfoDouble(sym,SYMBOL_VOLUME_STEP);
   long stopsLevel=SymbolInfoInteger(sym,SYMBOL_TRADE_STOPS_LEVEL);

   if(stepVol>0) vol=MathFloor(vol/stepVol+1e-8)*stepVol;
   vol=NormalizeDouble(vol,2);
   entry=NormalizeDouble(entry,digits);
   sl=NormalizeDouble(sl,digits);
   tp=NormalizeDouble(tp,digits);

   if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
      resultText="NOT_DEMO";
   }else if(_Symbol!=AllowedSymbol){
      resultText="SYMBOL_MISMATCH";
   }else if(!haveTick){
      resultText="NO_TICK";
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
   }else if(side=="BUY_LIMIT" && !(sl<entry && tp>entry)){
      resultText="BAD_STOPS";
      detail="BUY requires SL<Entry<TP";
   }else if(side=="SELL_LIMIT" && !(tp<entry && sl>entry)){
      resultText="BAD_STOPS";
      detail="SELL requires TP<Entry<SL";
   }else if(point<=0){
      resultText="BAD_SYMBOL";
   }else if(stopsLevel>0 && MathAbs(entry-sl)/point<stopsLevel){
      resultText="STOP_DISTANCE";
      detail=StringFormat("entry-sl=%.0f min=%d points",MathAbs(entry-sl)/point,stopsLevel);
   }else{
      trade.SetAsyncMode(false);
      trade.SetTypeFillingBySymbol(sym);
      if(side=="BUY_LIMIT") ok=trade.BuyLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
      else if(side=="SELL_LIMIT") ok=trade.SellLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
      else resultText="BAD_SIDE";

      uint rc=trade.ResultRetcode();
      resultText=ok ? "EXECUTED" : "FAILED";
      detail=trade.ResultRetcodeDescription();
      Print("RAYYAN4 TRADE CHECK side=",side," vol=",DoubleToString(vol,2),
            " entry=",DoubleToString(entry,digits)," bid=",DoubleToString(tick.bid,digits),
            " ask=",DoubleToString(tick.ask,digits)," sl=",DoubleToString(sl,digits),
            " tp=",DoubleToString(tp,digits)," retcode=",rc," desc=",detail);
   }

   lastId=id;
   Print("RAYYAN4 id=",id," side=",side," result=",resultText,
         " detail=",detail," retcode=",trade.ResultRetcode());

   string ack=GatewayUrl+"/ack?id="+urlEncode(id)+"&result="+urlEncode(resultText);
   char ad[],ar[]; string ah;
   WebRequest("GET",ack,"","",3000,ad,0,ar,ah);
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