#property strict
#include <Trade/Trade.mqh>
CTrade trade;

input string GatewayUrl="http://192.168.0.109:8787";
input string AllowedSymbol="XAUUSDm";
input bool DemoOnly=true;
input int PollSeconds=1;

string lastId="";
string pendingAckId="";
string pendingAckResult="";
string pendingAckDetail="";
datetime lastDiag=0;

int OnInit(){
   EventSetTimer(MathMax(1,PollSeconds));
   Print("RAYYAN4 START Gateway=",GatewayUrl," Symbol=",AllowedSymbol);
   return(INIT_SUCCEEDED);
}
void OnDeinit(const int r){ EventKillTimer(); }
void OnTick(){}

void OnTimer(){
   int code=-1;
   string cmd="",transportDetail="";
   ResetLastError();
   bool received=gatewayGet("/next",code,cmd,transportDetail);

   if(!received || code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY SOCKET ERROR transport=",received,
               " code=",code," err=",GetLastError()," detail=",transportDetail,
               " gateway=",GatewayUrl);
      }
      return;
   }

   if(StringLen(cmd)<5){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY OK - waiting for command");
      }
      return;
   }

   string id=field(cmd,"id");
   if(id=="")return;
   // A trade is sent once; until ACK is confirmed, retry ACK only, never place it twice.
   if(pendingAckId!=""){
      if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
         lastId=pendingAckId;
         pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
      }else return;
      if(id==lastId)return;
   }
   if(id==lastId)return;

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
   string requestedSym=field(cmd,"symbol");
   string sym=(requestedSym=="" ? AllowedSymbol : requestedSym);
   bool supported=(sym=="XAUUSDm" || sym=="BTCUSDm" || sym=="ETHUSDm");
   if(supported) SymbolSelect(sym,true);

   MqlTick tick;
   bool haveTick=supported && SymbolInfoTick(sym,tick);
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
   }else if(!supported){
      resultText="UNSUPPORTED_SYMBOL"; detail=sym;
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
      bool terminalConnected=(bool)TerminalInfoInteger(TERMINAL_CONNECTED);
      bool terminalTradeAllowed=(bool)TerminalInfoInteger(TERMINAL_TRADE_ALLOWED);
      bool eaTradeAllowed=(bool)MQLInfoInteger(MQL_TRADE_ALLOWED);
      bool accountTradeAllowed=(bool)AccountInfoInteger(ACCOUNT_TRADE_ALLOWED);
      bool accountExpertAllowed=(bool)AccountInfoInteger(ACCOUNT_TRADE_EXPERT);
      string brokerServer=AccountInfoString(ACCOUNT_SERVER);
      long accountLogin=AccountInfoInteger(ACCOUNT_LOGIN);
      Print("RAYYAN4 BROKER CHECK connected=",terminalConnected,
            " terminalTrade=",terminalTradeAllowed," eaTrade=",eaTradeAllowed,
            " accountTrade=",accountTradeAllowed," accountExpert=",accountExpertAllowed,
            " server=",brokerServer," login=",accountLogin);

      if(!terminalConnected){
         resultText="NO_BROKER_CONNECTION";
         detail="TERMINAL_CONNECTED=false server="+brokerServer+" login="+IntegerToString((int)accountLogin);
      }else if(!terminalTradeAllowed || !eaTradeAllowed || !accountTradeAllowed || !accountExpertAllowed){
         resultText="TRADE_NOT_ALLOWED";
         detail=StringFormat("terminal=%d ea=%d account=%d expert=%d server=%s",
            (int)terminalTradeAllowed,(int)eaTradeAllowed,(int)accountTradeAllowed,
            (int)accountExpertAllowed,brokerServer);
      }else{
         trade.SetAsyncMode(false);
         trade.SetTypeFillingBySymbol(sym);
         if(side=="BUY_LIMIT") ok=trade.BuyLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
         else if(side=="SELL_LIMIT") ok=trade.SellLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"RAYYAN4");
         else { resultText="BAD_SIDE"; detail=side; }

         uint rc=trade.ResultRetcode();
         if(side=="BUY_LIMIT" || side=="SELL_LIMIT"){
            bool brokerAccepted=(rc==TRADE_RETCODE_PLACED || rc==TRADE_RETCODE_DONE);
            resultText=(ok && brokerAccepted) ? "EXECUTED" : "FAILED";
            detail=trade.ResultRetcodeDescription()+" retcode="+IntegerToString((int)rc)+
                   " order="+(string)trade.ResultOrder();
         }
         Print("RAYYAN4 TRADE CHECK symbol=",sym," side=",side," vol=",DoubleToString(vol,2),
               " entry=",DoubleToString(entry,digits)," bid=",DoubleToString(tick.bid,digits),
               " ask=",DoubleToString(tick.ask,digits)," sl=",DoubleToString(sl,digits),
               " tp=",DoubleToString(tp,digits)," retcode=",rc," desc=",detail,
               " connected=",terminalConnected," server=",brokerServer);
      }
   }

   Print("RAYYAN4 id=",id," side=",side," result=",resultText,
         " detail=",detail," retcode=",trade.ResultRetcode());
   pendingAckId=id; pendingAckResult=resultText; pendingAckDetail=detail;
   if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
      lastId=pendingAckId;
      pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
   }
}


