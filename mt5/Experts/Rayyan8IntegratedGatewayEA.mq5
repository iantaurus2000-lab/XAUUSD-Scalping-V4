#property strict
#include <Trade/Trade.mqh>
CTrade trade;
#define RAYYAN_MAGIC 4104407

input string GatewayUrl="http://192.168.0.109:8787";
input string AllowedSymbol="XAUUSDm";
input bool DemoOnly=true;
input int PollSeconds=1;

string lastId="";
string trackedOrderId="";
string pendingAckId="";
string pendingAckResult="";
string pendingAckDetail="";
datetime lastDiag=0;
// RAYYAN8 integrated native chart controller. The indicator queue is independent.
input int SRLookbackBars=80;
input int FiboLookbackBars=60;
int hEma9=INVALID_HANDLE,hEma21=INVALID_HANDLE,hEma50=INVALID_HANDLE;
bool onEma9=true,onEma21=true,onEma50=true,onSR=true,onFibo=true;
string lastIndicatorCommandId="";
uint lastScreenshotTick=0;
datetime lastIndicatorBar=0;

int OnInit(){
   hEma9=iMA(_Symbol,_Period,9,0,MODE_EMA,PRICE_CLOSE);
   hEma21=iMA(_Symbol,_Period,21,0,MODE_EMA,PRICE_CLOSE);
   hEma50=iMA(_Symbol,_Period,50,0,MODE_EMA,PRICE_CLOSE);
   if(hEma9==INVALID_HANDLE||hEma21==INVALID_HANDLE||hEma50==INVALID_HANDLE)
      Print("RAYYAN8 INDICATOR WARNING handles=",hEma9,",",hEma21,",",hEma50);
   EventSetTimer(MathMax(1,PollSeconds));
   applyAll();
   Print("RAYYAN8 INTEGRATED START Gateway=",GatewayUrl,
         " Symbol=",AllowedSymbol," trade queue=/next indicator queue=/indicator_next");
   return(INIT_SUCCEEDED);
}
void OnDeinit(const int r){
   EventKillTimer();
   ObjectDelete(0,"RAYYAN8_SR_RESISTANCE");
   ObjectDelete(0,"RAYYAN8_SR_SUPPORT");
   ObjectDelete(0,"RAYYAN8_FIBO");
   if(hEma9!=INVALID_HANDLE)IndicatorRelease(hEma9);
   if(hEma21!=INVALID_HANDLE)IndicatorRelease(hEma21);
   if(hEma50!=INVALID_HANDLE)IndicatorRelease(hEma50);
}
void OnTick(){}

