# Island States, Animations & Trigger Scenarios

Referensi diambil dari perilaku **HyperOS Super Island** (Xiaomi), modul **HyperIsland** (1812z), dan **HyperBridge** (D4vidDf), serta model Apple Dynamic Island.

## Tiga State Visual

| State | Nama HyperOS | Bentuk | Ukuran default | Peran |
|-------|--------------|--------|----------------|-------|
| **Minimal** | 小岛 / Permanent | Capsule tipis hitam | ~120×36 dp | Idle / penutup cutout kamera |
| **Compact** | 大岛 (summary) | Capsule kiri–kanan kamera | ~200×40 dp (slider: 10–320 dp lebar, 10–72 dp tinggi) | Info ringkas (ikon + teks pendek) |
| **Expanded** | 展开态 (focus) | Kartu lebih besar, corner ~28 dp | ~320×90 dp (slider: 10–400 dp lebar, 10–160 dp tinggi) | Detail + tombol aksi |

## Animasi (diimplementasikan)

- **Size morph**: spring `dampingRatio=0.72`, `stiffness=520` (mirip iOS / HyperOS)
- **Corner radius**: 50 dp (capsule penuh) → 28 dp saat Expanded, otomatis mengecil lagi kalau lebar/tinggi pill custom lebih kecil dari radius itu sendiri (anti artefak sudut terpotong di ukuran ekstrem kecil)
- **Content transition**: fade + scale (0.92 → 1.0) + `SizeTransform` tanpa clip
- **Shadow elevation**: Minimal 2 → Compact 6 → Expanded 12, digambar via `graphicsLayer.shadowElevation` (bukan cuma dihitung) — terlihat karena window overlay memakai `PixelFormat.TRANSLUCENT` + hardware acceleration
- **Layout adaptif anti-terpotong**: setiap state diukur dengan `BoxWithConstraints` terhadap ukuran pixel *aktual* (bukan asumsi default), lalu ikon/font/padding/baris teks menyusut atau disembunyikan bertingkat sebelum sempat overflow — berlaku di seluruh rentang slider custom (Compact & Expand bisa diset sampai 10 dp)
- **Animasi per konten**:
  - *Media*: equalizer 3-bar berdenyut asinkron saat playing, progress bar tipis di bawah judul/artist (muncul kalau tinggi pill cukup), **cincin progres RGB adaptif di tepi pill + **mode interupsi**: saat event live lain (notif/charging/dll) masuk, musik menyusut jadi lingkaran progres di samping kanan (masih 1 pill ukuran sama), event utama tampil di area kiri; setelah 4 detik event hilang, musik expand kembali ke bentuk utuh dengan animasi morph smooth** (mulai dari tengah-atas, mengisi searah jarum jam, warna 3 random yang continuously shift hue, kecepatan mengikuti position/duration lagu, path dihitung ulang dari ukuran aktual sehingga tetap penuh tepat saat lagu habis meskipun ukuran Dynamic Island diubah custom)
  - *Charging*: glyph baterai dengan fill level animasi + shimmer halus saat charging, progress estimasi pengisian di mode Expanded
  - *Notification*: icon scale-in bertenaga spring saat muncul
  - *Ringer*: cross-fade + scale saat ikon berganti mode (Ring/Vibrate/Silent/DND)
  - *Flashlight*: glow pulsing lembut saat menyala
  - *Bluetooth / Hotspot*: radar ping melingkar saat status aktif
  - *Minimal*: titik tengah "bernapas" (breathing alpha) alih-alih titik statis, supaya idle state tetap terasa hidup
  - Pill super sempit (di bawah ambang lebar/tinggi tertentu) otomatis fallback ke indikator titik/equalizer saja tanpa teks, bukan memotong teks secara paksa

## Skenario Pemicu (dari HyperIsland / HyperBridge / sistem)

### → Compact (大岛)
| Event | Sumber di app ini | Catatan HyperOS/HyperBridge |
|-------|-------------------|-----------------------------|
| Media mulai diputar | `MediaSessionTracker` | Compact + kontrol play; tap → Expanded |
| Notifikasi masuk | `IslandNotificationListener` | Default summary; bisa auto-expand jika “first float” |
| Flashlight toggle | `SystemEventMonitor` (torch callback) | Compact singkat (~3s) |
| Mode ringer / DND | `SystemEventMonitor` | Compact atau Expanded singkat |
| Bluetooth / Hotspot | `SystemEventMonitor` | Compact status |

### → Expanded (展开态)
| Event | Sumber | Catatan |
|-------|--------|---------|
| Tap pada Compact | `onExpand` di UI | User gesture |
| Charging plug | `SystemEventMonitor` | Biasanya expand dulu ~5–6s |
| Notifikasi penting (first float) | `show(..., expand=true)` | HyperOS `islandFirstFloat=true` |
| Update konten live (opsional) | `enableFloat` | HyperOS bisa expand ulang saat update |

### → Minimal (小岛 / permanent)
| Event | Sumber | Catatan |
|-------|--------|---------|
| Timeout auto-collapse | `autoCollapseMs` (default 5s) | HyperOS default ~5s untuk expanded |
| Tidak ada aktivitas | Idle | Permanent island (HyperBridge) |
| User collapse | `onCollapse` / Stop Island | |

## Alur yang dipakai di ViewModel

1. `show(content, expand=false)` → **Compact**
2. `show(content, expand=true)` → **Expanded**, lalu setelah ~5s:
   - Media → kembali ke **Compact** (live activity)
   - Event sekali (charging, ringer, notif) → **Minimal**
3. Tap Compact → **Expanded**
4. Tap Expanded / timeout → collapse

## Referensi

- [HyperIsland (1812z)](https://github.com/1812z/HyperIsland) — LSPosed Super Island enhancer
- [HyperBridge (D4vidDf)](https://github.com/D4vidDf/HyperBridge) — bridge notifikasi ke HyperIsland native
- [Xiaomi Super Island docs](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131) — `islandFirstFloat`, `enableFloat`, big/small island templates
