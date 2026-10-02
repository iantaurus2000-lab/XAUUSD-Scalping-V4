#property strict
#property version "5.20"

#include <Trade/Trade.mqh>

input string BridgeUrl = "https://YOUR-BRIDGE.example.com/mt5/next";
input string ApiToken = "CHANGE_ME";
input int PollSeconds = 2;
input ulong Magic = 5200001;

CTrade trade;
datetime lastPoll=0;

string HttpGet(string url){
   char result[];
   char data[];
   string headers="Authorization: Bearer "+ApiToken+"\r\n";
   string response_headers="";
   ResetLastError();
   int code=WebRequest("GET",url,headers,5000,data,0,result,response_headers);
   if(code==-1){
      Print("Bridge WebRequest failed: ",GetLastError());
      return "";
   }
   if(code<200 || code>=300){
      Print("Bridge HTTP status: ",code);
      return "";
   }
   return CharArrayToString(result);
}

string JsonString(string json,string key){
   string needle="\"" + key + "\"";
   int p=StringFind(json,needle);
   if(p<0) return "";
   p=StringFind(json,":",p);
   if(p<0) return "";
   p++;
   while(p<StringLen(json) && (StringGetCharacter(json,p)==' ' || StringGetCharacter(json,p)=='\"')) p++;
   int e=p;
   while(e<StringLen(json)){
      ushort ch=StringGetCharacter(json,e);
      if(ch=='\"' || ch==',' || ch=='}') break;
      e++;
   }
   return StringTrimLeft(StringTrimRight(StringSubstr(json,p,e-p)));
}

double JsonNumber(string json,string key){
   string v=JsonString(json,key);
   return StringToDouble(v);
}

bool PlaceLimit(string side,string symbol,double volume,double entry,double sl,double tp,string comment){
   trade.SetExpertMagicNumber(Magic);
   trade.SetAsyncMode(false);
   if(side=="buy")
      return trade.BuyLimit(volume,entry,symbol,sl,tp,ORDER_TIME_GTC,0,comment);
   if(side=="sell")
      return trade.SellLimit(volume,entry,symbol,sl,tp,ORDER_TIME_GTC,0,comment);
   return false;
}

void ProcessCommand(string json){
   string id=JsonString(json,"id");
   string side=JsonString(json,"side");
   string symbol=JsonString(json,"symbol");
   string comment=JsonString(json,"comment");
   if(symbol=="") symbol="XAUUSD";
   if(comment=="") comment="XAUUSD-V5.2-BRIDGE";

   double volume=JsonNumber(json,"volume");
   double entry=JsonNumber(json,"entry");
   double sl=JsonNumber(json,"sl");
   double tp=JsonNumber(json,"tp");

   if((side!="buy" && side!="sell") || volume<=0 || entry<=0 || sl<=0 || tp<=0){
      Print("Bridge command rejected: invalid payload");
      return;
   }

   if((side=="buy" && !(sl<entry && tp>entry)) ||
      (side=="sell" && !(sl>entry && tp<entry))){
      Print("Bridge command rejected: invalid SL/TP geometry");
      return;
   }

   bool ok=PlaceLimit(side,symbol,volume,entry,sl,tp,comment);
   if(ok)
      Print("Bridge LIMIT accepted id=",id," side=",side," symbol=",symbol," entry=",DoubleToString(entry,_Digits));
   else
      Print("Bridge LIMIT failed id=",id," retcode=",trade.ResultRetcode()," ",trade.ResultRetcodeDescription());
}

void OnTick(){
   if(TimeCurrent()-lastPoll<PollSeconds) return;
   lastPoll=TimeCurrent();

   if(StringLen(BridgeUrl)<10 || ApiToken=="CHANGE_ME") return;

   string payload=HttpGet(BridgeUrl);
   if(StringLen(payload)>0) ProcessCommand(payload);
}

int OnInit(){
   Print("XAUUSD Mobile Engine V5.2 MT5 Bridge initialized.");
   Print("Allow BridgeUrl in MT5: Tools -> Options -> Expert Advisors -> Allow WebRequest.");
   return(INIT_SUCCEEDED);
}