void OnTimer(){
   processIndicatorQueue();
   uploadChartSnapshot();
   int code=-1;
   string cmd="",transportDetail="";
   ResetLastError();
   bool received=gatewayGet("/next",code,cmd,transportDetail);

   if(!received || code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 GATEWAY HTTP ERROR transport=",received,
               " code=",code," err=",GetLastError()," detail=",transportDetail,
               " gateway=",GatewayUrl);
      }
      return;
   }

   publishOrderState();
   publishMarketQuotes();

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

   // Isolated additive path: cancel only the exact still-pending Rayyan LIMIT
   // identified by both ticket and its unique R4-<original-id> order comment.
   // No new order is sent by this command.
   if(type=="CANCEL"){
      string targetId=field(cmd,"targetId");
      ulong cancelTicket=(ulong)StringToInteger(field(cmd,"ticket"));
      string expectedComment="R4-"+targetId;
      string cancelResult="CANCEL_FAILED";
      string cancelDetail="Pending order not found or identity mismatch; no replacement permitted";
      trackedOrderId=targetId;
      if(DemoOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE)!=ACCOUNT_TRADE_MODE_DEMO){
         cancelResult="NOT_DEMO";
         cancelDetail="DemoOnly guard blocked order cancellation outside demo account";
      }else if(targetId!="" && cancelTicket>0 && OrderSelect(cancelTicket)){
         string orderComment=OrderGetString(ORDER_COMMENT);
         ENUM_ORDER_TYPE orderType=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
         long orderMagic=OrderGetInteger(ORDER_MAGIC);
         bool correctSide=(orderType==ORDER_TYPE_BUY_LIMIT || orderType==ORDER_TYPE_SELL_LIMIT)
            && orderMagic==RAYYAN_MAGIC;
         if(orderComment==expectedComment && correctSide){
            ResetLastError();
            bool deleted=trade.OrderDelete(cancelTicket);
            uint cancelRc=trade.ResultRetcode();
            if(deleted && cancelRc==TRADE_RETCODE_DONE){
               cancelResult="CANCELLED";
               cancelDetail="Exact pending LIMIT deleted ticket="+(string)cancelTicket;
               Print("RAYYAN4 SMART CANCEL OK id=",targetId," ticket=",cancelTicket);
            }else{
               cancelDetail="OrderDelete failed retcode="+IntegerToString((int)cancelRc)+
                  " "+trade.ResultRetcodeDescription()+" lastError="+IntegerToString(GetLastError());
            }
         }else{
            cancelDetail="Order identity rejected comment="+orderComment+
               " type="+IntegerToString((int)orderType);
         }
      }
      pendingAckId=id; pendingAckResult=cancelResult; pendingAckDetail=cancelDetail;
      if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
         lastId=pendingAckId;
         pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
      }
      Print("RAYYAN4 id=",id," type=CANCEL target=",targetId,
            " result=",cancelResult," detail=",cancelDetail);
      return;
   }

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

   // APK chart is sourced from Biquote while orders are validated against the
   // broker quote in MT5. Map the signal levels to the MT5 quote reference
   // captured when the command was queued, preserving the signal's risk distances.
   double refBid=StringToDouble(field(cmd,"refBid"));
   double refAsk=StringToDouble(field(cmd,"refAsk"));
   if(haveTick && refBid>0 && refAsk>=refBid){
      double shift=0.0;
      if(side=="BUY_LIMIT") shift=tick.ask-refAsk;
      else if(side=="SELL_LIMIT") shift=tick.bid-refBid;
      if(side=="BUY_LIMIT" || side=="SELL_LIMIT"){
         entry+=shift; sl+=shift; tp+=shift;
         Print("RAYYAN4 PRICE MAP side=",side,
               " refBid=",DoubleToString(refBid, digits),
               " refAsk=",DoubleToString(refAsk, digits),
               " brokerBid=",DoubleToString(tick.bid, digits),
               " brokerAsk=",DoubleToString(tick.ask, digits),
               " shift=",DoubleToString(shift, digits));
      }
   }

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
         // Snapshot the connection immediately before the request. This is a point-in-time
         // indicator only; the trade server can disconnect between this check and OrderSend.
         Print("RAYYAN4 SEND PRECHECK time=",TimeToString(TimeCurrent(),TIME_DATE|TIME_SECONDS),
               " connected=",(bool)TerminalInfoInteger(TERMINAL_CONNECTED),
               " terminalTrade=",(bool)TerminalInfoInteger(TERMINAL_TRADE_ALLOWED),
               " eaTrade=",(bool)MQLInfoInteger(MQL_TRADE_ALLOWED),
               " accountTrade=",(bool)AccountInfoInteger(ACCOUNT_TRADE_ALLOWED),
               " accountExpert=",(bool)AccountInfoInteger(ACCOUNT_TRADE_EXPERT),
               " server=",brokerServer," login=",accountLogin,
               " symbol=",sym," side=",side);

         trade.SetAsyncMode(false);
         trade.SetExpertMagicNumber(RAYYAN_MAGIC);
         trade.SetTypeFillingBySymbol(sym);
         ResetLastError();
         if(side=="BUY_LIMIT") ok=trade.BuyLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"R4-"+id);
         else if(side=="SELL_LIMIT") ok=trade.SellLimit(vol,entry,sym,sl,tp,ORDER_TIME_GTC,0,"R4-"+id);
         else { resultText="BAD_SIDE"; detail=side; }

         uint rc=trade.ResultRetcode();
         int sendLastError=GetLastError();
         bool connectedAfter=(bool)TerminalInfoInteger(TERMINAL_CONNECTED);
         bool brokerAccepted=(rc==TRADE_RETCODE_PLACED || rc==TRADE_RETCODE_DONE);
         string rcDescription=trade.ResultRetcodeDescription();
         if(side=="BUY_LIMIT" || side=="SELL_LIMIT"){
            if(ok && brokerAccepted){
               resultText="EXECUTED";
            }else if(rc==TRADE_RETCODE_CONNECTION){
               resultText="NO_BROKER_CONNECTION";
               detail="MT5 returned TRADE_RETCODE_CONNECTION(10031); terminalConnectedBefore="+
                      IntegerToString((int)terminalConnected)+" connectedAfter="+
                      IntegerToString((int)connectedAfter)+" server="+brokerServer+
                      " login="+IntegerToString((int)accountLogin)+
                      ". No automatic resubmission; verify MT5 Trade/Journal before retry. retcode=10031";
            }else{
               resultText="FAILED";
               detail=rcDescription+" retcode="+IntegerToString((int)rc)+
                      " lastError="+IntegerToString(sendLastError)+
                      " order="+(string)trade.ResultOrder();
            }
         }
         Print("RAYYAN4 TRADE CHECK time=",TimeToString(TimeCurrent(),TIME_DATE|TIME_SECONDS),
               " symbol=",sym," side=",side," vol=",DoubleToString(vol,2),
               " entry=",DoubleToString(entry,digits)," bid=",DoubleToString(tick.bid,digits),
               " ask=",DoubleToString(tick.ask,digits)," sl=",DoubleToString(sl,digits),
               " tp=",DoubleToString(tp,digits)," retcode=",rc," desc=",rcDescription,
               " ok=",ok," lastError=",sendLastError," order=",trade.ResultOrder(),
               " connectedBefore=",terminalConnected," connectedAfter=",connectedAfter,
               " server=",brokerServer);
      }
   }

   if(resultText=="EXECUTED") trackedOrderId=id;
   Print("RAYYAN4 id=",id," side=",side," result=",resultText,
         " detail=",detail," retcode=",trade.ResultRetcode());
   pendingAckId=id; pendingAckResult=resultText; pendingAckDetail=detail;
   if(sendAck(pendingAckId,pendingAckResult,pendingAckDetail)){
      lastId=pendingAckId;
      pendingAckId=""; pendingAckResult=""; pendingAckDetail="";
   }
}


