# ÇDTP – İki Tekerlekli Ev İçi Hasta Destek Robotu
**Proje planı ve Modül D (Mobil Uygulama) çalışma dokümanı**
YTÜ EEF · 2026-2027 Güz · Rol: Bilgisayar Mühendisliği (Recep)

---

## 1. Proje özeti

Kendi dengesini aktif olarak sağlayan iki tekerlekli bir robot yapılacak.
1. Robot, üzerinde bir ilaç kutusu taşıyarak dengede durur.
2. **Giyilebilir eldivenden** gelen kablosuz komutlarla hedefe gider.
3. Görev bitince **şarj istasyonunu IR ile kendisi bulur**, yerleşir ve şarj kendiliğinden başlar.
4. Sabit duran bir **akıllı telefon** görevi kamerayla izler, ölçer ve **geçti/kaldı raporu** üretir.

**Temel ilke:** Yöntem değil sonuç puanlanır. Pahalı donanım, marka ya da görünüş puan getirmez.

### Değişmez kurallar
- Yalnız iki teker kullanılır. Üçüncü teker, ayak ya da zemine değen bir destek olamaz.
- Robot enerjisizken ±10°'den bırakıldığında 5 s içinde kendiliğinden dik **durmamalı**. Yani denge pasif olamaz, aktif olmalı.
- Gerilim en fazla 24 V DC olabilir. Robot kendi bataryasıyla çalışır ve şarj için kimse elle kablo takmaz.
- Zorunlu güvenlik öğeleri: acil durdurma, BMS'li lityum paket, motor hattında sigorta.
- Hazır, komple bir denge robotu kiti kullanılamaz.
- Kutu kapalı ve kırılmaz olmalı. Görev boyunca robotta sabit kalmalı ama gövdeye monte edilmemeli.
- Final değerlendirmesi başladıktan sonra firmware, parametre, mekanik ve kablolama değiştirilemez.
- Şarj LED'i donanımsal CHG/STAT sinyaliyle yanmalı. Yazılımla yakılamaz.

---

## 2. Puanlama (100 puan = 5 ölçüt × 4 alt test × 5 puan)

Her alt test ya **geçti = 5** ya **kaldı = 0** olarak puanlanır. Ara puan yoktur.

| Ölçüt | Alt testler | Sorumlu |
|---|---|---|
| **Ö1 Denge ve hareket** | 30 s statik denge · 1 m ileri + 3 s dur · 1 m geri + 3 s dur · ±10°'den 10 s içinde toparlanma | Kontrol |
| **Ö2 Eldiven** | İleri 0,5 m · Geri 0,5 m · Sola 90° · Sağa 90° (her biri + dur, 2 s içinde tepki) | Biyomedikal + Elektronik |
| **Ö3 Otomatik şarj** | İstasyonu algıla ve yönel · Otonom yerleş · 5 s içinde şarj başlasın ve LED yansın · Robot çekilince 2 s içinde sönsün, geri gelince 5 s içinde yansın | Elektrik + Kontrol |
| **Ö4 Mobil uygulama** | 1 Hz şarj ve eğim verisi · Kameradan hareket ve yön tespiti · Otomatik değerlendirme · Değiştirilemez rapor | **Bilgisayar (Recep)** |
| **Ö5 Bütünleşik görev** | Tam görev × 4 tekrar | Herkes. Kayıtları uygulama üretir. |

**Ortak test protokolü:** Zemin düz, sert ve kuru olur. Kutu düşerse alt test kalır. Robota dokunulursa ya da zemine teker dışında bir parça değerse alt test kalır. Süre jüri kronometresiyle ölçülür, mesafeyi uygulama ölçer.

---

## 3. Rol dağılımı

| Bölüm | Modül | Sorumluluk |
|---|---|---|
| Kontrol ve Otomasyon | A, C (yaklaşma) | Durum kestirimi, denge kontrolcüsü, hareket, yükle kararlılık, IR ile istasyona yaklaşma |
| Elektrik | C | Batarya/BMS/sigorta/acil stop, şarj devresi, istasyon güç katı, temas pedleri, CHG/STAT LED |
| Elektronik ve Haberleşme | B (donanım) | Eldiven elektroniği, eldiven→robot ESP-NOW bağlantısı, robot tarafı alıcı |
| Biyomedikal | B (sinyal) | IMU/EMG seçimi, kalibrasyon, jestten 5 komuta dönüşüm, ergonomi |
| **Bilgisayar (Recep)** | **D** | Android uygulaması, BLE iletişimi, kamera takibi, otomatik değerlendirme, rapor. Ayrıca **robot tarafı BLE telemetri modülü**. |

