# ÇDTP Modül D – Mobil Uygulama Rehberi

**Proje:** İki Tekerlekli Ev İçi Hasta Destek Robotu · YTÜ EEF · 2026-2027 Güz
**Modül:** D – Mobil izleme ve değerlendirme (Bilgisayar Mühendisliği)
**Sürüm:** 0.1.0 · 14.10.2026

Bu belge dört şeyi anlatır: ne yapıldığını ve nasıl yapıldığını, şartnamedeki maddelerin nasıl karşılandığını, uygulamanın başka telefonlara nasıl yüklenip güncelleneceğini ve nasıl kullanılacağını.
Tasarım ayrıntıları ve ICD için [CDTP_Proje_Plani.md](CDTP_Proje_Plani.md), kısa teknik özet için [README.md](README.md) dosyasına bakın.

---

## İçindekiler
1. [Kısa özet](#1-kısa-özet)
2. [Ne yapıldı](#2-ne-yapıldı)
3. [Nasıl yapıldı (mimari ve kararlar)](#3-nasıl-yapıldı)
4. [Şartnameyi karşılama](#4-şartnameyi-karşılama)
5. [Neler test edildi, neler bekliyor](#5-test-durumu)
6. [Uygulamayı telefona yükleme ve güncelleme](#6-yükleme-ve-güncelleme)
7. [Geliştirme ortamı ve derleme](#7-geliştirme-ortamı-ve-derleme)
8. [Kullanım kılavuzu](#8-kullanım-kılavuzu)
9. [ESP32 / robot bağlantısı](#9-esp32--robot-bağlantısı)
10. [Sorun giderme](#10-sorun-giderme)
11. [Final günü kontrol listesi](#11-final-günü-kontrol-listesi)
12. [Kalan işler](#12-kalan-işler)
13. [Dosya haritası](#13-dosya-haritası)

---

## 1. Kısa özet

Android uygulaması üç iş yapar:
- Robotun **1 Hz şarj ve eğim** verisini kablosuz alır.
- Sabit duran telefonun **kamerasıyla** robotu izler: konum, hareket durumu ve yön.
- Seçilen görevi **otomatik olarak GEÇTİ/KALDI** diye değerlendirir ve sonucu **sonradan değiştirilemeyen** bir kayda yazar. Kayıt video, SHA-256 hash zinciri ve PDF rapordan oluşur.

Robot tarafında ESP32 için hazır bir **BLE telemetri modülü** vardır. Kontrol ekibi bu modülü iki fonksiyonla kullanır. Donanım yokken denemek için bir **simülatör** de hazırdır: ESP32 kartı ya da uygulama içi.

---

## 2. Ne yapıldı

| Parça | Konum | Açıklama |
|---|---|---|
| Android uygulaması | `cdtp-mobile/` | Kotlin + Jetpack Compose. 8 ekran: Ana, Bağlantı, Kalibrasyon, Görev Seç, Canlı Test, Sonuç, Kayıtlar, Ayarlar |
| Robot BLE modülü | `cdtp-firmware-telemetry/lib/telemetry_ble/` | ESP32 + NimBLE, tam 1 Hz gönderim, thread-safe |
| ESP32 simülatörü | `cdtp-firmware-telemetry/src/sim/` | Sahte eğim ve batarya üretir. BOOT butonu = şarj, seri komutla kopma ve itme simülasyonu |
| Robot entegrasyon örneği | `cdtp-firmware-telemetry/src/robot_example/` | Kontrol ve Elektrik ekipleri için şablon: CHG/STAT pini ve batarya ADC okuma |
| HC-05 örneği | `cdtp-firmware-telemetry/examples/arduino_hc05/` | Robot MCU'su ESP32 olmazsa Arduino + Klasik Bluetooth |
| Marker'lar | `markers/cdtp_aruco_markers.pdf` | Yazdırılacak 7 ArUco marker, her biri ayrı A4 sayfada |
| Test posteri | `markers/ekran_test_posteri.png` | Bütün marker'lar tek resimde; ekrandan masa başı deneme için |
| Araçlar | `tools/` | Marker üretici, UDP simülatörü, zincir doğrulayıcı, telemetri CSV analizi |
| Hazır APK | `apk/cdtp-izleme-0.1.0.apk` | Telefona doğrudan yüklenecek dosya |

---

## 3. Nasıl yapıldı

### 3.1 Genel akış

```
Robot ESP32 ──(BLE notify, 1 Hz, 11 byte)──► Telefon: TelemetryRepository ─┐
                                                                           │
Telefon kamerası ─► CameraX ─► OpenCV ArUco ─► Homografi (piksel→metre) ─► │
                    (25-30 fps)  ─► Filtre ─► Hareket sınıflandırma ───────┤
                                                                           ▼
                                            TestController (10 Hz örnekleme)
                                                      │
                                         Görev değerlendirici (5 adet)
                                                      │
                                Room veritabanı (insert-only) + SHA-256 zinciri
                                                      │
                                         Sonuç ekranı · PDF · JSON dışa aktarma
```

### 3.2 Kullanılan teknolojiler

| İş | Teknoloji |
|---|---|
| Dil ve arayüz | Kotlin, Jetpack Compose (Material 3) |
| Kamera | CameraX: Preview + ImageAnalysis + VideoCapture, hepsi 4:3 |
| Görüntü işleme | OpenCV 4.10 ArUco, `DICT_4X4_50` sözlüğü |
| Veritabanı | Room, yalnız INSERT ve SELECT |
| Bütünlük | SHA-256 hash zinciri, video SHA-256 |
| Rapor | Android `PdfDocument` |
| Robot tarafı | ESP32 Arduino + NimBLE-Arduino, PlatformIO |
| Min. Android | 8.0 (API 26). Test edilen: Android 15, realme 11 |

### 3.3 Önemli tasarım kararları

1. **Bağlantı türünden bağımsız telemetri.** Uygulama her durumda aynı 11 byte'lık paketi bekler. Bağlantı türü ekrandan seçilir: BLE (ESP32), Klasik Bluetooth (HC-05/06), Wi-Fi UDP (Raspberry Pi / ESP32 Wi-Fi) ya da simülatör. Robotun donanımı değişse de uygulama değişmez.
2. **2 s watchdog.** Son paketten bu yana 2 saniyeden fazla geçerse ekranda "BAĞLANTI KOPTU" yazar, olay log'a geçer ve uygulama otomatik yeniden bağlanır. Paket kaybı, ortalama periyot ve en büyük aralık canlı ölçülür.
3. **Kamera ile metre cinsinden ölçüm.** Zemine konan 4 marker'ın merkezleri ile şerit metreyle ölçülen dikdörtgen eşlenir ve bir homografi hesaplanır. Robot marker'ının köşelerinden hem konum hem yön (heading) çıkar.
4. **Hareket sınıflandırma.** Son 0,5 s'lik pencerede hız (v), açısal hız (ω) ve telemetriden gelen eğim (θ) kullanılır. Sonuç STATİK DENGE, HAREKET, DÖNÜŞ, TOPARLANMA veya DEVRİLDİ olur. Yön İLERİ, GERİ, SOL ya da SAĞ.
5. **Kutu düştü tespiti.** Kutu marker'ı robottan 15 cm'den uzaktaysa ya da robot görünürken kutu 1 s'den uzun süre görünmüyorsa kutu düşmüş sayılır.
6. **Değiştirilemez kayıt.**
   - Her test bir JSON kaydı üretir. Kayıt ölçümleri, kuralları, telemetri örneklerini, olayları, yörüngeyi, kalibrasyonu, eşiklerin kopyasını ve video hash'ini içerir.
   - `recordHash = SHA256(önceki kaydın hash'i + kayıt JSON'u)`. Tek bir byte değişirse zincir kırılır.
   - Uygulamada güncelleme ya da silme yolu yoktur. Başlatılan her test mutlaka kayda dönüşür; iptal butonu yoktur.
   - Zincir, jüri bilgisayarında `tools/verify_chain.py` ile telefondan bağımsız doğrulanabilir.
7. **Eşiklerin dondurulması.** Bütün eşikler Ayarlar ekranındadır. 12. haftada "DONDUR" denince bir daha değiştirilemez. Her kayıt o anki eşiklerin hash'ini taşır.
8. **Video yerine log yedeği.** Bir cihaz video ile analizi aynı anda desteklemezse uygulama video olmadan devam eder ve zaman damgalı kayıt üretir. Şartname de "video **veya** zaman damgalı kayıt" diyor.

---

## 4. Şartnameyi karşılama

### 4.1 Puanlama tablosu – Ö4 Mobil görsel ölçüm ve değerlendirme (20 puan)

| Alt test | Şartname | Uygulamadaki karşılığı |
|---|---|---|
| Ö4.1 | Kablosuz iletişim, şarj + eğim canlı, 1 Hz | Bağlantı ekranında canlı batarya, eğim, şarj ve durum. Periyot, kayıp, aralık ve kopma istatistikleri. 2 s watchdog |
| Ö4.2 | Hareket ve yön bilgisi kameradan, anlık | Canlı test ekranında büyük durum etiketi (HAREKET / DÖNÜŞ / STATİK / TOPARLANMA) ve yön (İLERİ / GERİ / SOL / SAĞ) |
| Ö4.3 | Seçilen görevin başarısı otomatik değerlendirilir | 5 değerlendirici. Kural kural ✓/✗ ve gerekçe |
| Ö4.4 | Video veya zaman damgalı kayıtla rapor, sonradan değiştirilemez | Video + SHA-256, hash zinciri, insert-only veritabanı, PDF |

### 4.2 Şartname 5.2 – Mobil uygulama maddeleri

| Madde | Karşılığı |
|---|---|
| Telefon sabit konumlandırılır | Kalibrasyon + "KAMERA OYNADI" uyarısı |
| Kablosuz bağlantı doğrulanır, 1 Hz, kopma ≤ 2 s | Bağlantı ekranı + watchdog + istatistikler |
| Kullanıcı görevi seçer | Görev Seç: Statik Denge, Robot Yönlendirmesi, Şarj Etme, Bütünleşik Görev (+ ek: Toparlanma) |
| Şarj durumu kamera + eğim + pil bilgisiyle ölçülür | Şarj görevi üç kaynağı birlikte kullanır: istasyona uzaklık, yerleşmedeki eğim, CHG bayrağı ve batarya |
| "Testi Başlat" = başlangıç zaman damgası + kamera kaydı | Var |
| Konum, hareket, şarj, eğim canlı + sayaç | Canlı test ekranı |
| "Testi Bitir" ya da otomatik bitiş | İkisi de var; her görevin kendi bitiş koşulu var |
| Süre (±2 s), mesafe (0,8–1,2 m), yön, son konum, kutu durumu | Her kayıtta bu beş ölçüm var |
| Sonuç değiştirilemez biçimde saklanır | Hash zinciri + video hash + PDF |

### 4.3 Görevler ve geçme kuralları (varsayılan eşikler)

| Görev | Geçmek için | Otomatik bitiş |
|---|---|---|
| **Statik Denge** (Modül A) | Süre 30 s ±2. Başlangıçtan sapma < 0,10 m. \|θ\| < 30°. Kutu robotta. Robot karelerin ≥ %70'inde görünmüş | 30 s dolunca |
| **Yönlendirme – İleri/Geri** (Modül B) | Net mesafe 0,8–1,2 m (ya da ~0,5 m ön ayarı: 0,3–0,7). Baskın yön doğru. Sonda STATİK. Kutu robotta | Robot hareket edip 3 s durunca |
| **Yönlendirme – Sol/Sağ** | Dönüş 60–120°, doğru yönde. Sonda STATİK. Kutu robotta | Aynı |
| **Şarj Etme** (Modül C) | Robot istasyona < 25 cm ve STATİK (yerleşme). Bundan sonra ≤ 5 s içinde şarj=1 | Şarj doğrulanınca |
| **Şarj – çek/geri getir** (Ö3.4) | Ek olarak: çekilince ≤ 2 s içinde şarj=0, geri gelince ≤ 5 s içinde şarj=1 | Döngü tamamlanınca |
| **Bütünleşik Görev** (Modül E) | Sırayla: 5 s statik → ≥ 0,8 m hareket + ≥ 45° dönüş → hedefte 5 s statik → istasyona yaklaşma → yerleşme → ≤ 5 s içinde şarj. Kutu baştan sona robotta | Bütün adımlar bitince ya da bir adım zaman aşımına uğrayınca |
| **Toparlanma** (Ö1.4, ek) | Başlangıç eğimi ≥ 8°. 10 s içinde \|θ\| < 3°. Devrilme yok | 10 s dolunca |

Bütün sayılar **Ayarlar** ekranından değiştirilebilir. Gerçek robotla ayarlandıktan sonra dondurulmalıdır.

---

## 5. Test durumu

### Doğrulanan
- **20 birim testi** geçiyor: telemetri paketi ve çerçeve çözümü, homografi, hareket ve yön sınıflandırma, kutu düştü, 5 görevin kuralları, hash zincirinin kurcalamayı yakalaması (içerik değişikliği, sütun değişikliği, araya kayıt silme).
- **Emülatörde:** uygulama açıldı, simülatör telemetrisi aktı, 3 s kopmada 1 kopma, 3 kayıp paket ve 3999 ms aralık doğru ölçüldü.
- **realme 11'de (Android 15):**
  - kamera 1280×960, 13–30 fps,
  - 7 marker'ın hepsi algılandı, çizimler doğru hizada,
  - kalibrasyon çalıştı; elde tutulan telefonla istasyon konumu ~6 cm hatayla, robot yönü 88–89° (beklenen 90°),
  - video kaydedildi; videonun SHA-256'sı PC'de hesaplanan değerle birebir aynı,
  - PDF üretildi, "Zinciri doğrula" → sağlam.
- **ESP32 firmware'i** (simülatör + robot örneği) hatasız derleniyor.

### Bekleyen
- Gerçek ESP32 ile BLE bağlantısı ve 10 dakikalık 1 Hz testi.
- Tripodla sabit kamerada şerit metre doğruluk testi (hedef: < ±5 cm).
- Gerçek robotla eşik ayarı ve 4/4 bütünleşik görev provası.

---

## 6. Yükleme ve güncelleme

### 6.1 Başka bir telefona yükleme (en kolay yol)

1. `apk/cdtp-izleme-0.1.0.apk` dosyasını telefona gönderin: WhatsApp (dosya olarak), Google Drive, e-posta ya da USB kablosuyla kopyalama.
2. Telefonda dosyaya dokunun.
3. "Bilinmeyen kaynaklardan yükleme" izni sorulursa, dosyayı açtığınız uygulamaya (WhatsApp, Dosyalar vb.) izin verin.
4. **Yükle**. İlk açılışta **Kamera** ve **Yakındaki cihazlar (Bluetooth)** izinlerini verin.

Gereksinimler: Android 8.0 veya üstü, 64-bit ya da 32-bit ARM işlemci. Bugünkü telefonların neredeyse hepsi uyar. Uygulama yaklaşık 57 MB yer kaplar; videolar ayrıca yer tutar.

### 6.2 USB kablosuyla yükleme (geliştirici yolu)

Telefonda **USB hata ayıklama** açık olmalı. Realme için:
1. Ayarlar → Cihaz hakkında → Sürüm → **Yapı numarası**na 7 kez dokunun.
2. Ek ayarlar → Geliştirici seçenekleri → **USB hata ayıklama**, **USB üzerinden yükle** ve **İzin izlemeyi devre dışı bırak** seçeneklerini açın.

Sonra bilgisayarda:
```
C:\Users\PC\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r apk\cdtp-izleme-0.1.0.apk
```

### 6.3 Güncelleme

- Yeni APK'yı aynı şekilde yükleyin. Android "Güncelle" der ve **kayıtlar, kalibrasyon ve ayarlar korunur.**
- **Önemli – imza kuralı:** Android bir uygulamanın üstüne ancak **aynı anahtarla imzalanmış** bir sürümü yükler.
  - Şu anki APK, bu bilgisayardaki debug anahtarıyla (`C:\Users\PC\.android\debug.keystore`) imzalı.
  - Başka bir bilgisayarda derlenen APK farklı imzalı olur. Yükleme "uygulama yüklenmedi / imza uyuşmuyor" hatası verir.
  - O durumda eski uygulamayı kaldırmak gerekir ve **kaldırınca bütün kayıtlar silinir.**
  - Bu yüzden: ya hep bu bilgisayardan derleyin, ya da `debug.keystore` dosyasını ekipte saklayın. Finalden önce ekibe ait bir release anahtarı oluşturulacak (bkz. [Kalan işler](#12-kalan-işler)).
- Yeni bir sürüm çıkarırken `cdtp-mobile/app/build.gradle.kts` içindeki `versionCode` (1 artır) ve `versionName` değerlerini güncelleyin.

### 6.4 Kayıtları sıfırlamak (finalden önce)

Ayarlar → Uygulamalar → **ÇDTP İzleme** → Depolama → **Verileri sil**. Zincir #1'den yeniden başlar. Kalibrasyon ve eşikler de sıfırlanır; dondurma da kalkar.

---

## 7. Geliştirme ortamı ve derleme

Bu bilgisayarda kurulu olanlar:
- JDK 17: `C:\Program Files\Eclipse Adoptium\jdk-17...`
- Android SDK: `C:\Users\PC\AppData\Local\Android\Sdk` (Android Studio yok; istenirse kurulabilir ve bu SDK'yı kendisi bulur)
- Python 3.13: opencv-contrib-python, reportlab, platformio

### Android uygulaması
```
cd cdtp-mobile
gradlew.bat assembleDebug          :: APK üretir
gradlew.bat testDebugUnitTest      :: 20 birim testi
```
- **Çıktı yeri:** Klasör adında "Ç" olduğu için Gradle çıktıları `C:\Users\PC\.cdtp-build\` altına yazılır. Aksi halde Windows'ta testler çalışmıyor.
- **APK:** `C:\Users\PC\.cdtp-build\app\outputs\apk\debug\app-debug.apk`. Paylaşmak için `apk\` klasörüne kopyalayın.
- **Emülatör için:** `gradlew.bat assembleDebug -Pemu` (x86_64 ekler, APK büyür).
- **Android Studio ile:** File → Open → `cdtp-mobile` klasörü.

### ESP32 firmware
```
cd cdtp-firmware-telemetry
pio run -e simulator                 :: derle
pio run -e simulator -t upload       :: karta yükle
pio device monitor                   :: seri monitör (115200)
```

### Araçlar
```
python tools/generate_markers.py                   :: marker PDF'ini yeniden üret (--floor-mm 120 gibi)
python tools/udp_simulator.py --ip <telefon IP>    :: Wi-Fi UDP telemetri denemesi
python tools/verify_chain.py <zincir.json>         :: kayıt zincirini PC'de doğrula
python tools/analyze_telemetry.py <telemetri.csv>  :: 1 Hz kabul testi
```

---

## 8. Kullanım kılavuzu

### 8.1 Hazırlık (bir kere)

1. **Marker'ları yazdırın:** `markers/cdtp_aruco_markers.pdf`, yazıcıda ölçek **%100 / Gerçek boyut**. Siyah karenin kenarını cetvelle kontrol edin: zemin ve istasyon 100 mm, robot 80 mm, kutu 60 mm. Kesik çizgiden keserek beyaz kenar bırakın; algılama için gerekli.
2. **Yapıştırın:**
   - **ID 0**: robotun üstüne, yatay. **Oklu kenar robotun önüne** bakmalı.
   - **ID 1**: ilaç kutusunun üstüne.
   - **ID 20**: şarj istasyonunun üstüne, yatay.
3. **Zemini kurun** (yaklaşık 2 m × 1,5 m alan önerilir):
   ```
        13 ──────────── 12          (telefondan uzak)
        │                │
        │   test alanı   │     20 (istasyon) alanın içinde ya da kenarında
        │                │
        10 ──────────── 11          (telefona yakın)
                 📱 tripod
   ```
   - Robot marker'ı yerden yüksekte olduğu için ölçüm biraz kayar (paralaks). Bunu önlemek için kalibrasyon sırasında zemin marker'larını robot marker'ıyla **aynı yükseklikte** takozların üstüne koyun. Kalibrasyondan sonra takozlar kalabilir ya da yere indirilebilir; önemli olan kalibrasyon anındaki yükseklik.
   - 10→11 ve 10→13 arasını **marker merkezlerinden** şerit metreyle ölçün.
4. **Telefonu tripoda** takın: 1,5–2 m yükseklik, alana yukarıdan eğik bakacak şekilde. **Test boyunca telefona dokunmayın.**

### 8.2 Ekran ekran kullanım

**Ana ekran** – Sağdaki "Hazırlık durumu" kartında izinler, OpenCV, telemetri, kalibrasyon, eşikler ve kayıt sayısı görünür. Hepsi yeşilse teste hazırsınız.

**1 · Bağlantı**
- Bağlantı türünü seçin:
  - **BLE (ESP32):** "Otomatik bağlan" ya da "Tara" → listeden cihaz.
  - **Klasik BT:** cihazı önce telefonun Bluetooth ayarlarından eşleyin, sonra listeden seçin.
  - **Wi-Fi UDP:** ekranda telefonun IP'si yazar; robot paketleri bu IP'ye göndermeli.
  - **Simülatör:** donanımsız deneme. "Şarjı başlat", "3 s kopma" ve "±10° it" butonları var.
- Sağ tarafta canlı değerler ve bağlantı kalitesi görünür: son paket yaşı, kayıp, ortalama periyot, en büyük aralık, kopma sayısı.
- "CSV dışa aktar" 10 dakikalık 1 Hz testi içindir.
- Sağ üstteki rozet her ekranda görünür: yeşil = veri akıyor, kırmızı = bağlantı koptu.

**2 · Kalibrasyon**
- Kamerada 10, 11, 12 ve 13 numaralı marker'lar yeşil olunca ölçtüğünüz genişlik ve derinliği girip **Kalibre et**'e basın.
- İstasyon (20) o an görünüyorsa konumu da kaydedilir. Robot istasyona yerleşince marker'ı kapatabileceği için bu önemli.
- **Doğruluk testi:** robot marker'ını bir yere koyun → "A noktasını işaretle" → marker'ı 0,5, 1,0 ve 1,5 m kaydırıp ekrandaki "A → şimdi mesafe" değerini şerit metreyle karşılaştırın.
- Turuncu çizgi robot marker'ının izidir. "İzi temizle" ile silinir.
- **"⚠ KAMERA OYNADI"** yazarsa telefon kımıldamıştır: yeniden kalibre edin.

**3 · Görev Seç**
- Görevi seçin:
  - Yönlendirmede beklenen yönü ve mesafe ön ayarını (~1 m / ~0,5 m) seçin.
  - Şarjda modu seçin (temel / çek-geri getir).
- "Canlı test ekranına geç".

**Canlı Test**
- **TESTİ BAŞLAT**: bu an başlangıç zaman damgasıdır, video kaydı başlar (sol üstte REC).
- Ekranda sayaç, durum, yön, konum, hız, kutu, eğim, şarj, bağlantı ve canlı kural listesi (✓ / … / ✗) görünür.
- Test kendiliğinden biter ya da **TESTİ BİTİR**'e basılır. Test sürerken geri tuşu çalışmaz.
- **Başlatılan test iptal edilemez, mutlaka kayda geçer.**

**Sonuç**
- Ekranda GEÇTİ/KALDI, kural kural gerekçe, ölçülen değerler, bağlantı özeti, hash'ler ve zaman çizelgesi görünür. Düzenleme butonu yoktur.
- "PDF paylaş" ve "Videoyu aç" butonları var.

**Kayıtlar**
- Bütün testler sıra numarasıyla listelenir.
- **Zinciri doğrula:** bütün hash'leri ve video dosyalarını yeniden hesaplar.
- **Zinciri JSON olarak dışa aktar:** jüri `tools/verify_chain.py` ile kendi bilgisayarında doğrulayabilir.
- Debug sürümde "Son kaydı kurcala" test butonu vardır. Kırılmanın yakalandığını göstermek içindir; final telefonunda kullanmayın.

**Ayarlar**
- Bütün eşikler burada; değiştirip "Kaydet".
- **DONDUR**: 12. haftada basılır, geri alınamaz.
- Video kaydını açıp kapatma da buradadır.

### 8.3 Görev başına uygulama

| Görev | Nasıl yapılır |
|---|---|
| Statik Denge | Robot dengede ve kutu üstündeyken Başlat. 30 s sonra kendiliğinden biter |
| Yönlendirme | Görevde beklenen yönü seç → Başlat → eldivenle komut ver → robot durunca 3 s sonra biter |
| Şarj | Robot istasyonun 30 cm yaklaşma bölgesindeyken Başlat → robot kendisi yerleşir → şarj başlayınca biter |
| Şarj – çek/geri getir | Şarj başladıktan sonra robotu istasyondan çekin, sonra geri yerleştirin |
| Bütünleşik | Başlat → 5 s bekle → eldivenle ~1 m + dönüş → 5 s bekle → istasyona yönlendir → otonom yerleşme → şarj |
| Toparlanma | Robotu ±10° eğik tutun, Başlat'a basıp hemen bırakın. 10 s sürer |

---

## 9. ESP32 / robot bağlantısı

### Simülatör ESP32 ile deneme
1. ESP32 DevKit'i USB ile bilgisayara bağlayın. `cdtp-firmware-telemetry` klasöründe `pio run -e simulator -t upload` çalıştırın.
2. Karttaki mavi LED yanıp söner; bu, bağlantı beklediği anlamına gelir.
3. Telefonda **Bağlantı → BLE (ESP32) → Otomatik bağlan**. LED sürekli yanar, değerler saniyede bir güncellenir.
4. Karttaki **BOOT** butonu şarj durumunu değiştirir.
5. `pio device monitor` açıp şu komutları yazabilirsiniz:
   - `d`: 3 s paket göndermez. Telefon "BAĞLANTI KOPTU" demeli, sonra toparlanmalı.
   - `k` / `j`: robotu ±10° itmiş gibi eğim üretir.
   - `c`: şarj durumunu değiştirir.
6. **1 Hz kabul testi:** 10 dakika bağlı bırakın → CSV dışa aktar → `python tools/analyze_telemetry.py dosya.csv`. Kabul: periyot 1 s ±50 ms, kayıp < %1, aralık < 2 s.

### Gerçek robota taşıma (Kontrol ve Elektrik ekipleriyle)
- `lib/telemetry_ble` klasörü robotun ESP32 projesine kopyalanır. Kodda iki satır yeter:
  ```cpp
  telemetry_begin();                                    // setup()
  telemetry_set(batarya_pct, egim_deg, sarj, durum);    // kontrol döngüsünde
  ```
- **Elektrik ekibi:**
  - CN3722'nin CHRG çıkışını LED'e **ek olarak** bir GPIO'ya bağlar. Örnekte GPIO27, INPUT_PULLUP; şarjdayken LOW okunur.
  - Batarya gerilimi için bir bölücü kurar. Örnekte 100k/22k bölücü GPIO34'e bağlı.
- ESP-NOW (eldiven) ve BLE aynı ESP32'de birlikte çalışabilir.
- Örnek kod: `src/robot_example/main.cpp`.

---

## 10. Sorun giderme

| Belirti | Çözüm |
|---|---|
| Uygulama yüklenmiyor, "imza uyuşmuyor" | Farklı bilgisayarda derlenmiş. Bkz. 6.3. Eski uygulamayı kaldırmak kayıtları siler |
| BLE'de cihaz bulunamıyor | Bluetooth ve konum açık mı? "Yakındaki cihazlar" izni verildi mi? ESP32'nin LED'i yanıp sönüyor mu? Başka bir telefon ESP32'ye zaten bağlı mı? |
| "BAĞLANTI KOPTU" sık sık çıkıyor | Mesafeyi kısaltın, ESP32 antenini metal gövdeden uzak tutun. Ayarlar'da "Kopma eşiği" 2000 ms kalmalı (şartname) |
| Marker algılanmıyor | Işık yetersiz ya da yansıma var. Marker çok küçük ya da uzak. Beyaz kenar kesilmiş olabilir |
| Ekranda "KALİBRASYON YOK" | Kalibrasyon yapılmamış ya da kamera çözünürlüğü değişmiş: yeniden kalibre edin |
| "KAMERA OYNADI" | Telefon kımıldamış: yeniden kalibre edin |
| SOL/SAĞ ters çıkıyor | Zemin marker'ları yanlış köşede olabilir (10 yakın-sol olmalı). Ya da Ayarlar → "Dönüş işaretini ters çevir" |
| İleri/GERİ ters çıkıyor | Robot marker'ının oklu kenarı robotun önüne bakmıyor |
| Turuncu iz her yere çizilmiş | Kamera oynatılmış. İz her testte sıfırlanır; kalibrasyon ekranında "İzi temizle" |
| "Bu cihaz video + analizi birlikte desteklemiyor" | Normal; zaman damgalı kayıt kullanılır. Ayarlar'dan video kapatılabilir |

---

## 11. Final günü kontrol listesi

- [ ] Eşikler **DONDURULDU** (Ayarlar'da yeşil)
- [ ] Deneme kayıtları temizlendi, zincir #1'den başlıyor (bkz. 6.4). Ardından eşikler yeniden girilip donduruldu
- [ ] Telefon şarjı dolu, yedek powerbank var
- [ ] Tripod sağlam, telefon sabit
- [ ] Marker'lar yerinde, düz ve temiz; zemin ölçüleri not edildi
- [ ] Kalibrasyon yapıldı; "Kalibrasyondan sapma" küçük
- [ ] Robota bağlanıldı, rozet yeşil, periyot ~1000 ms
- [ ] Robot ve istasyon marker'ları kamerada görünüyor
- [ ] Yedek telefonda aynı APK yüklü (aynı imza)
- [ ] Testlerden sonra **Zinciri doğrula** + JSON dışa aktar + PDF'ler alındı

---

## 12. Kalan işler

| İş | Kim | Ne zaman |
|---|---|---|
| Simülatör ESP32 ile BLE + 10 dk 1 Hz testi | Recep | ESP32 gelince |
| Robot ESP32'sine telemetri modülünü taşıma | Recep + Kontrol | Faz 5 (hafta 9–11) |
| CHG/STAT → GPIO ve batarya ADC | Elektrik | Faz 5 |
| Tripodla şerit metre doğruluk testi | Recep | Marker'lar basılınca |
| Gerçek robotla eşik ayarı | Recep + Kontrol | Hafta 10–12 |
| Eşikleri dondurma | Recep | 12. hafta |
| Release imza anahtarı oluşturma ve saklama | Recep | Finalden önce |
| Danışmana açık sorular (Plan Bölüm 10) | Ekip | Hemen |

---

## 13. Dosya haritası

```
ÇDTP-mobil-uygulama/
├── MODUL_D_REHBER.md                ← bu belge
├── README.md                         ← kısa teknik özet
├── CDTP_Proje_Plani.md               ← plan, ICD, kararlar günlüğü
├── apk/cdtp-izleme-0.1.0.apk         ← telefona yüklenecek dosya
├── markers/
│   ├── cdtp_aruco_markers.pdf        ← yazdırılacak marker'lar
│   └── ekran_test_posteri.png        ← masa başı test görseli
├── cdtp-mobile/                      ← Android projesi
│   └── app/src/main/java/edu/ytu/cdtp/
│       ├── telemetry/   (BLE, SPP, UDP, simülatör, watchdog)
│       ├── vision/      (ArUco, homografi, filtre, hareket, kutu)
│       ├── camera/      (CameraX)
│       ├── task/        (eşikler, test oturumu, değerlendiriciler)
│       ├── report/      (Room, hash zinciri, PDF)
│       └── ui/          (ekranlar)
├── cdtp-firmware-telemetry/          ← ESP32 (PlatformIO)
│   ├── lib/telemetry_ble/            ← robot BLE modülü
│   ├── src/sim/                      ← simülatör
│   ├── src/robot_example/            ← entegrasyon şablonu
│   └── examples/arduino_hc05/        ← alternatif donanım
└── tools/                            ← Python yardımcı araçları
```
