# RAYYAN EA — BUILD NOTE

Dokumen ini menjadi catatan tetap untuk setiap hasil build EA. Nomor build mengikuti nomor workflow GitHub Actions yang benar.

## Kunci logika entry POWERFULL — #162

**Status: DIKUNCI. Tidak boleh diubah tanpa izin eksplisit pemilik.**

- Sumber editable: `mt5/Experts/RayyanD1Standalone_127_BACKUP.mq5`. EA original `RayyanD1Standalone.mq5` tetap dilindungi dan tidak diubah.
- Aktivasi ekstrem: jarak ekstrem live M5 dari Open minimal 1.000 broker points. BUY memakai Low di bawah Open; SELL memakai High di atas Open.
- Setiap layer, termasuk layer 2–5, harus menunggu ekstrem baru yang lebih jauh daripada ekstrem layer terakhir yang berhasil terisi.
- Setiap kandidat ekstrem memiliki trailing pelemahan sendiri. BUY mengikuti kandidat Low terbaru; SELL mengikuti kandidat High terbaru.
- Jika ekstrem baru terbentuk sebelum pelemahan terkonfirmasi, kandidat trail diperbarui ke ekstrem baru dan konfirmasi pelemahan dihitung ulang dari sana.
- Entry hanya setelah trailing pelemahan terkonfirmasi: adaptif 1% dari rentang live M5, dibatasi minimum 2 tick dan maksimum 8 tick (mengikuti parameter).
- Tepat sebelum market order, harga eksekusi harus tetap berjarak minimal 1.000 broker points dari Open M5: BUY menggunakan Ask dan SELL menggunakan Bid. Jika tidak terpenuhi, order tidak dikirim.
- Kandidat ekstrem hanya dikonsumsi setelah broker mengonfirmasi order berhasil. Jika gagal, kandidat boleh mencoba lagi selama seluruh syarat masih terpenuhi.
- Beberapa layer dalam candle M5 yang sama tetap diperbolehkan untuk ekstrem berbeda yang memenuhi seluruh syarat. Tidak ada batas satu entry per candle.
- Maksimal lima layer; satu arah basket; market order saja; spread hanya informasi dan bukan pemblokir.
- Filter warna tetap: BUY = M5 merah + H1 hijau; SELL = M5 hijau + H1 merah; doji memblokir.
- Blackout 40 detik terakhir candle M5 tetap berlaku.
- Tidak menggunakan ATR, warm-up, cooldown, atau candle historis untuk entry.
- Seluruh aturan SL, Profit Lock, LOSS_RESCUE, hard-loss guard, dan manajemen posisi dipertahankan tanpa perubahan pada revisi ini.

### Larangan perubahan
Entry POWERFULL di atas dikunci. Tidak boleh menambah, menghapus, mengubah, atau menafsirkan ulang kondisi entry tanpa izin eksplisit pemilik. Perubahan di luar entry juga tidak boleh menyentuh manajemen posisi/profit yang sudah dikunci tanpa izin.

## Riwayat build

### Workflow build #162 — Lock per-layer POWERFULL weakening trail and execution-distance gate
- Tanggal: 2026-11-27
- Branch: `RAYYAN`
- Commit sumber: `d8e66a135ac574c89c03b95788f4ed9ed0aae520`
- Workflow run ID: `46017348543`
- Workflow: https://github.com/iantaurus2000-lab/XAUUSD-Scalping-V4/actions/runs/46017348543
- Standalone EA: **0 errors, 0 warnings**
- Gateway EA: **0 errors, 0 warnings**
- Artifact ID: `15205669509`
- Artifact SHA256: `a17bf1cfa388933e2fc07c2ae95f140cfb8a691ef6e5dd85b5d3e952e64201e1`
- Catatan: kompilasi MetaEditor berhasil dan artifact terunggah. Uji trading demo MT5 tetap perlu dilakukan; hasil compile bukan bukti strategi sudah lulus forward test.

### Workflow run #160 — Revise POWERFULL entry to 1000-point adaptive extreme trailing
- Commit sumber strategi: `772884d5c9d002053ae4dc3d00819879c87f5d65`
- Workflow run ID: `46009449316`
- Standalone EA dan Gateway EA: 0 errors, 0 warnings.
- Artifact ID: `15202736534`
- SHA256 ZIP asli: `016840263c93bdfca6489cf41b21a4d9ef4d75df4ce79a50557e302585884971`
- Catatan: artifact run #160 mendahului revisi #162.

### Workflow run #159 — Dashboard live quote placement
- Commit: `de430774e6296fbf3804fd82321d396a9afbba09`
- Workflow run ID: `46000490694`
- Perubahan: harga realtime MT5 dipindahkan ke bawah status ALGO, rata kanan, font lebih besar dan tebal.