### Sınır (netleştirildi)
- **Ben yapıyorum:** mobil uygulamanın tamamı, robottaki ESP32'nin **sadece BLE telemetri gönderme kodu** ve bir **simülatör ESP32**.
- **Ben yapmıyorum:** denge ve motor kontrolü, docking algoritması, eldiven firmware'i.
- **Kontrol ekibinden beklenen:** kendi kodlarında `batarya_%`, `eğim`, `şarj_flag` ve `durum` değişkenlerini güncel tutmaları. Gönderme işini benim modülüm yapar.
- **Elektrik ekibinden beklenen:** CHG/STAT pinini LED'e ek olarak robotun ESP32'sinde bir GPIO'ya da bağlamaları (bkz. Açık Soru #5) ve batarya gerilimini ölçmek için bir ADC bölücü koymaları.

---

## 4. Sistem mimarisi ve iletişim

```
 Eldiven (ESP32-C3 + IMU/EMG)
          │  ESP-NOW (komut: İLERİ/GERİ/SOL/SAĞ/DUR)
          ▼
 Robot ESP32 ──────── IR alıcılar ◄── Şarj istasyonu (IR verici + CHG/STAT LED)
          │  BLE notify, 1 Hz (telemetri)
          ▼
 Android uygulaması ◄── Telefon kamerası (ArUco takibi)
          │
          ▼
 Rapor (Room DB + video/log + SHA-256 zinciri + PDF)
```

- Uygulama **yalnızca robot ESP32'si ile, BLE üzerinden** konuşur.
- Eldiven uygulamaya bağlanmaz.
- ESP32 üzerinde ESP-NOW ve BLE aynı anda çalışabilir (WiFi/BT coexistence).

---

## 5. Arayüz Kontrol Dokümanı (ICD) – BLE telemetri

> Bu bölüm ekiple paylaşılacak. Formatta değişiklik olursa önce burası güncellenir.

**Cihaz adı:** `CDTP-ROBOT`
**Servis UUID:** `8f3a0001-5c2e-4b7a-9d10-3e6f2a1b0c01`
**Telemetri karakteristiği (NOTIFY):** `8f3a0002-5c2e-4b7a-9d10-3e6f2a1b0c01`
**Ping/Komut karakteristiği (WRITE, opsiyonel):** `8f3a0003-5c2e-4b7a-9d10-3e6f2a1b0c01`

UUID'ler bu proje için seçilmiş örnek değerlerdir ve proje boyunca sabit kalır.

### Telemetri paketi (11 byte, little-endian, 1 Hz)
| Offset | Alan | Tip | Açıklama |
|---|---|---|---|
| 0 | `seq` | uint16 | Her pakette +1 artar. Paket kaybını tespit etmek için. |
| 2 | `t_ms` | uint32 | Robotun açılıştan bu yana geçen süresi (ms) |
| 6 | `battery_pct` | uint8 | 0–100 |
| 7 | `tilt_x10` | int16 | Eğim × 10 (ör. 1,4° → 14) |
| 9 | `charging` | uint8 | 0/1, CHG/STAT pininden okunur |
| 10 | `state` | uint8 | 0 BEKLE · 1 MANUEL · 2 DOCKING · 3 ŞARJDA · 4 HATA |

### Kurallar
- Gönderim periyodu 1000 ms ±50 ms.
- Uygulama tarafında **son paketten bu yana geçen süre > 2 s** olursa "BAĞLANTI KOPTU" gösterilir, olay log'a yazılır ve otomatik yeniden bağlanma başlar.
- Telemetri değişkenleri ESP32 kodunda global ya da thread-safe olmalı. Kontrol döngüsü bunları yazar, BLE task'ı okur.