// Use MQL5 TCP sockets instead of WebRequest for the local LAN bridge.
// Recent MT5 builds have reports of WebRequest returning 1001 / error 5203.
bool gatewayGet(string path,int &httpCode,string &body,string &diagnostic){
   httpCode=-1; body=""; diagnostic="";
   string base=GatewayUrl;
   while(StringLen(base)>0 && StringSubstr(base,StringLen(base)-1,1)=="/")
      base=StringSubstr(base,0,StringLen(base)-1);
   if(StringFind(base,"http://")!=0 && StringFind(base,"https://")!=0){
      diagnostic="GatewayUrl must start with http:// or https://";
      return false;
   }

   // Use WebRequest rather than SocketConnect: MT5 may reject socket calls
   // with error 4014. The base URL must be added to Tools > Options >
   // Expert Advisors > Allow WebRequest for listed URL.
   string url=base+path;
   string requestHeaders="User-Agent: Rayyan4GatewayEA\r\nConnection: close\r\n";
   string responseHeaders="";
   char requestData[];
   char responseData[];
   ArrayResize(requestData,0);
   ResetLastError();
   int status=WebRequest("GET",url,requestHeaders,2500,requestData,responseData,responseHeaders);
   if(status==-1){
      int err=GetLastError();
      diagnostic="WebRequest failed err="+IntegerToString(err)+
                 "; allow URL "+base+" in MT5 Expert Advisors options";
      return false;
   }
   httpCode=status;
   body=CharArrayToString(responseData,0,ArraySize(responseData),CP_UTF8);
   return true;
}

int headerContentLength(string headers){
   string lines[];
   int count=StringSplit(headers,'\n',lines);
   for(int i=0;i<count;i++){
      string line=lines[i];
      StringReplace(line,"\r","");
      if(StringFind(line,"Content-Length:")==0)
         return (int)StringToInteger(StringSubstr(line,StringLen("Content-Length:")));
   }
   return -1;
}

bool sendAck(string id,string resultText,string detail){
   string path="/ack?id="+urlEncode(id)+"&result="+urlEncode(resultText)+"&detail="+urlEncode(detail);
   int code=-1;
   string body="",transportDetail="";
   ResetLastError();
   bool received=gatewayGet(path,code,body,transportDetail);
   if(received && code==200 && (StringFind(body,"ACK OK")>=0 || StringFind(body,"ACK STALE")>=0)) return true;
   if(TimeCurrent()-lastDiag>=10){
      lastDiag=TimeCurrent();
      Print("RAYYAN4 ACK SOCKET RETRY id=",id," transport=",received," code=",code,
            " err=",GetLastError()," detail=",transportDetail," response=",body);
   }
   return false;
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