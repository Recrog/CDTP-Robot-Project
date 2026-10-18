// ÇDTP – Simülatör ESP32: robot yokken uygulamayı gerçek BLE ile test etmek için.
//
// Eğim  : 1,5° genlikli sinüs (8 s) + ±0,3° gürültü
// Batarya: yavaşça azalır, şarjda artar
// BOOT (GPIO0) butonu: şarj durumunu değiştirir
// Mavi LED (GPIO2): bağlıyken sürekli yanar, değilse yanıp söner
//
// Seri monitör komutları (115200):
//   c  şarjı değiştir      k  +10° it (toparlanma)    j  -10° it
//   d  3 s paket gönderme (uygulamanın KOPMA algısını dene)
#include <Arduino.h>
#include "telemetry_ble.h"

static const int BOOT_PIN = 0;
static const int LED_PIN = 2;

static bool charging = false;
static float battery = 87.0f;
static float kick_deg = 0.0f;
static uint32_t kick_at = 0;

static void toggle_charging() {
    charging = !charging;
    Serial.printf("[sim] sarj: %s\n", charging ? "VAR" : "YOK");
}

void setup() {
    Serial.begin(115200);
    pinMode(BOOT_PIN, INPUT_PULLUP);
    pinMode(LED_PIN, OUTPUT);
    randomSeed(esp_random());
    telemetry_begin(CDTP_DEVICE_NAME);
    Serial.println("[sim] hazir. Komutlar: c=sarj k/j=+-10 derece d=3 s kopma");
}

void loop() {
    static bool last_btn = HIGH;
    static uint32_t last_btn_ms = 0;
    static uint32_t last_bat_ms = 0;
    static uint32_t last_log_ms = 0;
    uint32_t now = millis();

    bool b = digitalRead(BOOT_PIN);
    if (b == LOW && last_btn == HIGH && now - last_btn_ms > 250) {
        toggle_charging();
        last_btn_ms = now;
    }
    last_btn = b;

    while (Serial.available()) {
        switch (Serial.read()) {
            case 'c': toggle_charging(); break;
            case 'k': kick_deg = 10.0f; kick_at = now; Serial.println("[sim] +10 derece"); break;
            case 'j': kick_deg = -10.0f; kick_at = now; Serial.println("[sim] -10 derece"); break;
            case 'd': telemetry_pause_ms(3000); Serial.println("[sim] 3 s paket yok"); break;
            default: break;
        }
    }

    if (now - last_bat_ms >= 1000) {
        last_bat_ms = now;
        battery += charging ? 0.5f : -0.02f;
        if (battery > 100) battery = 100;
        if (battery < 0) battery = 0;
    }

    float t = now / 1000.0f;
    float tilt = 1.5f * sinf(2.0f * PI * t / 8.0f) + random(-30, 31) / 100.0f;
    if (kick_at) tilt += kick_deg * expf(-(float)(now - kick_at) / 1500.0f);

    telemetry_set((uint8_t)battery, tilt, charging, charging ? ST_SARJDA : ST_BEKLE);

    digitalWrite(LED_PIN, telemetry_is_connected() ? HIGH : ((now / 500) % 2));

    if (now - last_log_ms >= 5000) {
        last_log_ms = now;
        Serial.printf("[sim] seq=%u bagli=%d bat=%.0f%% egim=%.1f sarj=%d\n",
                      telemetry_seq(), telemetry_is_connected(), battery, tilt, charging);
    }
    delay(20);
}
