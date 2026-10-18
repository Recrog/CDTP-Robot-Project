// ÇDTP – Robot ESP32'sine entegrasyon örneği (Kontrol + Elektrik ekipleri için).
//
// Bu dosya sadece ŞABLONDUR: denge/motor kodu Kontrol ekibine aittir.
// Telemetri modülünün beklediği tek şey, kontrol döngüsünün şu dört değeri güncel tutmasıdır:
//   batarya_%, eğim (derece), şarj_flag (CHG/STAT pininden), durum (RobotState)
//
// Elektrik ekibinden beklenen bağlantılar:
//   - CN3722 CHRG çıkışı (açık-drenaj, şarjda LOW) → LED'e EK OLARAK GPIO27 (INPUT_PULLUP)
//     LED donanımsal kalır; MCU sadece okur (Açık Soru #5).
//   - Batarya gerilimi → bölücü → GPIO34 (ADC1). 3S Li-ion: 9,0 V = %0, 12,6 V = %100
//     Örnek bölücü 100k / 22k → 12,6 V'ta ~2,27 V (ADC 11 dB aralığında).
#include <Arduino.h>
#include "telemetry_ble.h"

static const int CHG_PIN = 27;
static const int VBAT_PIN = 34;
static const float DIVIDER = (100.0f + 22.0f) / 22.0f;  // Vbat = Vadc * DIVIDER
static const float VBAT_EMPTY = 9.0f;
static const float VBAT_FULL = 12.6f;

// --- Kontrol ekibinin kendi kodunda güncellediği değişkenler (örnek) ---
volatile float tilt_deg = 0.0f;          // IMU füzyonundan gelen gövde eğimi
volatile uint8_t robot_state = ST_BEKLE; // durum makinesi
// ----------------------------------------------------------------------

static uint8_t battery_pct() {
    // ESP32 ADC kalibre edilmiş mV okuması
    uint32_t mv = analogReadMilliVolts(VBAT_PIN);
    float v = mv / 1000.0f * DIVIDER;
    float p = (v - VBAT_EMPTY) / (VBAT_FULL - VBAT_EMPTY) * 100.0f;
    if (p < 0) p = 0;
    if (p > 100) p = 100;
    return (uint8_t)(p + 0.5f);
}

void setup() {
    Serial.begin(115200);
    pinMode(CHG_PIN, INPUT_PULLUP);
    analogSetPinAttenuation(VBAT_PIN, ADC_11db);
    telemetry_begin();  // ESP-NOW (eldiven) ile aynı anda çalışabilir
}

void loop() {
    // ... Kontrol ekibinin denge döngüsü burada (ör. 200 Hz) tilt_deg ve robot_state'i günceller ...

    static uint32_t last = 0;
    if (millis() - last >= 100) {  // telemetri değerlerini 10 Hz tazele; gönderim 1 Hz
        last = millis();
        bool charging = digitalRead(CHG_PIN) == LOW;
        telemetry_set(battery_pct(), tilt_deg, charging, charging ? ST_SARJDA : robot_state);
    }
}
