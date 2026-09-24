# Hyper Island

Dynamic Island untuk Android.

## Fitur

- Overlay Dynamic Island (Compact / Expanded)
- Now Playing + kontrol media
- Ringkasan notifikasi + aksi / balas
- Flashlight, ringer mode, status sistem
- Kontrol Airplane / Data / Lokasi / Hotspot / Bluetooth melalui **Shizuku** (opsional)
- Deteksi fullscreen melalui **Accessibility Service** (opsional)
- Equalizer / audio pulse (izin mikrofon)

## Cara pakai

1. Install APK  
2. Berikan izin **Overlay** dan **Notification Access**  
3. Buka aplikasi → **Mulai**  
4. Putar musik atau ubah ringer / senter — Island akan muncul  

### Shizuku (kontrol sistem dari Island)

1. Install dan jalankan [Shizuku](https://shizuku.rikka.app/)  
2. Di Hyper Island → **Izinkan Shizuku**  
3. Tombol Airplane, Data, Lokasi, dan Hotspot di tampilan Expanded dapat digunakan  

### Deteksi fullscreen

1. Di Hyper Island → **Aktifkan Aksesibilitas**  
2. Nyalakan layanan **Hyper Island**  
3. Island akan disembunyikan otomatis saat aplikasi masuk mode immersive  

## Log

```
/storage/emulated/0/Android/data/com.hyperisland/files/logs/hyperisland_debug.txt
```

## Build

```bash
./gradlew assembleDebug
```

`applicationId`: `com.hyperisland`
