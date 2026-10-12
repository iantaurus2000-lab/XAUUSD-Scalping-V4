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
   if(StringFind(GatewayUrl,"http://")!=0){
      diagnostic="GatewayUrl must use http://host:port";
      return false;
   }
   string endpoint=StringSubstr(GatewayUrl,7);
   int slash=StringFind(endpoint,"/");
   if(slash>=0) endpoint=StringSubstr(endpoint,0,slash);
   int colon=StringFind(endpoint,":");
   string host=endpoint;
   uint port=80;
   if(colon>=0){
      host=StringSubstr(endpoint,0,colon);
      port=(uint)StringToInteger(StringSubstr(endpoint,colon+1));
   }
   if(host=="" || port==0){
      diagnostic="Invalid GatewayUrl host/port";
      return false;
   }

   ResetLastError();
   int sock=SocketCreate();
   if(sock==INVALID_HANDLE){
      diagnostic="SocketCreate failed";
      return false;
   }
   if(!SocketConnect(sock,host,port,1200)){
      int err=GetLastError();
      SocketClose(sock);
      diagnostic="SocketConnect failed err="+IntegerToString(err);
      return false;
   }

   string request="GET "+path+" HTTP/1.1\r\nHost: "+host+"\r\nUser-Agent: Rayyan4GatewayEA\r\nConnection: close\r\n\r\n";
   char outgoing[];
   int sendLen=StringToCharArray(request,outgoing,0,WHOLE_ARRAY,CP_UTF8)-1;
   if(sendLen<=0 || SocketSend(sock,outgoing,(uint)sendLen)!=sendLen){
      int err=GetLastError();
      SocketClose(sock);
      diagnostic="SocketSend failed err="+IntegerToString(err);
      return false;
   }

   string raw="";
   bool complete=false;
   uint started=GetTickCount();
   while(GetTickCount()-started<2200 && !IsStopped()){
      uint available=SocketIsReadable(sock);
      if(available==0){
         if(StringLen(raw)>0){
            int split0=StringFind(raw,"\r\n\r\n");
            if(split0>=0){
               string hdr0=StringSubstr(raw,0,split0);
               int expected0=headerContentLength(hdr0);
               string body0=StringSubstr(raw,split0+4);
               if(expected0>=0 && StringLen(body0)>=expected0){
                  complete=true;
                  break;
               }
            }
         }
         Sleep(10);
         continue;
      }
      char incoming[];
      uint wanted=(uint)MathMin(8192.0,(double)available);
      int got=SocketRead(sock,incoming,wanted,250);
      if(got<0){
         if(StringLen(raw)==0) diagnostic="SocketRead failed err="+IntegerToString(GetLastError());
         break;
      }
      if(got>0) raw+=CharArrayToString(incoming,0,got,CP_UTF8);

      int split=StringFind(raw,"\r\n\r\n");
      if(split>=0){
         string hdr=StringSubstr(raw,0,split);
         int expected=headerContentLength(hdr);
         string responseBody=StringSubstr(raw,split+4);
         if(expected>=0 && StringLen(responseBody)>=expected){
            complete=true;
            break;
         }
      }
      if(StringLen(raw)>16000){
         diagnostic="HTTP response exceeded 16KB safety limit";
         break;
      }
   }
   SocketClose(sock);

   int split=StringFind(raw,"\r\n\r\n");
   if(split<0){
      if(diagnostic=="") diagnostic="Incomplete HTTP response";
      return false;
   }
   string header=StringSubstr(raw,0,split);
   string firstLine=StringSubstr(header,0,StringFind(header,"\r\n")>=0?StringFind(header,"\r\n"):StringLen(header));
   int space=StringFind(firstLine," ");
   if(space>=0) httpCode=(int)StringToInteger(StringSubstr(firstLine,space+1,3));
   int expected=headerContentLength(header);
   body=StringSubstr(raw,split+4);
   if(expected>=0 && StringLen(body)>expected) body=StringSubstr(body,0,expected);
   if(!complete && expected>=0 && StringLen(body)<expected){
      diagnostic="Short HTTP body expected="+IntegerToString(expected)+" got="+IntegerToString(StringLen(body));
      return false;
   }
   return (httpCode>=100);
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