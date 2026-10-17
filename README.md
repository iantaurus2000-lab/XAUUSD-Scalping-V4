# XAUUSD Mobile Trading Engine — Rayyan4

## Tujuan
Repository ini dipisah berdasarkan komponen supaya perubahan satu bagian tidak diam-diam merusak bagian lain. Semua perubahan yang menyentuh antarmuka integrasi wajib melewati pemeriksaan komponen dan build/emulator.

## Peta komponen

| Komponen | Lokasi | Tanggung jawab | Risiko perubahan |
|---|---|---|---|
| Android UI/chart | `MainActivity.java`, `CandleChartView.java`, `RayyanUiLayout.java`, `RayyanMenuLayout.java` | Tampilan, skala harga, Bid/Ask, level order, status ENTRY READY | Bisa mengubah tampilan dan keputusan tombol; uji unit + emulator |
| Strategi | `SignalEngine.java`, `SignalResult.java`, `SmartLimitReentry.java` | M1/M5, wick, sweep/BOS, filter, Entry/SL/TP, READY | Uji unit; jangan menganggap skor saja cukup |
| Feed MT5 | `Mt5FeedClient.java`, `Candle.java` | Membaca snapshot harga/candle dari EA | Kontrak simbol, JSON, timestamp, Bid/Ask harus konsisten |
| Gateway Android | `GatewayService.java`, `Mt5Gateway.java` | LAN, port 8787, command queue/ACK, deduplikasi | **Area terlindungi**; jangan ubah sebagai bagian dari perbaikan UI |
| EA MT5 | `mt5/Experts/Rayyan4GatewayEA.mq5` | Publikasi feed/status, polling perintah, operasi LIMIT | **Area terlindungi**; perubahan wajib cocok dengan gateway/protokol dan diuji pada MT5 Demo |
| Penyimpanan/telemetri | `HistoryStore.java`, `AppLog.java`, `WinRateTracker.java`, `TelegramClient.java` | Riwayat, log, statistik, notifikasi | Uji sesuai komponen yang tersentuh |
| APK koneksi terpisah | `pingtest/` | APK diagnostik koneksi | Build sendiri; tidak mengubah APK utama |

## Alur validasi
1. **Component Checks**: unit test Android dan pemeriksaan marker integrasi MT5/Gateway.
2. **Build and Emulator Check**: unit test, build APK, instalasi/peluncuran emulator, laporan screenshot/log.
3. **Uji perangkat nyata**: OPPO A16 untuk APK + Gateway dan Vivo/Winlator untuk MT5/EA. Emulator CI tidak memiliki broker MT5; kelulusan emulator bukan bukti order demo berhasil.
4. **MT5 compile/demo**: compile EA di MetaEditor dan validasi feed, command/ACK, serta satu order LIMIT demo sebelum menyatakan integrasi selesai.

## Aturan perubahan aman
- Perubahan dikumpulkan per satu tugas dan commit; jangan melakukan edit massal lintas komponen tanpa pemeriksaan dependensi.
- Jangan mengganti port `8787`, JSON/protokol, command queue/ACK, atau mekanisme anti-duplikat sebagai efek samping perbaikan UI.
- Jangan memakai Biquote sebagai fallback tersembunyi untuk data candle MT5 pada chart yang sama.
- ENTRY READY hanya aktif jika seluruh filter dan geometri Entry/SL/TP valid; skor tinggi saja tidak cukup.
- Perubahan pada feed, simbol, format JSON, queue/ACK, atau EA harus menjalankan Component Checks dan Build and Emulator Check, lalu uji MT5 demo manual.
- Artefak APK dihasilkan hanya setelah unit test dan build berhasil. Uji emulator hanya memeriksa launch/proses; bukan validasi broker/order live.
- Workflow memakai concurrency per branch agar run lama tidak menumpuk ketika commit baru masuk.

## Workflow
- `.github/workflows/build-rayyan4-test.yml`: Component Checks (unit test + marker integrasi).
- `.github/workflows/rayyan4-emulator-check.yml`: build APK + smoke test emulator + laporan.
- `.github/workflows/build-connection-test.yml`: APK diagnostik `pingtest/` saja.
- `.github/workflows/build-rayyan4-clean.yml` dan `build-rayyan4-repair.yml`: workflow legacy khusus `main`; jangan gunakan sebagai validasi rutin pada branch fitur.
- `.github/workflows/rayyan-ui-preview.yml`: preview branch lama `rayyan-entry-ready-menu-fix`.

## Batasan
CI tidak bisa mengompilasi MQL5 dengan MetaEditor atau terhubung ke broker MT5. Karena itu, jangan menyebut EA teruji penuh hanya karena workflow GitHub sukses.
