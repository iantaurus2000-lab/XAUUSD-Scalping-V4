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
   if("TEST".equals(type)){
      lastId=id;
      Print("RAYYAN4 TEST id=",id," result=TEST_OK");
      string ackTest=GatewayUrl+"/ack?id="+urlEncode(id)+"&result=TEST_OK";
      char td[],tr[]; string th;
      ResetLastError();
      int testAckCode=WebRequest("GET",ackTest,"","",3000,td,0,tr,th);
      if(testAckCode==200) Print("RAYYAN4 TEST ACK OK id=",id);
      else Print("RAYYAN4 TEST ACK ERROR HTTP=",testAckCode," err=",GetLastError());
      return;
   }

   string side=field(cmd,"side");
   double vol=StringToDouble(field(cmd,"volume"));
   double entry=StringToDouble(field(cmd,"entry"));
   double sl=StringToDouble(field(cmd,"sl"));
   double tp=StringToDouble(field(cmd,"tp"));

   bool ok=false;
   string resultText="REJECT";

   if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
      resultText="NOT_DEMO";
   }else if(_Symbol!=AllowedSymbol){
      resultText="SYMBOL_MISMATCH";
   }else if(vol<=0 || entry<=0 || sl<=0 || tp<=0){
      resultText="BAD_VALUES";
   }else{
      trade.SetTypeFillingBySymbol(_Symbol);
      if(side=="BUY_LIMIT") ok=trade.BuyLimit(vol,entry,_Symbol,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
      if(side=="SELL_LIMIT") ok=trade.SellLimit(vol,entry,_Symbol,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
      resultText=ok ? "EXECUTED" : "FAILED";
   }

   lastId=id;
   Print("RAYYAN4 id=",id," side=",side," result=",resultText," retcode=",trade.ResultRetcode());

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