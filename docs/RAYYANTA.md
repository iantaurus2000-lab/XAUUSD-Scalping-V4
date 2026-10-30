# RAYYANTA
## Prinsip Dasar Manajemen Posisi EA
Dokumen acuan untuk penerapan, audit kode, dan pengujian demo MT5.

> **Prinsip inti:** Jangan menutup posisi hanya karena harga bergerak berlawanan atau mendekati Stop Loss (SL). Pantau apakah harga pulih mengikuti arah entry atau gagal mempertahankan pemulihan. Beri kesempatan posisi berkembang, dengan SL keras tetap aktif.

## 1. Prinsip utama strategi

EA mengelola posisi secara adaptif dengan memantau dua kemungkinan setelah entry. Keputusan tidak boleh dibuat hanya berdasarkan satu gerakan harga sesaat. Harga yang mendekati SL adalah sinyal untuk meningkatkan pemantauan, bukan perintah otomatis untuk menutup posisi.

### A. Harga berlawanan lalu pulih mengikuti arah entry
- **BUY:** harga turun atau mendekati SL, kemudian berbalik naik dan kembali mendukung arah BUY.
- **SELL:** harga naik atau mendekati SL, kemudian berbalik turun dan kembali mendukung arah SELL.
- EA mempertahankan posisi selama pemulihan dan kelanjutan arah entry masih valid.
- Jangan menutup posisi hanya karena harga sempat mendekati SL atau mengalami retracement kecil.
- Berikan kesempatan memperoleh profit selama minimal 3 candle M5 atau lebih, selama kondisi pasar mendukung dan SL keras belum tersentuh.

### B. Harga gagal mempertahankan pemulihan dan kembali melawan entry
- EA mengamati apakah pemulihan melemah, gagal berlanjut mendukung posisi, lalu berbalik melawan arah entry.
- Penutupan dini sebelum 3 candle M5 diperbolehkan jika pembalikan telah terkonfirmasi, bukan karena retracement kecil atau gerakan sesaat.
- Jika memungkinkan, utamakan keluar dengan profit tipis di atas harga entry.
- Jika profit tidak memungkinkan, EA boleh mengurangi kerugian sebelum SL keras tersentuh, berdasarkan konfirmasi kegagalan arah.
- SL keras tetap aktif sebagai perlindungan terakhir. EA tidak boleh menghapus, memperlebar, atau mengabaikan SL.

**Keseimbangan wajib:** jangan terlalu cepat keluar, tetapi jangan mempertahankan posisi yang sudah terbukti kehilangan arah hingga kerugiannya membesar.

## 2. Perubahan yang harus dilakukan pada EA

1. **Deteksi pergerakan berlawanan:** catat jarak harga terhadap entry dan SL serta ekstrem pergerakan sejak posisi dibuka. Mendekati SL hanya mengaktifkan pemantauan risiko.
2. **Pemantauan pemulihan:** ukur apakah harga kembali bergerak ke arah posisi dan apakah pemulihan cukup kuat untuk dipertahankan.
3. **Deteksi kegagalan pemulihan:** kenali pemulihan yang gagal, pelemahan momentum, dan pembalikan terkonfirmasi. Hindari keputusan dari satu tick atau retracement kecil.
4. **Penutupan dini perlindungan risiko:** boleh aktif sebelum 3 candle M5 hanya setelah kegagalan arah terkonfirmasi. Utamakan profit tipis jika memungkinkan; jika tidak, kurangi kerugian secara terukur.
5. **Manajemen profit normal:** tetap menahan posisi minimal 3 candle M5. Setelah itu, aturan retracement/profit normal yang disepakati dapat bekerja.
6. **Hard Stop Loss:** pertahankan SL keras dan pastikan perhitungan internal EA sesuai dengan SL aktual di MT5.
7. **Pemulihan siklus scan:** setelah posisi ditutup, EA kembali melakukan scan sesuai aturan entry.

## 3. Sistem pemantauan yang disarankan

