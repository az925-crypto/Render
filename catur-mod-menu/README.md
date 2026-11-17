# Catur Mod Menu

Overlay pendamping untuk aplikasi catur Chess.com di Android. Panel mengambang
membaca posisi dari daftar langkah yang terlihat di layar, lalu menjalankan
Stockfish (NNUE embedded) untuk menyarankan langkah balasan setelah lawan
bermain. Juga bisa membaca papan dari screenshot dan menerima FEN manual.

Folder ini dibangun **hanya lewat GitHub Action** di repo `Render`, sesuai
 ketentuan repo. README utama di root repo tidak diubah.

## Build

1. Push folder `catur-mod-menu/` ke repo ini (branch `main`).
2. Buka tab **Actions** → workflow **Build Catur Mod Menu** → **Run workflow**.
   Build juga jalan otomatis saat ada perubahan di folder ini.
3. Workflow membuat keystore release lalu menjalankan `./gradlew assembleRelease`.
   APK hanya beberapa MB — biner mesin tidak ikut dibundling.
4. Unduh artefak `catur-mod-menu-release`, ekstrak, dapat `app-release.apk`.
   APK ini sudah ditandatangani (signed), punya ikon, dan langsung bisa di-install.
5. Saat pertama kali Start overlay, aplikasi mengunduh Stockfish 19 arm64
   (~80 MB) dari rilis resmi GitHub. Butuh koneksi internet di langkah ini;
   setelah itu mesin tersimpan di penyimpanan privat aplikasi dan dipakai offline.

Build memakai JDK 17, Gradle 8.7 wrapper, AGP 8.5.2, compileSdk 35.

## Pasang & pakai

1. Install `app-release.apk` di perangkat Android arm64 (Android 8+).
2. Buka aplikasi, berikan izin **Draw over other apps** dan **Notifications**.
3. Tombol **Accessibility access** → aktifkan *Catur Mod Menu* di Settings →
   Accessibility. Ini dipakai untuk membaca teks daftar langkah.
4. Tombol **Start overlay** → setujui screen capture (dipakai tombol SCAN BOARD).
5. Buka Chess.com dan mainkan / lanjutkan sebuah game. Biarkan panel mengambang.

Panel menampilkan: langkah terbaik (SAN), evaluasi, kedalaman, dan garis utama.
Panah kuning digambar di papan setelah papan pernah di-scan sekali.

## Sumber posisi

- **Daftar langkah (utama):** AccessibilityService membaca teks notasi, lalu
  `Chess.java` merekonstruksi FEN dari SAN. Cara ini paling akurat.
- **Screenshot (cadangan):** tombol SCAN BOARD menangkap layar, mencari papan,
  menentukan orientasi, lalu mencocokkan 12 siluet bidak.
- **FEN manual:** paste FEN di layar utama → **Analyse FEN**.

Warna pemain (White/Black) menentukan kapan saran muncul: hanya saat giliran
pemain. Atur di layar utama sebelum start.

## Struktur

```
app/src/main/java/com/catur/modmenu/
  Chess.java              aturan catur + FEN + SAN (murni Java, ada tes)
  EngineController.java   dialog UCI dengan Stockfish
  BoardWatchService.java  AccessibilityService, baca daftar langkah
  BoardDetector.java      deteksi papan dari screenshot
  ModMenuService.java     foreground service + jendela overlay + panah
  ArrowView.java          lapisan panah transparan
  MainActivity.java       izin & pengaturan
tests/ChessTest.java      tes aturan catur (javac + java)
```

## Tes aturan catur

```bash
mkdir -p /tmp/chessbuild && cd /tmp/chessbuild
cp -r /path/to/catur-mod-menu/app/src/main/java/com/catur/modmenu/Chess.java .
cp /path/to/catur-mod-menu/tests/ChessTest.java .
sed -i '1i package com.catur.modmenu;' ChessTest.java
javac Chess.java ChessTest.java && java com.catur.modmenu.Test
```

## Catatan teknis

- Biner Stockfish tidak disimpan di repo atau APK supaya unduhan tidak putus.
  Aplikasi mengunduhnya sekali dari rilis resmi `sf_19` saat first run.
- Hak akses aksesibilitas hanya membaca teks di layar; tidak ada jaringan,
  tidak ada pengiriman data.
- Deteksi screenshot bersifat best-effort dan bergantung tema papan. Jika
  gagal, pakai daftar langkah atau FEN manual.
