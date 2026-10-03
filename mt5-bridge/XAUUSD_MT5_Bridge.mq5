#property strict
#property version "5.2.6"
#property description "XAUUSD Mobile Trading Engine MT5 Bridge"
#include <Trade/Trade.mqh>
input string InpBotToken="";
input string InpChatId="";
input long InpMagic=5206001;
input int InpPollSeconds=1;
input bool InpAllowBuyLimit=true;
input bool InpAllowSellLimit=true;
input bool InpSendAck=true;
CTrade trade;
long g_offset=0;
string g_last_key="";
string ApiBase(){return "https://api.telegram.org/bot"+InpBotToken;}
string UrlEncode(string s){
 uchar a[]; StringToCharArray(s,a,0,WHOLE_ARRAY,CP_UTF8); string out="";
 for(int i=0;i<ArraySize(a)-1;i++){uchar c=a[i];if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.')out+=CharToString(c);else if(c==' ')out+="+";else out+=StringFormat("%%%02X",c);} return out;
}
bool WebGet(string url,string &out){
 char data[],result[]; string headers=""; ResetLastError();
 int code=WebRequest("GET",url,"",NULL,8000,data,0,result,headers);
 if(code<200||code>=300){Print("MT5BRIDGE WebRequest failed code=",code," err=",GetLastError());return false;}
 out=CharArrayToString(result,0,-1,CP_UTF8); return true;
}
void SendAck(string text){
 if(!InpSendAck||InpBotToken==""||InpChatId=="")return;
 string body; WebGet(ApiBase()+"/sendMessage?chat_id="+InpChatId+"&text="+UrlEncode(text),body);
}
bool HasOurOrder(string key){
 for(int i=OrdersTotal()-1;i>=0;i--){ulong ticket=OrderGetTicket(i);if(ticket==0)continue;if((long)OrderGetInteger(ORDER_MAGIC)!=InpMagic)continue;if(StringFind(OrderGetString(ORDER_COMMENT),key)>=0)return true;} return false;
}
void ProcessCommand(string cmd){
 StringTrimLeft(cmd);StringTrimRight(cmd);string p[];int n=StringSplit(cmd,'|',p);
 if(n<9||p[0]!="[MT5BRIDGE]"||p[1]!="V1")return;
 string side=p[2],symbol=p[3],key=p[8];if(key==""||key==g_last_key||HasOurOrder(key))return;
 double volume=StringToDouble(p[4]),entry=StringToDouble(p[5]),sl=StringToDouble(p[6]),tp=StringToDouble(p[7]);
 if(volume<=0||entry<=0)return;
 if(!SymbolSelect(symbol,true)){SendAck("MT5BRIDGE SYMBOL ERROR: "+symbol);return;}
 MqlTick tick;if(!SymbolInfoTick(symbol,tick)){SendAck("MT5BRIDGE NO TICK: "+symbol);return;}
 int digits=(int)SymbolInfoInteger(symbol,SYMBOL_DIGITS);trade.SetExpertMagicNumber(InpMagic);trade.SetTypeFillingBySymbol(symbol);
 bool ok=false;
 if(side=="BUY_LIMIT"&&InpAllowBuyLimit){
  if(entry>=tick.ask||sl>=entry||tp<=entry){SendAck("MT5BRIDGE INVALID BUY LIMIT "+symbol);return;}
  ok=trade.BuyLimit(volume,entry,symbol,sl,tp,ORDER_TIME_GTC,0,"XAUUSD-V5.2|"+key);
 }else if(side=="SELL_LIMIT"&&InpAllowSellLimit){
  if(entry<=tick.bid||sl<=entry||tp>=entry){SendAck("MT5BRIDGE INVALID SELL LIMIT "+symbol);return;}
  ok=trade.SellLimit(volume,entry,symbol,sl,tp,ORDER_TIME_GTC,0,"XAUUSD-V5.2|"+key);
 }else return;
 uint rc=trade.ResultRetcode();ulong ticket=trade.ResultOrder();
 if(ok&&ticket>0){g_last_key=key;SendAck("MT5 AUTO ENTRY OK\n"+side+" "+symbol+"\nEntry "+DoubleToString(entry,digits)+"\nSL "+DoubleToString(sl,digits)+"\nTP "+DoubleToString(tp,digits)+"\nLot "+DoubleToString(volume,2)+"\nTicket "+(string)ticket);}
 else SendAck("MT5 ORDER REJECT\n"+side+" "+symbol+"\nRetcode "+(string)rc+"\n"+trade.ResultRetcodeDescription());
}
void PollTelegram(){
 if(InpBotToken==""||InpChatId=="")return;
 string url=ApiBase()+"/getUpdates?timeout=1&allowed_updates=%5B%22message%22%5D";if(g_offset>0)url+="&offset="+(string)g_offset;
 string body;if(!WebGet(url,body))return;int pos=0;
 while(true){
  int up=StringFind(body,"\"update_id\":",pos);if(up<0)break;int next=StringFind(body,"\"update_id\":",up+12);if(next<0)next=StringLen(body);
  string chunk=StringSubstr(body,up,next-up);int idp=StringFind(chunk,"\"update_id\":")+12;while(idp<StringLen(chunk)&&(StringGetCharacter(chunk,idp)==' '||StringGetCharacter(chunk,idp)=='\"'))idp++;
  int ide=idp;while(ide<StringLen(chunk)){ushort c=StringGetCharacter(chunk,ide);if(c==','||c=='}'||c=='\"')break;ide++;}long uid=StringToInteger(StringSubstr(chunk,idp,ide-idp));
  int tp=StringFind(chunk,"\"text\":\"[MT5BRIDGE]|");
  if(tp>=0){tp+=StringLen("\"text\":\"");int te=StringFind(chunk,"\"",tp);if(te>tp){string txt=StringSubstr(chunk,tp,te-tp);int cp=StringFind(chunk,"\"chat\":{\"id\":");if(cp>=0){cp+=StringLen("\"chat\":{\"id\":");int ce=cp;while(ce<StringLen(chunk)){ushort c=StringGetCharacter(chunk,ce);if(c==','||c=='}')break;ce++;}string chat=StringSubstr(chunk,cp,ce-cp);if(chat==InpChatId)ProcessCommand(txt);}}}
  if(uid>=g_offset)g_offset=uid+1;pos=next;
 }
}
int OnInit(){EventSetTimer(MathMax(1,InpPollSeconds));Print("XAUUSD MT5 Bridge started. Allow WebRequest https://api.telegram.org");return INIT_SUCCEEDED;}
void OnDeinit(const int reason){EventKillTimer();}
void OnTimer(){PollTelegram();}
