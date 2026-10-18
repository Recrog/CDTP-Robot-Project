// ÇDTP – Alternatif donanım: Arduino (Uno/Nano) + HC-05/HC-06 Klasik Bluetooth.
// Robot MCU'su ESP32 değilse uygulamada "Klasik BT (HC-05/06)" seçilir.
//
// Bağlantı: HC-05 TX → D10, HC-05 RX ← D11 (5 V Arduino'da RX hattına 1k/2k bölücü!)
// Telefonda önce Bluetooth ayarlarından HC-05 ile eşleşin (PIN 1234 veya 0000).
//
// Çerçeve (14 byte): A5 5A | 11 byte ICD paketi | XOR(11 byte)
#include <SoftwareSerial.h>

SoftwareSerial bt(10, 11);  // RX, TX

uint16_t seq = 0;

void sendTelemetry(uint8_t battery, float tiltDeg, bool charging, uint8_t state) {
  int16_t t10 = (int16_t)(tiltDeg * 10.0f + (tiltDeg >= 0 ? 0.5f : -0.5f));
  uint32_t ms = millis();
  uint8_t p[11] = {
    (uint8_t)(seq & 0xFF), (uint8_t)(seq >> 8),
    (uint8_t)(ms & 0xFF), (uint8_t)(ms >> 8), (uint8_t)(ms >> 16), (uint8_t)(ms >> 24),
    battery,
    (uint8_t)((uint16_t)t10 & 0xFF), (uint8_t)((uint16_t)t10 >> 8),
    (uint8_t)(charging ? 1 : 0),
    state,
  };
  uint8_t x = 0;
  for (uint8_t i = 0; i < 11; i++) x ^= p[i];
  bt.write(0xA5);
  bt.write(0x5A);
  bt.write(p, 11);
  bt.write(x);
  seq++;
}

void setup() {
  bt.begin(9600);  // HC-05 varsayılan veri hızı
  pinMode(2, INPUT_PULLUP);  // örnek: CHG/STAT sinyali (şarjda LOW)
}

void loop() {
  static unsigned long next = 0;
  if ((long)(millis() - next) >= 0) {
    next += 1000;  // 1 Hz, kayma birikmez
    float tilt = 0.0;          // TODO: IMU'dan
    uint8_t battery = 80;      // TODO: ADC'den
    bool charging = digitalRead(2) == LOW;
    sendTelemetry(battery, tilt, charging, charging ? 3 : 0);
  }
}