// Publish a read-only snapshot of Rayyan-managed order lifecycle. This is
// separate from /next and /ack; it never changes trade state.
void publishOrderState(){
   string managedId=trackedOrderId;
   string state="NONE", ticketText="", symbol="", side="", priceText="0";

   // Positions dominate pending state: if a LIMIT has filled, AUTO ENTRY must
   // stay locked until that position is gone. This is deliberately conservative.
   for(int i=PositionsTotal()-1;i>=0;i--){
      ulong ticket=PositionGetTicket(i);
      if(ticket==0) continue;
      string comment=PositionGetString(POSITION_COMMENT);
      long positionMagic=PositionGetInteger(POSITION_MAGIC);
      bool tagged=StringFind(comment,"R4-")==0;
      if(!tagged && positionMagic!=RAYYAN_MAGIC) continue;
      string oid=tagged ? StringSubstr(comment,3)
                        : (managedId!="" ? managedId : "UNKNOWN_POSITION");
      if(managedId!="" && oid!=managedId) continue;
      managedId=oid; trackedOrderId=oid;
      state="POSITION"; ticketText=(string)ticket;
      symbol=PositionGetString(POSITION_SYMBOL);
      ENUM_POSITION_TYPE pt=(ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      side=(pt==POSITION_TYPE_BUY ? "BUY" : "SELL");
      priceText=DoubleToString(PositionGetDouble(POSITION_PRICE_OPEN),
                               (int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
      break;
   }

   if(state!="POSITION"){
      for(int i=OrdersTotal()-1;i>=0;i--){
         ulong ticket=OrderGetTicket(i);
         if(ticket==0) continue;
         string comment=OrderGetString(ORDER_COMMENT);
         long orderMagic=OrderGetInteger(ORDER_MAGIC);
         bool tagged=StringFind(comment,"R4-")==0;
         if(!tagged && orderMagic!=RAYYAN_MAGIC) continue;
         string oid=tagged ? StringSubstr(comment,3)
                           : (managedId!="" ? managedId : "UNKNOWN_PENDING");
         if(managedId!="" && oid!=managedId) continue;
         ENUM_ORDER_TYPE ot=(ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE);
         if(ot!=ORDER_TYPE_BUY_LIMIT && ot!=ORDER_TYPE_SELL_LIMIT) continue;
         managedId=oid; trackedOrderId=oid;
         state="PENDING"; ticketText=(string)ticket;
         symbol=OrderGetString(ORDER_SYMBOL);
         side=(ot==ORDER_TYPE_BUY_LIMIT ? "BUY_LIMIT" : "SELL_LIMIT");
         priceText=DoubleToString(OrderGetDouble(ORDER_PRICE_OPEN),
                                  (int)SymbolInfoInteger(symbol,SYMBOL_DIGITS));
         break;
      }
   }

   if(managedId=="") managedId="NONE";
   string path="/state?id="+urlEncode(managedId)+
      "&state="+urlEncode(state)+
      "&ticket="+urlEncode(ticketText)+
      "&symbol="+urlEncode(symbol)+
      "&side="+urlEncode(side)+
      "&price="+urlEncode(priceText);
   int code=-1;
   string body="",diagnostic="";
   if(!gatewayGet(path,code,body,diagnostic) || code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN4 STATE HTTP ERROR code=",code,
               " err=",GetLastError()," detail=",diagnostic);
      }
   }
}

// Publish broker quotes for all supported symbols. If a market is closed and
// MqlTick.time_msc stops advancing, the APK locks new entries despite /next heartbeat.
void publishMarketQuotes(){
   string symbols[3]={"XAUUSDm","BTCUSDm","ETHUSDm"};
   for(int i=0;i<ArraySize(symbols);i++){
      string sym=symbols[i];
      if(!SymbolSelect(sym,true)) continue;
      MqlTick tick;
      if(!SymbolInfoTick(sym,tick)||tick.bid<=0||tick.ask<tick.bid||tick.time_msc<=0) continue;
      int digits=(int)SymbolInfoInteger(sym,SYMBOL_DIGITS);
      double point=SymbolInfoDouble(sym,SYMBOL_POINT);
      if(point<=0) continue;
      string path="/quote?symbol="+urlEncode(sym)+
         "&bid="+urlEncode(DoubleToString(tick.bid,digits))+
         "&ask="+urlEncode(DoubleToString(tick.ask,digits))+
         "&point="+urlEncode(DoubleToString(point,10))+
         "&digits="+IntegerToString(digits)+
         "&tickms="+(string)tick.time_msc;
      int code=-1;
      string body="",diagnostic="";
      if(!gatewayGet(path,code,body,diagnostic)||code!=200){
         if(TimeCurrent()-lastDiag>=10){
            lastDiag=TimeCurrent();
            Print("RAYYAN4 QUOTE HTTP ERROR symbol=",sym," code=",code,
                  " err=",GetLastError()," detail=",diagnostic);
         }
      }
   }
}

// Local LAN bridge uses WebRequest (not MQL5 TCP sockets).
// Add the exact GatewayUrl base to MT5 Tools > Options > Expert Advisors > Allow WebRequest.
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
   string responseHeaders="";
   char requestData[];
   char responseData[];
   // Use WebRequest's cookie/referer overload with an explicit zero-length GET body.
   // This is more compatible with MetaTrader builds that reject an empty array in
   // the headers overload with internal error 1001 / runtime error 4006.
   ResetLastError();
   int status=WebRequest("GET",url,"","",2500,requestData,0,responseData,responseHeaders);
   int err=GetLastError();
   if(status==-1 || status==1001){
      diagnostic="WebRequest has no HTTP connection status="+IntegerToString(status)+
                 " err="+IntegerToString(err)+"; allow URL "+base+
                 " in MT5 Expert Advisors options and check Winlator LAN reachability";
      return false;
   }
   httpCode=status;
   body=CharArrayToString(responseData,0,ArraySize(responseData),CP_UTF8);
   return (status>=100 && status<=599);
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
      Print("RAYYAN4 ACK HTTP RETRY id=",id," transport=",received," code=",code,
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


// Indicator synchronization never reads or consumes the trade /next queue.
bool indicatorGet(string path,string &out){
   int code=-1;
   string body="",diagnostic="";
   bool ok=gatewayGet(path,code,body,diagnostic);
   out=body;
   if(!ok||code!=200){
      if(TimeCurrent()-lastDiag>=10){
         lastDiag=TimeCurrent();
         Print("RAYYAN8 INDICATOR HTTP ERROR code=",code," detail=",diagnostic," path=",path);
      }
      return false;
   }
   return true;
}
void processIndicatorQueue(){
   string cmd="";
   if(indicatorGet("/indicator_next",cmd)&&StringLen(cmd)>5){
      string id=field(cmd,"id");
      if(id!=""&&id!=lastIndicatorCommandId){
         string targetSymbol=field(cmd,"symbol");
         ENUM_TIMEFRAMES targetPeriod=parsePeriod(field(cmd,"tf"));
         if(targetSymbol!=""&&targetPeriod!=PERIOD_CURRENT&&
            (ChartSymbol(0)!=targetSymbol||ChartPeriod(0)!=targetPeriod)){
            if(SymbolSelect(targetSymbol,true)&&ChartSetSymbolPeriod(0,targetSymbol,targetPeriod)){
               Print("RAYYAN8 chart sync requested ",targetSymbol," ",field(cmd,"tf"));
               lastIndicatorCommandId=id;
               uploadChartSnapshot();
               string ackSync="";
               indicatorGet("/indicator_ack?id="+id,ackSync);
               return;
            }
            Print("RAYYAN8 chart sync failed target=",targetSymbol,
                  " tf=",field(cmd,"tf")," err=",GetLastError());
         }
         onEma9=(field(cmd,"ema9")=="1");
         onEma21=(field(cmd,"ema21")=="1");
         onEma50=(field(cmd,"ema50")=="1");
         onSR=(field(cmd,"sr")=="1");
         onFibo=(field(cmd,"fibo")=="1");
         applyAll();
         lastIndicatorCommandId=id;
         string ack="";
         indicatorGet("/indicator_ack?id="+id,ack);
         Print("RAYYAN8 indicators ACK id=",id," EMA9=",onEma9,
               " EMA21=",onEma21," EMA50=",onEma50," SR=",onSR," FIBO=",onFibo);
      }
   }
   datetime bar=iTime(_Symbol,_Period,0);
   if(bar!=0&&bar!=lastIndicatorBar){lastIndicatorBar=bar;applyAll();}
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
   setEma(hEma9,9,onEma9);
   setEma(hEma21,21,onEma21);
   setEma(hEma50,50,onEma50);
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
