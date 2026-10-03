AUTO ENTRY APK → MT5 EXNESS (GRATIS)
====================================

MENGAPA TIDAK LANGSUNG DARI APK KE MT5 HP?
- MetaTrader 5 Mobile tidak menyediakan API untuk aplikasi lain
  menempatkan order secara otomatis.
- EA (Expert Advisor) hanya berjalan di MT5 Desktop / VPS, bukan di APK MT5 HP.

SOLUSI YANG DIPAKAI (100% gratis):
1. APK mendeteksi ENTRY READY (BUY LIMIT / SELL LIMIT)
2. Jika "Auto Entry" ON + Telegram terisi → APK kirim paket order ke Telegram
3. EA XAUUSD_AutoLimit_EA.mq5 di MT5 (PC atau VPS gratis) membaca Telegram
4. EA menaruh order LIMIT di akun Exness yang login di terminal itu

SETUP CEPAT
-----------
A. Telegram (sama di APK dan EA)
   1. @BotFather → /newbot → copy TOKEN
   2. Chat bot Anda → /start
   3. Dapatkan chat id (mis. @userinfobot)
   4. APK → Menu → Telegram → simpan TOKEN + Chat ID
   5. APK → Menu → Auto Entry ON, set lot (atau Risk balance)

B. MT5 Desktop / VPS gratis
   1. Install MT5 Exness, login akun (demo/real)
   2. Buka XAUUSD di Market Watch (nama bisa XAUUSD / XAUUSDm — sesuaikan InpSymbol)
   3. MetaEditor → buka file mt5/XAUUSD_AutoLimit_EA.mq5 → Compile
   4. Tools → Options → Expert Advisors:
      - Allow algorithmic trading
      - Allow WebRequest for listed URL:
        https://api.telegram.org
   5. Drag EA ke chart XAUUSD, isi InpBotToken & InpChatId (SAMA dengan APK)
   6. AutoTrading tombol hijau ON

C. APK HP
   1. START monitor
   2. Auto Entry ON
   3. Saat ENTRY READY → paket order ke Telegram → EA eksekusi LIMIT

FORMAT PAKET (otomatis dari APK):
#XAUUSD BUY_LIMIT
ENTRY:4153.72
SL:4149.20
TP:4157.95
LOT:0.01
SCORE:80
ID:BUY-4153.72-...

VPS GRATIS (opsional, agar EA 24 jam):
- Oracle Cloud free tier, Google Cloud trial, atau PC rumah yang nyala
- Tidak wajib bayar broker API / MetaAPI

PENTING
- Uji dulu di akun DEMO Exness
- Samakan simbol (XAUUSD vs XAUUSDm)
- Spread terlalu lebar bisa menolak LIMIT
- APK sendiri tidak bisa menekan tombol order di MT5 Mobile
