#property strict
#include <Trade/Trade.mqh>
CTrade trade;
input int Port=8787;
input string AllowedSymbol="XAUUSD";
input bool DemoOnly=true;
// The EA is intentionally a small HTTP bridge. Attach it to XAUUSD in the demo terminal.
// Production hardening should add authentication and LAN allow-listing.
int OnInit(){return(INIT_SUCCEEDED);}
void OnTick(){}
string Json(bool ok,string msg){return "{\"ok\":"+(ok?"true":"false")+",\"message\":\""+msg+"\"}";}
void OnChartEvent(const int id,const long &l,const double &d,const string &s){}
bool PlaceLimit(string side,double volume,double entry,double sl,double tp){
 if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO)return false;
 if(_Symbol!=AllowedSymbol)return false;
 trade.SetTypeFillingBySymbol(_Symbol);
 if(side=="BUY_LIMIT") return trade.BuyLimit(volume,entry,_Symbol,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
 if(side=="SELL_LIMIT") return trade.SellLimit(volume,entry,_Symbol,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
 return false;
}
