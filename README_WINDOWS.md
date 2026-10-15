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

## Cara build

### A. GitHub Actions (paling mudah)
Push folder ini ke repo GitHub → tab **Actions → Build Windows (NoAmp FX)**. Hasil di Artifacts:
`NoAmpFX-portable` (zip, jalankan `NoAmpFX\NoAmpFX.exe`), `NoAmpFX-installer` (.exe installer), `noamp-dll`.

### B. Lokal (Windows)
Butuh: JDK 17, CMake ≥ 3.22, MinGW-w64 (GCC ≥ 11), Gradle 8.9, internet (mengunduh NAM core + miniaudio saat konfigurasi pertama).
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
- ASIO tidak didukung (WASAPI saja). Untuk latensi terendah pilih mode **Ultra** (exclusive) dan interface dengan driver WASAPI yang baik.
- Angka "XRUN" kini berarti callback audio melebihi waktunya (indikasi dropout), bukan hitungan driver.