1. **Entry dan pencatatan:** simpan ticket, arah BUY/SELL, harga entry, SL, waktu entry, Bid/Ask, serta data candle M5 relevan.
2. **Pemantauan dua kemungkinan:** pantau pergerakan berlawanan dan pemulihan. Simpan ekstrem adverse dan recovery agar EA tidak mengambil keputusan dari perubahan sesaat.
3. **Pemulihan berhasil:** jika harga kembali mengikuti arah entry dan mampu mempertahankan pergerakan, tahan posisi. Jangan tutup hanya karena sebelumnya mendekati SL.
4. **Pemulihan gagal:** jika pemulihan terkonfirmasi gagal dan harga kembali melawan posisi, evaluasi kesempatan keluar. Penutupan dini boleh dilakukan sebelum 3 candle M5.
5. **Penahanan profit normal:** jika tidak ada alasan perlindungan risiko yang terkonfirmasi, pertahankan posisi minimal 3 candle M5 atau lebih. Sesudahnya gunakan aturan retracement normal.
6. **SL dan siklus berikutnya:** SL keras tetap aktif. Setelah posisi ditutup, bersihkan state pemantauan ticket dan kembalikan EA ke proses scan.

## 4. Bagian kode yang harus diaudit dan direvisi

| Fungsi / bagian | Fokus audit dan revisi |
|---|---|
| `powerManagePositions()` | Audit seluruh alur manajemen posisi. Pisahkan perlindungan risiko dari manajemen profit normal. |
| `E1_EARLY_PROFIT_RETRACE_NET_POSITIVE` | Hilangkan pemicu dini yang hanya bergantung pada net profit positif dan retracement kecil. Jangan melewati masa tahan 3 candle tanpa konfirmasi risiko. |
| `HARD_LOSS_GUARD` | Periksa jarak, satuan point/tick, Bid/Ask, dan SL aktual. Pastikan guard tidak menutup lebih awal akibat perhitungan yang berbeda dari SL. |
| `powerProjectedNet()` | Verifikasi profit bersih berdasarkan harga keluar yang dapat dieksekusi, komisi, swap, dan biaya terkait. |
| `powerClosePosition()` dan semua pemanggilnya | Audit semua jalur penutupan, termasuk fungsi lain, perintah gateway/APK, dan kemungkinan EA/strategi lain. |
| State per ticket | Pastikan ekstrem adverse, recovery, peak profit, dan status pemantauan tersimpan per ticket dan dibersihkan setelah posisi ditutup. |

## 5. Hal yang tidak boleh berubah

- Penahanan profit normal minimal 3 candle M5 tetap berlaku.
- Pengecualian sebelum 3 candle hanya untuk perlindungan risiko setelah kegagalan arah benar-benar terkonfirmasi.
- SL keras tetap aktif dan tidak boleh dihapus, diperlebar, atau diabaikan.
- Posisi profit yang masih terbuka tetap memblokir seluruh entry baru BUY dan SELL pada simbol yang sama.
- Setelah posisi ditutup, EA kembali melakukan scan sesuai aturan entry.
- Aturan entry D1/E1, pengukuran ekstrem High/Low, aturan layer, dan gateway tidak diubah kecuali audit membuktikan ada kaitan langsung dan perubahan disetujui.
- Logika STANDAR dan EXCELLENT tidak boleh dicampurkan ke POWERFULL atau D1/E1.
- Seluruh revisi tetap pada satu branch utama GitHub: `RAYYAN`. Jangan membuat branch baru.

## 6. Persetujuan sebelum pengerjaan

**Persetujuan prinsip:** penutupan dini karena arah entry gagal BOLEH dilakukan sebelum 3 candle M5, termasuk ketika posisi masih sedikit profit atau sedang rugi tipis, asalkan pembalikan/kegagalan arah telah terkonfirmasi. SL keras tetap menjadi batas perlindungan terakhir.

Urutan kerja sebelum revisi kode dan build berikutnya:

1. Audit seluruh jalur penutupan posisi dan cari penyebab penutupan terlalu cepat.
2. Tetapkan kondisi pemicu secara eksplisit untuk pemulihan berhasil dan pemulihan gagal.
3. Laporkan ringkasan perubahan sebelum mengubah kode.
4. Revisi hanya bagian yang diperlukan; jangan mengubah aturan entry atau bagian lain yang tidak terkait.
5. Kompilasi EA dan periksa hasil MetaEditor; laporkan jumlah error dan warning.
6. Uji demo MT5 untuk BUY/SELL: mendekati SL lalu pulih; pulih lalu gagal; posisi berlanjut lebih dari 3 candle; serta SL tersentuh.
7. Periksa log setiap penutupan: alasan, ticket, harga, net profit, dan SL.
8. Jangan menyatakan strategi lulus pengujian hanya berdasarkan kompilasi berhasil.

---
**Akhir dokumen — RAYYANTA**
