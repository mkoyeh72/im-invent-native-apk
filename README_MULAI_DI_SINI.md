# IM-INVENT Android Native Scanner — Proyek Android

## Status
Ini **SOURCE CODE**, belum APK siap instal. Kode ini belum berhasil dikompilasi di lingkungan pembuatan saat ini karena Android SDK/Gradle serta koneksi pengunduh dependensi tidak tersedia. Workflow GitHub Actions disediakan agar pengguna dapat membangun APK dari GitHub. Lakukan uji menyeluruh di perangkat Android dan WebApp staging sebelum digunakan untuk data operasional.


## Konfigurasi staging khusus paket ini
- **Aplikasi:** IM-INVENT TEST (bukan produksi).
- **URL default:** `https://script.google.com/macros/s/AKfycbwDYbzHrredpCZ989Rmw9pBPrjDVmn-OuHnPk8_-zH6i0DZt22_MVt8B-JXo7u94aAoWQ/exec`
- **Lokasi konfigurasi:** `app/src/main/java/id/mkoyeh/iminvent/MainActivity.java`, konstanta `DEFAULT_STAGING_URL`.
- URL bukan password, tetapi tetap pastikan deployment staging dibatasi dan autentikasinya benar.
- **PENTING:** Untuk memunculkan detail setelah scan, selain APK kamu harus memasang patch Apps Script `IM-INVENT_Apps_Script/INDEX_NATIVE_SCAN_RC10.html` ke proyek staging. Jika source staging sudah lebih baru, merge hanya perubahan `RC-NATIVE` daripada menimpa seluruh file. Setelah itu deploy versi baru.
- **Status:** Source code siap **dicoba dibangun** di GitHub Actions. Build APK dan pengujian login/scan nyata belum dilakukan.
- **Keamanan:** Jangan upload password, secret, spreadsheet backup, atau keystore ke repo. Buat repository GitHub privat.

## Arsitektur
- Android WebView memuat deployment resmi **Google Apps Script** IM-INVENT.
- Tombol scanner di IM-INVENT memakai Google Code Scanner (Google Play services), tanpa iframe dan tanpa kamera GitHub Pages.
- Hasil scan masuk melalui bridge dua fungsi (`startScan`, `pollResult`) di APK dan diproses oleh `window.processScannedCode()` di WebApp.
- **WebView yang sama** tetap digunakan: tidak membuka tab lain dan tidak membuat login ulang.
- Kode barcode tidak memuat password/token; Check-Out dan Check-In tetap meminta konfirmasi, serta diputuskan backend IM-INVENT.

## Apa yang perlu diganti pada IM-INVENT staging
1. Backup `index.html` Apps Script staging yang sekarang aktif.
2. File `IM-INVENT_Apps_Script/INDEX_NATIVE_SCAN_RC10.html` dibuat dari sumber RC10 yang tersedia di percakapan ini dan menambahkan jembatan scanner Android. **Jangan menimpa perubahan fitur baru yang kamu lakukan sesudah RC10.** Jika ada perubahan, merge bagian `RC-NATIVE` di atas `window.openScannerModal` ke `index.html` kamu yang terbaru.
3. Deploy **Manage deployments -> Edit -> New version** pada WebApp staging.
4. Tidak perlu perubahan `code.gs`, `setup.gs`, atau database untuk pengujian bridge.

## Build APK dengan GitHub Actions
1. Buat repository GitHub BARU khusus proyek Android, misalnya `im-invent-native-apk` (jangan gabungkan ke repository scanner GitHub Pages).
2. Unggah **isi folder proyek ini** ke root repository, termasuk folder tersembunyi `.github/workflows/build-apk.yml`.
3. Buka tab **Actions** -> `Build IM-INVENT Native Scanner APK` -> `Run workflow` (atau workflow berjalan otomatis saat commit ke `main`).
4. Setelah build **success**, buka job -> bagian **Artifacts** -> unduh `IM-INVENT-Native-Scanner-debug-apk` dan ekstrak ZIP-nya; di dalamnya ada `app-debug.apk`.
5. Pasang pada HP Android. Paket ini **sudah berisi URL deployment staging yang pengguna berikan**, jadi saat pertama dibuka langsung mengarah ke IM-INVENT salinan. Jika perlu mengganti URL kemudian, ketuk tombol ⚙ di toolbar APK.
6. Login ke WebApp, ketuk **Pindai Barcode / QR Cepat** atau tombol **SCAN** pada toolbar native.

## Catatan keamanan/operasional
- APK ini **debug build**, bukan paket distribusi produksi. Setiap build baru mungkin memakai sertifikat debug berbeda sehingga tidak bisa menimpa APK lama (perlu uninstall). Untuk produksi, gunakan penandatanganan rilis dengan keystore yang dikelola sendiri secara privat; jangan unggah keystore ke GitHub publik.
- Google Code Scanner memerlukan **Google Play services** di perangkat dan mungkin mengunduh modul scanner saat pemakaian pertama. Tanpa layanan tersebut, gunakan solusi alternatif atau build decoder offline (belum termasuk).
- Bridge native diekspos ke iframe WebView tetapi **hanya** untuk membuka scanner & membaca kode. Jangan menambahkan metode untuk membaca password, token, data Sheets, atau transaksi yang melewati backend.
- Pengaturan WebApp `Siapa saja` harus ditinjau sesuai audit keamanan. URL /exec hanya boleh dikonfigurasi ke script.google.com.
- Jangan install di perangkat produksi sebelum login, scan, Check-Out, Check-In, penolakan Check-In milik orang lain, dan perubahan kalibrasi diuji di salinan spreadsheet.
- Jika `index.html` belum di-patch, tombol SCAN native hanya dapat mengembalikan kode tanpa detail. Detail membutuhkan patch RC-NATIVE + data backend yang siap.
- Upload proyek ke GitHub akan membuat **source code** terlihat jika repo publik. Sebaiknya buat repository **Private**, karena kode IM-INVENT terkait perusahaan.
