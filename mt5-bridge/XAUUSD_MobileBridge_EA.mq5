#property strict
#property version "5.20"

#include <Trade/Trade.mqh>

input string BridgeUrl = "https://YOUR-BRIDGE.example.com/mt5/next";
input string ApiToken  = "CHANGE_ME";
input int    PollSeconds = 2;
input ulong  Magic = 5200001;

CTrade trade;
datetime lastPoll=0;

string HttpGet(string url){
   char result[];
   char data[];
   string headers="";
   string response_headers="";
   string h="Authorization: Bearer "+ApiToken+"\r\n";
   ResetLastError();
   int code=WebRequest("GET",url,h,"",5000,data,0,result,response_headers);
   if(code==-1){
      Print("Bridge WebRequest failed: ",GetLastError());
      return "";
   }
   return CharArrayToString(result);
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
   string side="",symbol="XAUUSD",comment="XAUUSD-V5.2-BRIDGE";
   double volume=0,entry=0,sl=0,tp=0;

   // Minimal parser for a trusted bridge response.
   // Recommended bridge response:
   // {"id":"123","side":"buy","symbol":"XAUUSD","volume":0.01,"entry":4000.0,"sl":3995.0,"tp":4010.0}
   int p=StringFind(json,"\"side\"");
   if(p>=0){ int q=StringFind(json,":",p); side=StringSubstr(json,q+1); side=StringTrimLeft(StringTrimRight(side)); }
   // Use a production JSON parser/validated bridge in your deployment.
   if(side!="buy" && side!="sell") return;

   Alert("MT5 bridge command received. Configure a validated JSON parser before live use.");
}

void OnTick(){
   if(TimeCurrent()-lastPoll<PollSeconds) return;
   lastPoll=TimeCurrent();
   if(StringLen(BridgeUrl)<10 || ApiToken=="CHANGE_ME") return;

   string payload=HttpGet(BridgeUrl);
   if(StringLen(payload)>0) ProcessCommand(payload);
}

int OnInit(){
   Print("XAUUSD Mobile Engine MT5 Bridge EA initialized.");
   Print("Add the bridge URL to Tools -> Options -> Expert Advisors -> Allow WebRequest.");
   return(INIT_SUCCEEDED);
}
