#include "telemetry_ble.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <math.h>

namespace {

portMUX_TYPE g_mux = portMUX_INITIALIZER_UNLOCKED;
uint8_t g_battery = 0;
int16_t g_tilt_x10 = 0;
uint8_t g_charging = 0;
uint8_t g_state = ST_BEKLE;

volatile uint16_t g_seq = 0;
volatile bool g_connected = false;
volatile uint32_t g_pause_until = 0;
NimBLECharacteristic* g_tlm = nullptr;

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* server, ble_gap_conn_desc* desc) override {
        g_connected = true;
        // Daha sık bağlantı aralığı: 15–30 ms (gecikme düşük kalsın)
        server->updateConnParams(desc->conn_handle, 12, 24, 0, 400);
        Serial.println("[tlm] telefon baglandi");
    }
    void onDisconnect(NimBLEServer* server) override {
        g_connected = false;
        Serial.println("[tlm] baglanti koptu, reklam yeniden basliyor");
        NimBLEDevice::startAdvertising();
    }
};

class CommandCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c) override {
        std::string v = c->getValue();
        Serial.printf("[tlm] komut alindi (%u byte)\n", (unsigned)v.size());
    }
};

int16_t clamp_tilt_x10(float tilt_deg) {
    long t10 = lroundf(tilt_deg * 10.0f);
    if (t10 > 32767) t10 = 32767;
    if (t10 < -32768) t10 = -32768;
    return (int16_t)t10;
}

void build_raw(uint8_t* out, uint16_t seq, uint32_t t_ms, uint8_t bat, int16_t tilt_x10, uint8_t chg, uint8_t st) {
    uint16_t tilt = (uint16_t)tilt_x10;
    out[0] = seq & 0xFF;          out[1] = seq >> 8;
    out[2] = t_ms & 0xFF;         out[3] = (t_ms >> 8) & 0xFF;
    out[4] = (t_ms >> 16) & 0xFF; out[5] = (t_ms >> 24) & 0xFF;
    out[6] = bat;
    out[7] = tilt & 0xFF;         out[8] = tilt >> 8;
    out[9] = chg;
    out[10] = st;
}

void tlm_task(void*) {
    TickType_t last = xTaskGetTickCount();
    uint8_t buf[CDTP_PACKET_LEN];
    for (;;) {
        vTaskDelayUntil(&last, pdMS_TO_TICKS(CDTP_PERIOD_MS));
        uint8_t bat, chg, st;
        int16_t tilt;
        portENTER_CRITICAL(&g_mux);
        bat = g_battery; tilt = g_tilt_x10; chg = g_charging; st = g_state;
        portEXIT_CRITICAL(&g_mux);

        uint16_t seq = g_seq;
        uint32_t now = millis();
        build_raw(buf, seq, now, bat, tilt, chg, st);

        g_tlm->setValue(buf, sizeof(buf));
        if (g_connected && (int32_t)(now - g_pause_until) >= 0) g_tlm->notify();
        g_seq = seq + 1;
    }
}

}  // namespace

void telemetry_build_packet(uint8_t out[CDTP_PACKET_LEN], uint16_t seq, uint32_t t_ms,
                            uint8_t battery_pct, float tilt_deg, bool charging, uint8_t state) {
    build_raw(out, seq, t_ms, battery_pct, clamp_tilt_x10(tilt_deg), charging ? 1 : 0, state);
}

void telemetry_begin(const char* device_name) {
    NimBLEDevice::init(device_name);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService* svc = server->createService(CDTP_SERVICE_UUID);
    g_tlm = svc->createCharacteristic(CDTP_TELEMETRY_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    NimBLECharacteristic* cmd = svc->createCharacteristic(CDTP_COMMAND_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    cmd->setCallbacks(new CommandCallbacks());
    svc->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(CDTP_SERVICE_UUID);
    adv->setScanResponse(true);  // cihaz adı tarama cevabında
    adv->start();

    // Çekirdek 0'da (Arduino loop'u çekirdek 1'de); öncelik düşük-orta.
    xTaskCreatePinnedToCore(tlm_task, "tlm", 4096, nullptr, 2, nullptr, 0);
    Serial.printf("[tlm] BLE reklam basladi: %s\n", device_name);
}

void telemetry_set(uint8_t battery_pct, float tilt_deg, bool charging, uint8_t state) {
    int16_t t10 = clamp_tilt_x10(tilt_deg);
    if (battery_pct > 100) battery_pct = 100;
    portENTER_CRITICAL(&g_mux);
    g_battery = battery_pct;
    g_tilt_x10 = t10;
    g_charging = charging ? 1 : 0;
    g_state = state;
    portEXIT_CRITICAL(&g_mux);
}

bool telemetry_is_connected() { return g_connected; }
uint16_t telemetry_seq() { return g_seq; }
void telemetry_pause_ms(uint32_t ms) { g_pause_until = millis() + ms; }
