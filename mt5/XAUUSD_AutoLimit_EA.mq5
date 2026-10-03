//+----------------------------------------------------------------+
//| XAUUSD_AutoLimit_EA.mq5                                        |
//| Gratis — pasang di MT5 desktop/VPS (akun Exness)               |
//| Baca sinyal LIMIT dari Telegram bot (paket dari APK Scalper)   |
//|                                                                |
//| CARA PAKAI:                                                    |
//| 1. Buat bot di @BotFather → token                              |
//| 2. Chat bot /start, ambil chat_id                              |
//| 3. Isi input EA: InpBotToken + InpChatId (sama dengan APK)     |
//| 4. Tools → Options → Expert Advisors:                          |
//|    centang Allow WebRequest, tambah: https://api.telegram.org  |
//| 5. Attach EA ke chart XAUUSD (M1/M5), AutoTrading ON           |
//| 6. Di APK: isi Telegram sama, nyalakan Auto Entry + START      |
//+----------------------------------------------------------------+
#property copyright "XAUUSD Scalper Bridge"
#property version   "1.00"
#property strict

input string InpBotToken   = "";           // Bot token (sama APK)
input string InpChatId     = "";           // Chat ID (sama APK)
input string InpSymbol     = "XAUUSD";     // Simbol broker (sesuaikan: XAUUSDm dll)
input double InpDefaultLot = 0.01;         // Lot fallback
input int    InpMagic      = 20261003;     // Magic number
input int    InpSlippage   = 30;           // Slippage points
input int    InpPollSec    = 8;            // Poll Telegram detik
input int    InpMaxSpread  = 50;           // Max spread (points*10 style gold ~)
input bool   InpOnlyLimit  = true;         // Hanya LIMIT order

string   g_lastId = "";
datetime g_lastPoll = 0;

int OnInit()
{
   if(StringLen(InpBotToken) < 10 || StringLen(InpChatId) < 1)
   {
      Print("Isi InpBotToken dan InpChatId!");
      return INIT_PARAMETERS_INCORRECT;
   }
   EventSetTimer(InpPollSec);
   Print("XAUUSD AutoLimit EA aktif. Symbol=", InpSymbol);
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   EventKillTimer();
}

void OnTimer()
{
   PollTelegram();
}

void PollTelegram()
{
   string url = "https://api.telegram.org/bot" + InpBotToken + "/getUpdates?offset=-5&limit=5";
   char result[];
   char data[];
   string headers = "";
   string responseHeaders;
   int timeout = 8000;
   int res = WebRequest("GET", url, headers, timeout, data, result, responseHeaders);
   if(res == -1)
   {
      Print("WebRequest gagal. Izinkan https://api.telegram.org di Tools→Options→EA");
      return;
   }
   string json = CharArrayToString(result);
   ParseAndTrade(json);
}

void ParseAndTrade(string json)
{
   // Cari blok pesan teks terakhir yang mengandung #XAUUSD
   int pos = StringFind(json, "#XAUUSD");
   if(pos < 0) return;

   // Ambil cuplikan setelah #XAUUSD sampai akhir text kasar
   string chunk = StringSubstr(json, pos, 400);
   // Unescape JSON \n
   StringReplace(chunk, "\\n", "\n");
   StringReplace(chunk, "\"", "");

   string side = "";
   if(StringFind(chunk, "BUY_LIMIT") >= 0) side = "BUY_LIMIT";
   else if(StringFind(chunk, "SELL_LIMIT") >= 0) side = "SELL_LIMIT";
   else return;

   double entry = ExtractNum(chunk, "ENTRY:");
   double sl    = ExtractNum(chunk, "SL:");
   double tp    = ExtractNum(chunk, "TP:");
   double lot   = ExtractNum(chunk, "LOT:");
   string id    = ExtractStr(chunk, "ID:");

   if(entry <= 0 || sl <= 0) return;
   if(id != "" && id == g_lastId) return; // sudah diproses
   if(lot < 0.01) lot = InpDefaultLot;

   string sym = InpSymbol;
   if(!SymbolSelect(sym, true))
   {
      Print("Symbol tidak ada: ", sym, " — ganti InpSymbol sesuai Market Watch");
      return;
   }

   // Cegah duplikat order magic+entry mirip
   if(HasSimilarOrder(sym, entry)) 
   {
      g_lastId = id;
      return;
   }

   bool ok = PlaceLimit(sym, side, lot, entry, sl, tp);
   if(ok)
   {
      g_lastId = id;
      Print("ORDER OK ", side, " E=", entry, " SL=", sl, " TP=", tp, " L=", lot);
   }
}

