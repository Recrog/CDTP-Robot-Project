# ÇDTP – Modül D: Mobil İzleme ve Değerlendirme

İki tekerlekli ev içi hasta destek robotu için Android uygulaması, robot tarafı BLE telemetri modülü ve yardımcı araçlar.
Tasarım kararları ve ICD için: [CDTP_Proje_Plani.md](CDTP_Proje_Plani.md).

## Klasörler

| Klasör | İçerik |
|---|---|
| `cdtp-mobile/` | Android uygulaması (Kotlin, Jetpack Compose, CameraX, OpenCV ArUco, Room) |
| `cdtp-firmware-telemetry/` | ESP32 BLE telemetri modülü (`lib/telemetry_ble`), simülatör, robot entegrasyon örneği, HC-05 örneği |
| `tools/` | Marker üretici, UDP simülatörü, zincir doğrulayıcı, telemetri CSV analizi |
| `markers/` | Yazdırılacak ArUco marker'ları (A4 PDF) |

## Telefona yükleme (Realme 11)

1. **Ayarlar → Cihaz hakkında → Sürüm** bilgisinde **Yapı numarası**na 7 kez dokunun → Geliştirici seçenekleri açılır.
2. **Ayarlar → Ek ayarlar → Geliştirici seçenekleri**: **USB hata ayıklama** açık. Realme'de ayrıca **"USB üzerinden yükle"** ve **"İzin izlemeyi devre dışı bırak"** seçeneklerini açın.
3. Telefonu USB ile bağlayın, telefondaki "USB hata ayıklamasına izin ver" sorusunu onaylayın.
4. Yükleyin:
   ```
   C:\Users\PC\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r C:\Users\PC\.cdtp-build\app\outputs\apk\debug\app-debug.apk
   ```
   (Kablosuz alternatif: APK dosyasını telefona kopyalayıp dosya yöneticisinden açın.)

## Derleme

```
cd cdtp-mobile
gradlew.bat assembleDebug          # APK
gradlew.bat testDebugUnitTest      # 20 birim testi
```
Proje yolunda "Ç" olduğu için derleme çıktıları `C:\Users\PC\.cdtp-build\` altına yazılır (Windows'ta test çalıştırıcısı ASCII olmayan yolda sınıfları bulamıyor). Android Studio ile de açılabilir: `cdtp-mobile` klasörünü açın.

Firmware (ESP32 DevKit):
```
cd cdtp-firmware-telemetry
pio run -e simulator -t upload      # simülatörü karta yükle
pio device monitor                  # c = şarj, k/j = ±10°, d = 3 s kopma
```

## Uygulama akışı

1. **Bağlantı** – BLE (ESP32), Klasik BT (HC-05/06), Wi-Fi UDP veya uygulama içi **Simülatör**. Canlı batarya, eğim, şarj, son paket yaşı, paket kaybı, ortalama periyot, en büyük aralık, kopma sayısı. >2 s paket gelmezse **BAĞLANTI KOPTU** + olay log'u + otomatik yeniden bağlanma. CSV dışa aktarma (10 dk testi için `tools/analyze_telemetry.py`).
2. **Kalibrasyon** – 4 zemin marker'ı (10–13) görününce "Kalibre et": 15 karenin ortalamasıyla homografi. İstasyon (20) görünüyorsa konumu da kaydedilir. "A noktasını işaretle" ile şerit metre doğruluk testi. Kamera oynarsa uyarı verir.
3. **Görev seç** – Statik Denge · Yönlendirme (İleri/Geri/Sol/Sağ, ~1 m veya ~0,5 m) · Şarj (temel veya çek/geri getir) · Bütünleşik · Toparlanma (ek).
4. **Canlı test** – Kamera + marker/iz overlay'i, sayaç, HAREKET/DÖNÜŞ/STATİK/TOPARLANMA etiketi, yön, konum, kutu, telemetri, canlı kural listesi. "Testi Başlat" zaman damgası + video kaydı. Otomatik bitiş ya da "Testi Bitir".
5. **Sonuç** – GEÇTİ/KALDI, kural kural gerekçe, ölçülen değerler, hash'ler. Düzenleme butonu yok. PDF paylaş.
6. **Kayıtlar** – Liste, "Zinciri doğrula", zinciri JSON dışa aktar (`tools/verify_chain.py` ile PC'de bağımsız doğrulama).
7. **Ayarlar** – Bütün eşikler (EvalConfig). **12. hafta: DONDUR** (geri alınamaz). Video aç/kapa.

## Marker yerleşimi

`markers/cdtp_aruco_markers.pdf` dosyasını **%100 ölçekle** yazdırın (DICT_4X4_50).

```
        13 ─────────── 12        (kameradan uzak)
        │               │
        │    alan W×H   │        20 = şarj istasyonu
        │               │
        10 ─────────── 11        (kameraya yakın)
                📱 telefon (tripod, yüksek, eğik bakış)
```
- 10→11 mesafesi = W, 10→13 mesafesi = H (marker **merkezleri** arası, kalibrasyon ekranına girilir).
- Robot marker'ı (0) robotun üstünde yatay; oklu kenar robotun **önü**.
- Paralaks için yöntem (a): kalibrasyonda zemin marker'larını robot marker'ı yüksekliğinde takozlara koyun.

## Farklı donanım seçilirse

Uygulama 4 bağlantı türünde de **aynı 11 byte'lık ICD paketini** bekler:

| Robot MCU | Uygulamada | Not |
|---|---|---|
| ESP32 | BLE | `lib/telemetry_ble` → `telemetry_begin()` + `telemetry_set()` |
| Arduino + HC-05/06 | Klasik BT | `examples/arduino_hc05` · çerçeve `A5 5A + 11 byte + XOR` |
| ESP32 Wi-Fi / Raspberry Pi | Wi-Fi UDP | port 4210, ham 11 byte veya çerçeveli · `tools/udp_simulator.py` örnek |

## Ö4 rubriği ↔ uygulama

| Alt test | Uygulamadaki karşılığı |
|---|---|
| Ö4.1 1 Hz şarj + eğim, kablosuz | Bağlantı ekranı: canlı değerler + periyot/kayıp/aralık istatistikleri, 2 s watchdog |
| Ö4.2 Kameradan hareket + yön | Canlı test: HAREKET/DÖNÜŞ/STATİK/TOPARLANMA + İLERİ/GERİ/SOL/SAĞ |
| Ö4.3 Otomatik değerlendirme | 5 değerlendirici, kural bazlı GEÇTİ/KALDI + gerekçe |
| Ö4.4 Değiştirilemez rapor | Room insert-only + SHA-256 hash zinciri + video SHA-256 + PDF |

## Henüz yapılmadı / sahada yapılacak

- Gerçek telefonla kamera testi (dönüş, overlay hizası, FPS, video+analiz birlikte) — telefon bağlanınca.
- Eşiklerin gerçek robotla ayarı (Faz 5), sonra 12. hafta dondurma.
- Release imzası: şu an debug anahtarı. Final öncesi kendi keystore'unuzla imzalayın; debug derlemedeki "kurcalama testi" butonu release'te yoktur.