### Alternatif taşıma yolları (robot MCU'su ESP32 olmazsa)
Paket içeriği aynı kalır (11 byte). Uygulamada bağlantı türü seçilir.
- **Klasik Bluetooth SPP (HC-05/HC-06, ESP32 BluetoothSerial):** akış olduğu için çerçevelenir: `A5 5A` + 11 byte + `XOR(11 byte)` = 14 byte. Telefonla önceden eşleştirilir.
- **Wi-Fi UDP (ESP32 Wi-Fi, Raspberry Pi):** port **4210**, telefonun IP'sine ya da 255.255.255.255'e. Datagram ham 11 byte veya 14 byte çerçeve olabilir.

---

## 6. Malzeme listesi (öneri)

| Grup | Parça | Not |
|---|---|---|
| Robot MCU | ESP32-WROOM-32 DevKit V1 | BLE + WiFi |
| IMU | MPU6050 (GY-521) veya BNO055 | |
| Motor | 2 × JGB37-520 12V enkoderli redüktörlü motor | 200–330 rpm |
| Sürücü | TB6612FNG veya Cytron MDD3A | L298N kullanılmayacak |
| Teker | 65–85 mm teker + 6 mm kaplin | |
| Batarya | 3S 18650 Li-ion paket + 3S 20A BMS | |
| Regülatör | LM2596 / MP1584 buck | 5 V |
| Güvenlik | Bıçak sigorta + yuva, acil stop butonu | |
| Şarj | CN3722 şarj modülü (CHRG/DONE çıkışlı), pogo pin/yaylı kontak, mıknatıs | |
| IR | İstasyonda 940 nm IR LED (38 kHz), robotta 2–3 × TSOP38238 / VS1838B | |
| Eldiven | ESP32-C3 SuperMini + MPU6050/MPU9250 (opsiyonel MyoWare 2.0) + 1S LiPo + TP4056 | |
| Şasi | 3B baskı PETG/PLA veya pleksi/MDF + M3 aralayıcı | |
| **Benim için** | Android telefon, tripod, basılı ArUco marker'lar, **ekstra 1 × ESP32 DevKit (simülatör)** | |

**Nereden alınır:**
- Robotistan, Direnc.net, Robolink Market, Motorobit, Samm Market
- Entegreler için Özdisan / Komponentci
- AliExpress daha ucuz ama 2–4 hafta sürer

**Bütçe:** hedef ~9.500 TL (aralık 6.150–13.400 TL). Talep edilirse Raspberry Pi hibe ediliyor, ama kullanımı zorunlu değil.

---

## 7. MODÜL D – Mobil Uygulama (detaylı)

### 7.1 Gereksinimler (şartnameden)
1. Robotla kablosuz bağlantı kurulduğunu doğrulamak. Şarj ve eğim verisi **1 Hz** ile canlı gösterilir ve bağlantı **2 s'den fazla kopmaz**.
2. Kullanıcı görevi seçer: **Statik Denge / Yönlendirme / Şarj / Bütünleşik**.
3. **"Testi Başlat"** butonu başlangıç zaman damgasını alır ve kamera kaydını başlatır.
4. Test sırasında canlı gösterilenler:
   - konum
   - **hareket durumu** (hareket ediyor / dönüyor / statik denge / toparlanma)
   - **yön** (ileri / geri / sağ / sol)
   - şarj
   - eğim
   - sayaç
5. Test, **"Testi Bitir"** butonuyla ya da otomatik bitiş koşuluyla sonlanır.
6. Otomatik değerlendirme şu verilere dayanır:
   - süre (±2 s)
   - mesafe (0,8–1,2 m)
   - yön
   - son konum
   - kutunun durumu
