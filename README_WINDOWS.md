# NoAmp FX untuk Windows

Port Windows dari proyek Android **NoAmpFX v2.4**. Mesin DSP (gate, comp, pitch, wah, dist, NAM amp, cab/IR, EQ, mod, delay, reverb, limiter, looper, metronome, recorder, player) dipakai ulang apa adanya; yang diganti hanya lapisan Android.

| Bagian | Android | Windows |
|---|---|---|
| Audio I/O | Oboe | miniaudio → **WASAPI** (full-duplex; mode Ultra = exclusive) |
| UI | Jetpack Compose | **Compose Multiplatform Desktop** (kode UI yang sama) |
| Pembungkus | APK | `NoAmpFX.exe` (jpackage) + `noamp.dll` (JNI) |
| Simpan data | SharedPreferences / filesDir | `%APPDATA%\NoAmpFX\` |
| Dialog file | SAF | dialog file bawaan Windows |
| MIDI | android.media.midi | `javax.sound.midi` (USB, BLE-MIDI yang sudah di-pair di Windows, port virtual) |
| Decode lagu | MediaCodec | miniaudio: **WAV / MP3 / FLAC** |

## Penyesuaian UI untuk desktop
- Sidebar kiri: tombol Engine (CPU / latensi), daftar modul dengan LED on/off, alat (Looper, Music, Recorder, Metronome, Mixer), Tuner, Stage, Settings.
- Konten dipusatkan dengan lebar maksimum yang nyaman dibaca; jendela bisa di-resize (min 1040×660).
- Scroll mouse memutar knob (Shift = langkah halus), klik dua kali = reset, kursor tangan di tombol.
- Pintasan: `Ctrl+←/→` preset, `Ctrl+E` engine, `Ctrl+T` tuner, `Esc` kembali, `F11` layar penuh.
- Library musik memindai folder pilihan Anda (default `Musik`), bukan MediaStore.

## Fitur Android yang tidak ada di versi Windows
Rekam video / kamera / layar, "Full Record" (menangkap audio aplikasi lain), mic HP sebagai Input 2 (Input 2 = kanal 2 interface), umpan balik getar, pemindaian Bluetooth MIDI dari dalam aplikasi (pair lewat Pengaturan Windows), decode OGG/M4A/AAC.

## ASIO dan VST3 (opsional, butuh SDK Steinberg)
Settings → **Audio**: pilih **Windows audio (WASAPI)** atau **ASIO**. Mode ASIO menampilkan daftar driver ASIO terpasang, kanal input (gitar) dan pasangan output, **sample rate** (44.1 – 192 kHz sesuai kemampuan driver), **buffer size** (dalam sample + ms) dan tombol **Driver panel**. Pilihan device WASAPI sekarang diingat antar sesi.
Settings → **VST3**: satu slot plug-in efek (stereo). Posisi **sesudah chain** (reverb, EQ, mastering) atau **sebelum chain** (amp sim / gate, diproses mono). Ada daftar plug-in terpasang (`C:\Program Files\Common Files\VST3` + folder tambahan), tombol editor, on/off, dan state plug-in disimpan otomatis.

SDK tidak saya sertakan (lisensi Steinberg):
1. **VST3**: workflow GitHub Actions mengunduhnya otomatis dari repo publik `steinbergmedia/vst3sdk`. Secara lokal: `git clone --recurse-submodules https://github.com/steinbergmedia/vst3sdk third_party/vst3sdk`. Periksa lisensinya untuk distribusi Anda.
2. **ASIO**: unduh ASIO SDK dari Steinberg (perlu menerima lisensi mereka), salin ke `third_party/asiosdk` (harus ada `iasiodrv.h`, `asio.h`, `asiosys.h`). Jangan commit ke repo publik; pakai repo **private**. Aplikasi yang memakai ASIO wajib mencantumkan "ASIO is a trademark and software of Steinberg Media Technologies GmbH".
3. Tanpa salah satu SDK, bagian itu otomatis dimatikan dan menu menampilkan penjelasan. Jika build dengan SDK gagal dikompilasi, workflow otomatis membangun ulang tanpa keduanya supaya Anda tetap mendapat aplikasi yang jalan; lihat peringatan kuning di log.

Batasan: VST3 hanya efek 64-bit mono/stereo (bukan instrumen), tanpa MIDI/sidechain, satu slot, tersimpan per aplikasi (bukan per preset). ASIO: driver dibuka/ditutup lewat thread COM khusus; driver 32-bit tidak terlihat.

## Cara build

### A. GitHub Actions (paling mudah)
Push folder ini ke repo GitHub → tab **Actions → Build Windows (NoAmp FX)**. Hasil di Artifacts:
`NoAmpFX-portable` (zip, jalankan `NoAmpFX\NoAmpFX.exe`), `NoAmpFX-installer` (.exe installer), `noamp-dll`.

### B. Lokal (Windows)
Butuh: JDK 17, CMake ≥ 3.22, MinGW-w64 (GCC ≥ 11), Gradle 8.9, (opsional) SDK di `third_party/`, internet (mengunduh NAM core + miniaudio saat konfigurasi pertama).
```powershell
cmake -S native -B native/build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build native/build -j 4
mkdir resources\windows-x64 -Force; copy native\build\noamp.dll resources\windows-x64\
gradle run                    # jalankan langsung
gradle createDistributable    # folder portabel di build\compose\binaries\main\app\NoAmpFX
gradle packageExe             # installer (butuh WiX 3)
```
CPU sangat lama tanpa AVX2: tambahkan `-DNOAMP_AVX2=OFF` pada perintah cmake pertama.

## Lisensi (WAJIB diisi sebelum build)
`License.kt` memakai skema yang sama dengan Android (ECDSA, offline). Zip yang Anda kirim masih berisi placeholder `REPLACE_WITH_PUBLIC_KEY`, jadi **tempel public key yang sama** dengan `License.kt` di proyek Android Anda ke `src/main/kotlin/com/noamp/fx/License.kt` (`PUBLIC_KEY_B64`). Dengan begitu satu private key menerbitkan lisensi untuk HP dan PC.
Lisensi terikat per PC: Device ID Windows diturunkan dari `MachineGuid`, jadi ID-nya berbeda dari ID HP. Pakai `tools/keygen_gui.pyw` seperti biasa.

## Catatan jujur
- Kode ini **belum pernah dikompilasi/dijalankan di Windows** (lingkungan tempat saya mengerjakannya tanpa internet, Windows, atau Gradle). Yang sudah diperiksa: C++ engine lolos pengecekan tipe dengan stub miniaudio/NAM, dan keseimbangan kurung semua file Kotlin. Build pertama di Actions kemungkinan masih menampilkan beberapa error kecil (import / nama API) — kirim lognya, mudah diperbaiki.
- Kode ASIO dan VST3 adalah bagian yang **paling belum teruji** (butuh SDK + perangkat nyata). Tanpa SDK keduanya tidak ikut dikompilasi.
- Untuk latensi terendah: ASIO dengan buffer 64–128 sample, atau WASAPI mode **Ultra** (exclusive).
- Angka "XRUN" kini berarti callback audio melebihi waktunya (indikasi dropout), bukan hitungan driver.
