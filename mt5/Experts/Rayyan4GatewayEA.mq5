#property strict
#include <Trade/Trade.mqh>
CTrade trade;
input string GatewayUrl="http://192.168.1.100:8787";
input string AllowedSymbol="XAUUSD";
input bool DemoOnly=true;
input int PollSeconds=1;
string lastId="";
int OnInit(){EventSetTimer(PollSeconds); return(INIT_SUCCEEDED);}
void OnDeinit(const int r){EventKillTimer();}
void OnTick(){}
void OnTimer(){
 string url=GatewayUrl+"/next";
 char data[],result[]; string headers;
 ResetLastError(); int code=WebRequest("GET",url,"",3000,data,0,result,headers);
 if(code!=200)return;
 string cmd=CharArrayToString(result);
 if(StringLen(cmd)<5 || cmd==lastId)return;
 lastId=cmd;
 string type=field(cmd,"type"), side=field(cmd,"side");
 double vol=StringToDouble(field(cmd,"volume")),entry=StringToDouble(field(cmd,"entry"));
 double sl=StringToDouble(field(cmd,"sl")),tp=StringToDouble(field(cmd,"tp"));
 bool ok=false;
 if(!DemoOnly || AccountInfoInteger(ACCOUNT_TRADE_MODE)==ACCOUNT_TRADE_MODE_DEMO){
  if(_Symbol==AllowedSymbol){
   trade.SetTypeFillingBySymbol(_Symbol);
   if(side=="BUY_LIMIT") ok=trade.BuyLimit(vol,entry,_Symbol,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
   if(side=="SELL_LIMIT") ok=trade.SellLimit(vol,entry,_Symbol,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
  }
 }
 Print("RAYYAN4 command=",cmd," result=",ok," retcode=",trade.ResultRetcode());
}
string field(string s,string key){
 string p=key+"="; int a=StringFind(s,p); if(a<0)return "";
 a+=StringLen(p); int b=StringFind(s,";",a); if(b<0)b=StringLen(s); return StringSubstr(s,a,b-a);
}