7. Sonuç; video veya zaman damgalı kayıt, ölçülen değerler ve geçti/kaldı kararıyla **elle değiştirilemeyecek** biçimde saklanır.
8. Şarj durumu üç kaynaktan doğrulanır:
   - kamera (robotun istasyona göre konumu)
   - eğim (dock'a yaslanma)
   - şarj/pil bilgisi

### 7.2 Teknoloji yığını
| İş | Teknoloji |
|---|---|
| Dil / UI | Kotlin + Jetpack Compose (Material 3) |
| Mimari | MVVM, Coroutines + StateFlow, (opsiyonel) Hilt |
| BLE | Nordic Android-BLE-Library (veya ham Android BLE API) |
| Kamera | CameraX: Preview + ImageAnalysis + VideoCapture |
| Görüntü işleme | OpenCV Android SDK 4.x, `org.opencv.objdetect.ArucoDetector` |
| Veritabanı | Room |
| Bütünlük | SHA-256 (`MessageDigest`), hash zinciri |
| Rapor | `PdfDocument` ile PDF, paylaşım için FileProvider |
| Grafik | Vico (canlı eğim grafiği, opsiyonel) |
| Min SDK | 26+ (Android 8). Hedef: güncel SDK. |
| Robot/simülatör | PlatformIO + ESP32 Arduino Core + NimBLE-Arduino |

### 7.3 Proje (paket) yapısı
```
app/src/main/java/.../cdtp/
├── ble/
│   ├── BleScanner.kt          # CDTP-ROBOT'u bulur
│   ├── RobotBleManager.kt     # bağlan, notify'a abone ol, yeniden bağlan
│   ├── TelemetryParser.kt     # 11 byte → Telemetry data class
│   └── ConnectionWatchdog.kt  # >2 s paket yok → kopma olayı
├── camera/
│   ├── CameraController.kt    # CameraX use-case'leri
│   └── FrameAnalyzer.kt       # ImageAnalysis → vision pipeline
├── vision/
│   ├── ArucoTracker.kt        # marker tespiti (robot, kutu, zemin, istasyon)
│   ├── FloorCalibration.kt    # homografi: piksel → metre
│   ├── PoseFilter.kt          # hareketli ortalama / Kalman
│   └── MotionClassifier.kt    # hareket durumu + yön
├── task/
│   ├── TaskType.kt            # STATIC_BALANCE, GUIDANCE, CHARGING, INTEGRATED
│   ├── TestSession.kt         # başlangıç/bitiş, zaman damgası, sayaç, veri akışı
│   ├── evaluators/            # her görev için bir Evaluator
│   └── EvalConfig.kt          # eşikler (finalden önce dondurulacak)
├── report/
│   ├── db/                    # Room: TestRecord, TelemetrySample, EventLog
│   ├── HashChain.kt           # prevHash + kayıt → hash
│   └── PdfExporter.kt
└── ui/
    ├── connect/               # Bağlantı ekranı
    ├── calibration/           # Kamera/zemin kalibrasyonu
    ├── taskselect/            # Görev seçimi
    ├── livetest/              # Canlı test ekranı
    ├── result/                # Sonuç ekranı
    └── history/               # Kayıtlar + PDF dışa aktar + zincir doğrula
```

### 7.4 Ekranlar
1. **Bağlantı:** tara → `CDTP-ROBOT` → bağlan. Canlı batarya %, eğim, şarj, son paket yaşı (ms) ve paket kaybı sayacı gösterilir.
2. **Kalibrasyon:** kamera görüntüsünde zemin marker'larının tespiti ve homografinin hesaplanması. "Kalibrasyon OK" göstergesi.
3. **Görev seç:** 4 görev kartı.
4. **Canlı test:**
   - kamera önizlemesi + robot/kutu marker overlay'i + iz çizgisi
   - büyük sayaç, durum etiketi (HAREKET / DÖNÜŞ / STATİK / TOPARLANMA), yön etiketi
   - telemetri paneli
   - Başlat / Bitir butonları
5. **Sonuç:** GEÇTİ/KALDI kararı, ölçülen değerler, gerekçe (hangi kural geçti ya da kaldı). **Düzenleme butonu yok.**
6. **Kayıtlar:** liste, detay, PDF dışa aktar, "zinciri doğrula" butonu.

### 7.5 Görüntü işleme yaklaşımı (ArUco)
- **Sözlük:** `DICT_4X4_50`
- **Marker atamaları:**
  - ID 0 → robotun üstü (yatay, kameraya bakacak şekilde)
  - ID 1 → ilaç kutusunun üstü
  - ID 10–13 → zemin referansı. 4 köşe, aralarındaki mesafe bilinir (ör. 1,5 m × 1,0 m dikdörtgen).
  - ID 20 → şarj istasyonu
- **Telefon konumu:** yüksek ve sabit (tripod), alanı yukarıdan eğik açıyla görür. Test sırasında telefon kesinlikle oynamaz.
- **Piksel → metre:** 4 zemin marker'ının merkezlerinden homografi hesaplanır (`Calib3d.findHomography`).
- **Yükseklik hatası (paralaks):** robot marker'ı zeminden h kadar yüksekte olduğu için ölçüm biraz sapar. Çözüm seçenekleri:
  - (a) Kalibrasyonda zemin marker'larını robot marker'ıyla aynı yüksekliğe koymak (eşit boyda takozlar üzerine)
  - (b) Satranç tahtasıyla kamera kalibrasyonu yapıp `solvePnP` ile 3B konum hesaplamak
  - **Önce (a) ile başla.**
- **Heading:** robot marker'ının köşelerinden yön vektörü çıkar. Marker'ın "ileri" kenarı robotun ön tarafına bakacak şekilde yapıştırılır.
- **Filtre:** konum ve heading için hareketli ortalama (5–10 kare), gerekirse basit bir Kalman filtresi.
- **Hedef FPS:** analiz 15–30 fps. ImageAnalysis'te `STRATEGY_KEEP_ONLY_LATEST` kullanılır.

### 7.6 Hareket ve yön sınıflandırma
Pencere: son ~0,5 s. Hız v (m/s), açısal hız ω (°/s), eğim θ (telemetriden).

| Durum | Koşul (başlangıç eşikleri, sahada ayarlanacak) |
|---|---|
| STATİK DENGE | \|v\| < 0,03 m/s ve \|ω\| < 10°/s ve \|θ\| < 5° |
| HAREKET | \|v\| ≥ 0,03 m/s ve \|ω\| < 20°/s |
| DÖNÜŞ | \|ω\| ≥ 20°/s |
| TOPARLANMA | \|θ\| ≥ 5° iken azalma eğiliminde |

**Yön:**
- `v · heading > 0` → İLERİ, `< 0` → GERİ
- `ω > 0` → SOL, `< 0` → SAĞ. İşaret kamera yönüne göre teyit edilecek.

### 7.7 Görev değerlendirme kuralları
Bütün eşikler `EvalConfig` içinde tutulur. **12. haftada dondurulur.**

**Statik Denge (Modül A)**
- Başlat'tan itibaren 30 s (±2 s) boyunca:
  - toplam yer değiştirme < 0,10 m
  - durum hiç "devrildi" olmaz (\|θ\| < 30°)
  - kutu robotta
- Geçerse GEÇTİ.

**Yönlendirme (Modül B)**
- Başlangıç ve son konum farkından net mesafe çıkarılır.
- Koşullar:
  - mesafe 0,8–1,2 m
  - baskın yön, beklenen yönle aynı
  - sonda STATİK
  - kutu robotta
- ⚠️ Ö2 komut başına 0,5 m istiyor, uygulama tablosu 0,8–1,2 m diyor. Mesafe aralığı parametrik tutulacak (bkz. Açık Soru #1).

**Şarj Etme (Modül C)**
- Docking anı = robot marker'ı istasyon marker'ına < X cm (kalibrasyonla belirlenecek) **ve** robot STATİK.
- Bu andan sonra **5 s içinde** `charging == 1` olmalı.
- Eğim telemetrisi dock'a yaslanma açısıyla tutarlı olmalı (opsiyonel destek kontrolü).

**Bütünleşik Görev (Modül E)**
Durum makinesi, sırayla:
1. 5 s statik
2. hareket (~1 m + dönüş)
3. hedefte 5 s statik
4. istasyona yaklaşma
5. docking
6. 5 s içinde şarj

Kutu baştan sona robotta olmalı. Herhangi bir adım zaman aşımına uğrarsa sonuç KALDI olur ve gerekçesi yazılır.

**Kutu düştü tespiti:**
- Kutu marker'ı (ID 1) ile robot marker'ı (ID 0) arasındaki mesafe > 0,15 m, **veya**
- kutu marker'ı > 1 s görünmüyor, **ve** robot marker'ı görünüyor

Bu durumda KUTU DÜŞTÜ olarak işaretlenir.

### 7.8 Rapor ve değiştirilemezlik
Her test bir `TestRecord` üretir. İçeriği:
- id, görev tipi, başlangıç/bitiş zaman damgası, süre
- ölçülen değerler (mesafe, yön, son konum, kutu durumu, şarj başlama gecikmesi)
- karar + gerekçe
- telemetri örnekleri (1 Hz) + olay log'u (kopmalar, durum değişimleri)
- video dosya yolu + **video SHA-256**
- `prevHash`, `recordHash = SHA256(prevHash + kanonik JSON(kayıt))`

Kurallar:
- Uygulamada **güncelleme ve silme yolu yok**. DAO yalnızca `insert` ve `select` içerir.
- "Zinciri doğrula" butonu bütün hash'leri yeniden hesaplar. Tek bir byte bile değiştiyse kırılma gösterilir.
- PDF raporda karar, değerler, hash'ler ve kısa bir zaman çizelgesi yer alır.
- Opsiyonel: Firebase'e append-only kurallarla yedekleme.

Şartname "video **veya** zaman damgalı kayıt" diyor. Video kaydı performans sorunu çıkarırsa zaman damgalı log yeterli.

### 7.9 Robot tarafı BLE modülü + simülatör (ESP32)
- `telemetry_ble.h/.cpp`: NimBLE ile servis ve karakteristik oluşturur, 1 Hz FreeRTOS task'ı ile notify gönderir.
- Kontrol ekibinin kullanacağı API:
  ```cpp
  telemetry_begin();
  telemetry_set(battery_pct, tilt_deg, charging, state);
  ```
- **Simülatör:** aynı modül + sahte veri üreteci.
  - Eğim sinüs + gürültü olarak üretilir.
  - Batarya yavaşça azalır.
  - Kart üzerindeki BOOT butonu `charging` değerini toggle eder.
- Uygulama ilk aşamada tamamen simülatörle geliştirilir.

### 7.10 Test planı (benim modülüm)
| Test | Yöntem | Kabul |
|---|---|---|
| 1 Hz telemetri | 10 dk simülatörle, log analizi | Ortalama periyot 1 s ±50 ms, kayıp < %1 |
| Kopma toleransı | 10 dk boyunca en büyük paket aralığı | Hiçbir aralık 2 s'yi geçmiyor |
| Mesafe doğruluğu | Elle taşınan marker, şerit metreyle 0,5 / 1,0 / 1,5 m | Hata < ±5 cm |
| Yön/durum | Elle ileri, geri, dönüş, bekleme | Doğru etiket, < 0,5 s gecikme |
| Kutu düştü | Kutu marker'ını kaldır | 1–2 s içinde tespit |
| Zincir | DB dosyasını dışarıdan değiştir | Doğrulama kırılmayı yakalıyor |
| Bütünleşik | Gerçek robotla 4 tekrar | 4/4 doğru karar |

---

## 8. Yol haritası (Modül D)

### Faz 0 – Kurulum (Hafta 1–2)
- [ ] ICD'yi ekiple paylaş ve onaylat (Bölüm 5)
- [ ] Danışmana açık soruları sor (Bölüm 10)
- [ ] Git reposu: `cdtp-mobile` (Android) + `cdtp-firmware-telemetry` (ESP32)
- [ ] Simülatör ESP32 için kart al

### Faz 1 – BLE (Hafta 2–3)
- [ ] ESP32 simülatör: NimBLE servis + 1 Hz notify
- [ ] Android: izinler (BLUETOOTH_SCAN/CONNECT), tarama, bağlanma
- [ ] Telemetri parser + canlı panel
- [ ] Watchdog + otomatik yeniden bağlanma + olay log'u

### Faz 2 – Kamera ve görüntü (Hafta 3–5)
- [ ] CameraX önizleme + ImageAnalysis
- [ ] OpenCV entegrasyonu, ArUco tespiti, overlay
- [ ] Zemin kalibrasyonu (homografi), metre cinsinden konum
- [ ] Heading + filtre + iz çizimi

### Faz 3 – Görev ve değerlendirme (Hafta 5–8)
- [ ] TestSession: Başlat/Bitir, zaman damgası, sayaç
- [ ] MotionClassifier (durum + yön)
- [ ] 4 Evaluator + EvalConfig
- [ ] Kutu düştü tespiti
- [ ] Video kaydı (VideoCapture) – analiz ile birlikte performans testi

### Faz 4 – Rapor (Hafta 8–9)
- [ ] Room şeması (insert-only DAO)
- [ ] Hash zinciri + doğrulama ekranı
- [ ] PDF dışa aktarma, kayıtlar ekranı

### Faz 5 – Robot entegrasyonu (Hafta 9–11)
- [ ] Telemetri modülünü robot ESP32'sine taşı, kontrol ekibiyle birleştir
- [ ] CHG/STAT → GPIO, batarya ADC → yüzde dönüşümü (Elektrik ekibiyle)
- [ ] Gerçek robotla eşik ayarı

### Faz 6 – Prova (Hafta 11–13)
- [ ] Rubrikteki 20 alt testin tamamını uygulama ile kaydederek prova et
- [ ] **12. hafta: EvalConfig ve firmware DONDURULUR**
- [ ] Final günü kontrol listesi: telefon şarjı, tripod, marker'lar, kalibrasyon, yedek telefon

---

## 9. Genel ekip yapılacakları
- [ ] İlk hafta: roller, ICD ve ortak repo/klasör
- [ ] Parça siparişi **erken** (kargo riski)
- [ ] Haftalık entegrasyon toplantısı ("bende çalışıyor" yetmez)
- [ ] En riskli işlere erken başla:
  - kutu yüklüyken denge
  - dengedeki robotu istasyona yerleştirme (dock'a yaslanma yuvası)
  - Ö5'te 4/4 tekrarlanabilirlik
- [ ] 10. haftadan itibaren rubriği test listesi gibi düzenli dene
- [ ] Li-ion güvenliği: BMS, sigorta, acil stop
- [ ] Raporda her öğrencinin somut katkısı görünsün

---

## 10. Danışmana sorulacak açık sorular
1. **Yönlendirme mesafesi:** Ö2'de komut başına ~0,5 m, uygulama görev tablosunda 0,8–1,2 m isteniyor. Uygulamadaki "Robot Yönlendirmesi" görevi hangi mesafeyle değerlendirilecek?
2. Robot ve kutu üzerine **ArUco marker** yapıştırmak kabul edilir mi?
3. "Video **veya** zaman damgalı kayıt" ifadesine göre yalnızca log yeterli mi, yoksa video şart mı?
4. Uygulamanın yalnızca **Android** olması sorun mu?
5. Şarj göstergesi donanımsal olacak. Aynı CHG/STAT sinyalini MCU'nun okuyup uygulamaya bildirmesi kurala uygun mu? (Uygun olmalı, çünkü LED'i yazılım sürmüyor.)

---

## 11. Notlar / kararlar günlüğü
- 2026-10-14: Modül D sınırı netleşti. Mobil uygulamanın tamamı + robottaki BLE telemetri modülü bende. Denge, motor, docking ve eldiven firmware'i bende değil.
- 2026-10-14: Teknoloji: Kotlin + Compose + CameraX + OpenCV ArUco + Room. Robot iletişimi BLE (NimBLE).
- 2026-10-14: İlk sürüm yazıldı (`cdtp-mobile`, `cdtp-firmware-telemetry`, `tools`, `markers`). Faz 1–4'ün kodu var, 20 birim testi geçiyor; gerçek telefonda kamera testi bekliyor. Kararlar:
  - Bağlantı katmanı taşımadan bağımsız: BLE + Klasik BT (SPP) + Wi-Fi UDP + uygulama içi simülatör (ICD'ye "Alternatif taşıma yolları" eklendi).
  - Telemetri örnekleri, olay log'u ve yörünge ayrı tablolarda değil, hash'lenen kayıt JSON'unun içinde. Böylece hepsi zincirle korunuyor.
  - Başlatılan test iptal edilemez, her test kayda dönüşür.
  - Yönlendirme mesafesi iki ön ayarlı: ~1 m (0,8–1,2) ve ~0,5 m (0,3–0,7) (Açık Soru #1 netleşene kadar).
  - Ek görev: Toparlanma (Ö1.4, ±10° → 10 s). Şarj görevinde Ö3.4 için "çek/geri getir" modu var.
  - Proje yolundaki "Ç" yüzünden Gradle çıktıları `~/.cdtp-build` altına yönlendirildi.