double ExtractNum(string src, string key)
{
   int p = StringFind(src, key);
   if(p < 0) return 0;
   string rest = StringSubstr(src, p + StringLen(key), 24);
   // ambil angka
   string num = "";
   for(int i = 0; i < StringLen(rest); i++)
   {
      ushort ch = StringGetCharacter(rest, i);
      if((ch >= '0' && ch <= '9') || ch == '.' || ch == '-')
         num += ShortToString(ch);
      else if(StringLen(num) > 0) break;
   }
   return StringToDouble(num);
}

string ExtractStr(string src, string key)
{
   int p = StringFind(src, key);
   if(p < 0) return "";
   string rest = StringSubstr(src, p + StringLen(key), 64);
   string out = "";
   for(int i = 0; i < StringLen(rest); i++)
   {
      ushort ch = StringGetCharacter(rest, i);
      if(ch == '\n' || ch == '\r' || ch == ' ') break;
      out += ShortToString(ch);
   }
   return out;
}

bool HasSimilarOrder(string sym, double entry)
{
   for(int i = OrdersTotal() - 1; i >= 0; i--)
   {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0) continue;
      if(OrderGetInteger(ORDER_MAGIC) != InpMagic) continue;
      if(OrderGetString(ORDER_SYMBOL) != sym) continue;
      double op = OrderGetDouble(ORDER_PRICE_OPEN);
      if(MathAbs(op - entry) < 0.15) return true;
   }
   // positions juga
   for(int i = PositionsTotal() - 1; i >= 0; i--)
   {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic) continue;
      if(PositionGetString(POSITION_SYMBOL) != sym) continue;
   }
   return false;
}

bool PlaceLimit(string sym, string side, double lot, double entry, double sl, double tp)
{
   MqlTradeRequest req;
   MqlTradeResult  res;
   ZeroMemory(req);
   ZeroMemory(res);

   int digits = (int)SymbolInfoInteger(sym, SYMBOL_DIGITS);
   entry = NormalizeDouble(entry, digits);
   sl    = NormalizeDouble(sl, digits);
   tp    = NormalizeDouble(tp, digits);
   lot   = NormalizeDouble(lot, 2);

   req.action    = TRADE_ACTION_PENDING;
   req.symbol    = sym;
   req.volume    = lot;
   req.price     = entry;
   req.sl        = sl;
   req.tp        = tp;
   req.deviation = InpSlippage;
   req.magic     = InpMagic;
   req.type_time = ORDER_TIME_GTC;
   req.type_filling = ORDER_FILLING_RETURN;

   if(side == "BUY_LIMIT")
      req.type = ORDER_TYPE_BUY_LIMIT;
   else
      req.type = ORDER_TYPE_SELL_LIMIT;

   // Validasi harga vs market (LIMIT harus di sisi yang benar)
   double bid = SymbolInfoDouble(sym, SYMBOL_BID);
   double ask = SymbolInfoDouble(sym, SYMBOL_ASK);
   if(side == "BUY_LIMIT" && entry >= ask)
   {
      // terlalu tinggi untuk buy limit → geser sedikit di bawah ask
      entry = NormalizeDouble(ask - 0.5, digits);
      req.price = entry;
   }
   if(side == "SELL_LIMIT" && entry <= bid)
   {
      entry = NormalizeDouble(bid + 0.5, digits);
      req.price = entry;
   }

   bool sent = OrderSend(req, res);
   if(!sent || res.retcode != TRADE_RETCODE_DONE && res.retcode != TRADE_RETCODE_DONE_PARTIAL
      && res.retcode != TRADE_RETCODE_PLACED && res.retcode != TRADE_RETCODE_ORDER_PLACED)
   {
      Print("OrderSend gagal retcode=", res.retcode, " comment=", res.comment);
      return false;
   }
   return true;
}
