// ÇDTP – Robot tarafı BLE telemetri modülü (ICD: CDTP_Proje_Plani.md Bölüm 5)
//
// Kontrol ekibi için kullanım:
//
//   #include "telemetry_ble.h"
//   void setup() { telemetry_begin(); ... }
//   void loop()  { ...; telemetry_set(batarya_pct, egim_deg, sarj_flag, durum); }
//
// telemetry_set() istenen sıklıkta çağrılabilir (kontrol döngüsünden de);
// gönderim ayrı bir FreeRTOS görevinde tam 1 Hz yapılır. Değişkenler kilitle korunur.
#pragma once
#include <stdint.h>

enum RobotState : uint8_t {
    ST_BEKLE = 0,
    ST_MANUEL = 1,
    ST_DOCKING = 2,
    ST_SARJDA = 3,
    ST_HATA = 4,
};

#define CDTP_DEVICE_NAME "CDTP-ROBOT"
#define CDTP_SERVICE_UUID "8f3a0001-5c2e-4b7a-9d10-3e6f2a1b0c01"
#define CDTP_TELEMETRY_UUID "8f3a0002-5c2e-4b7a-9d10-3e6f2a1b0c01"
#define CDTP_COMMAND_UUID "8f3a0003-5c2e-4b7a-9d10-3e6f2a1b0c01"
#define CDTP_PACKET_LEN 11
#define CDTP_PERIOD_MS 1000

// BLE servisini başlatır, reklam yapar ve 1 Hz gönderim görevini kurar.
void telemetry_begin(const char* device_name = CDTP_DEVICE_NAME);

// Son değerleri günceller (thread-safe). tilt_deg derece cinsinden (ör. 1.4).
void telemetry_set(uint8_t battery_pct, float tilt_deg, bool charging, uint8_t state);

// Telefon bağlı mı?
bool telemetry_is_connected();

// Gönderilen paket sayısı (seq).
uint16_t telemetry_seq();

// Test amaçlı: ms süresince notify göndermez (uygulamanın 2 s kopma algısını denemek için).
void telemetry_pause_ms(uint32_t ms);

// 11 byte ICD paketini oluşturur (little-endian). Diğer taşıma yolları (UART/SPP/UDP) için de kullanılabilir.
void telemetry_build_packet(uint8_t out[CDTP_PACKET_LEN], uint16_t seq, uint32_t t_ms,
                            uint8_t battery_pct, float tilt_deg, bool charging, uint8_t state);
