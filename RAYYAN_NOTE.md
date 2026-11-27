# RAYYAN EA — BUILD NOTE

Dokumen ini disertakan di dalam ZIP setiap hasil build EA. Setiap workflow build menambahkan nomor run, branch, commit, dan tautan run secara otomatis.

## Ketetapan entry POWERFULL yang disetujui
- Aktivasi trailing ekstrem: 1.000 broker points dari Open candle M5 ke Low live untuk BUY atau High live untuk SELL.
- Trailing pelemahan adaptif: 1% dari rentang High–Low candle M5 yang sedang berjalan, dikonversi ke tick dan dibatasi minimum 2 tick, maksimum 8 tick.
- Trailing selalu mengikuti ekstrem terbaru candle M5 aktual. Ekstrem baru menggantikan kandidat sebelumnya sebelum konfirmasi pelemahan.
- Layer pertama hingga layer 5 menggunakan mekanisme trailing ekstrem yang sama.
- Beberapa layer pada candle M5 yang sama diperbolehkan untuk ekstrem berbeda yang memenuhi syarat.
- Tetap pertahankan market execution, filter arah M5/H1, blackout 40 detik sebelum candle M5 berakhir, satu arah basket, batas layer, SL, serta seluruh manajemen posisi/profit.
- Tidak memakai ATR, range candle yang sudah selesai, atau batas satu entry per candle.

## Penguncian izin
Logika entry POWERFULL di atas tidak boleh diubah, dihapus, ditambah, atau ditafsirkan ulang tanpa izin eksplisit pemilik. Setiap perubahan yang disetujui harus dicatat pada dokumen ini dan build berikutnya. Tidak boleh ada perubahan diam-diam.

## Riwayat build
### Workflow run #160 — Revise POWERFULL entry to 1000-point adaptive extreme trailing
- Tanggal: 2026-11-27
- Branch: `RAYYAN`
- Commit sumber strategi: `772884d5c9d002053ae4dc3d00819879c87f5d65`
- Workflow run ID: `46009449316`
- Hasil kompilasi: Standalone EA 0 errors, 0 warnings; Gateway EA 0 errors, 0 warnings.
- Artifact ID: `15202736534`
- SHA256 ZIP: `016840263c93bdfca6489cf41b21a4d9ef4d75df4ce79a50557e302585884971`
- Catatan: artifact run #160 asal belum memuat dokumen ini. Workflow packaging diperbarui agar build selanjutnya memasukkan RAYYAN_NOTE.md ke dalam ZIP.

### Workflow run #159 — Dashboard live quote placement
- Commit: `de430774e6296fbf3804fd82321d396a9afbba09`
- Workflow run ID: `46000490694`
- Perubahan: harga realtime MT5 dipindahkan ke bawah status ALGO, rata kanan, font lebih besar dan tebal.
