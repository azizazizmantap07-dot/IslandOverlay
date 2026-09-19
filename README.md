# Hyper Island Root

Dynamic Island untuk **semua Android** (bukan hanya HyperOS).

## Dua mode

### 1) Overlay + LSPosed (disarankan – paling stabil)
Island tetap dari **OverlayService** (WindowManager). LSPosed hanya dipakai untuk:

- Menyembunyikan ikon status bar (wifi/sinyal/speed) saat pill Compact/Expanded supaya tidak “mencuri” sentuhan
- Deteksi fullscreen / immersive (sembunyikan island saat app full-screen)

1. Magisk + **LSPosed (Zygisk)**
2. Install APK ini
3. LSPosed Manager → aktifkan modul **Hyper Island Root**
4. Scope: centang **SystemUI** (`com.android.systemui`) dan
   `Hyper Island Root` (`com.hyperisland.root`)
5. Reboot
6. Buka app → **Start Island** → putar musik / ubah ringer / senter → Island muncul

Tanpa LSPosed, overlay tetap jalan; hanya fitur hide-icon & fullscreen-detect yang hilang.

### 2) Overlay saja (tanpa LSPosed)
1. Izin **Overlay** + **Notification Access**
2. Buka app → **Start Island**
3. Island mengambang di atas (posisi tergantung ROM)

## Fitur otomatis (setelah Start Island)

| Event sistem | Island |
|--------------|--------|
| Senter QS / torch | Flashlight On/Off |
| Silent / Vibrate / Ring | Ringer mode |
| DND | Do Not Disturb |
| Colok / cabut charger | Charging info |
| Bluetooth on/off | Bluetooth status |
| Hotspot on/off | Hotspot status |
| Wi-Fi on/off | Wi-Fi status + SSID |
| Mobile data on/off | Cellular data status |
| Airplane mode | Custom status |
| Lokasi on/off | Location status |
| Musik (MediaSession) | Now playing + play/pause |
| Notifikasi | Summary + aksi / reply |

Quick Tests di app = simulasi manual. Event di atas = **asli dari sistem**.

## Log debug / crash
```
/storage/emulated/0/Android/data/com.hyperisland.root/files/logs/hyperisland_debug.txt
```
Kirim file ini jika ada masalah.

## Build
- GitHub Actions: push ke `main` → artifact APK
- Local: `./gradlew assembleDebug`

### Setup sekali agar APK bisa "update timpa" (install tanpa uninstall dulu)
Android hanya izinkan install APK baru menimpa yang lama kalau `applicationId`
**dan** signature-nya sama persis. `applicationId` sudah tetap (`com.hyperisland.root`),
tapi signature debug default di-generate ulang tiap runner CI baru — jadi perlu
signing key permanen yang sama dipakai di setiap build.

1. Generate keystore permanen sekali saja (sudah dibuat, lihat `keystore/hyperisland-debug.keystore` –
   **jangan commit file ini**, sudah di-`.gitignore`):
   ```
   keytool -genkeypair -v -keystore keystore/hyperisland-debug.keystore \
     -alias hyperisland -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Encode ke base64:
   ```
   base64 -w0 keystore/hyperisland-debug.keystore > keystore.b64
   ```
3. Di GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**, tambahkan:
   - `KEYSTORE_BASE64` → isi file `keystore.b64`
   - `KEYSTORE_PASSWORD` → password keystore
   - `KEYSTORE_ALIAS` → `hyperisland` (opsional, ini sudah default)
4. Push ulang / jalankan workflow → APK hasil build sekarang selalu ditandatangani
   dengan key yang sama → `adb install app-debug.apk` (tanpa `-r` pun boleh, tapi
   `-r` lebih aman) akan **menimpa** versi lama, tidak perlu uninstall.

Untuk build **lokal** (Android Studio / `./gradlew`), copy `keystore.properties.example`
jadi `keystore.properties` (jangan commit), isi password yang sama, taruh file
`.keystore` di path yang tertulis di situ.

## Versi
**1.3.3-theme-fix** (versionCode 18) — removed dead code (IslandViewModel, RadarPing, unused deps) and synchronized versions

## Stack
Kotlin, Jetpack Compose, libsu (root opsional), MediaSession, CameraManager torch, LSPosed hook SystemUI (icon visibility + fullscreen)
