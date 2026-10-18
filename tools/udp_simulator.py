"""PC'den telefona 1 Hz ICD telemetrisi gönderir (Wi-Fi UDP bağlantısını test etmek için).

Kullanım:
    python tools/udp_simulator.py --ip 192.168.1.42          # telefonun IP'si (uygulamada görünür)
    python tools/udp_simulator.py --broadcast                # 255.255.255.255
    python tools/udp_simulator.py --ip ... --framed          # A5 5A + 11 byte + XOR
Klavye: Enter → şarjı değiştir.
"""
import argparse
import math
import random
import socket
import struct
import sys
import threading
import time


def packet(seq, t_ms, battery, tilt_deg, charging, state):
    return struct.pack("<HIBhBB", seq & 0xFFFF, t_ms & 0xFFFFFFFF, battery, int(round(tilt_deg * 10)), 1 if charging else 0, state)


def frame(p):
    x = 0
    for b in p:
        x ^= b
    return b"\xA5\x5A" + p + bytes([x])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip")
    ap.add_argument("--port", type=int, default=4210)
    ap.add_argument("--broadcast", action="store_true")
    ap.add_argument("--framed", action="store_true")
    a = ap.parse_args()
    if not a.ip and not a.broadcast:
        ap.error("--ip veya --broadcast gerekli")
    dest = ("255.255.255.255" if a.broadcast else a.ip, a.port)

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    state = {"charging": False}

    def keys():
        for _ in sys.stdin:
            state["charging"] = not state["charging"]
            print("şarj:", state["charging"])

    threading.Thread(target=keys, daemon=True).start()
    t0 = time.monotonic()
    nxt = t0
    seq = 0
    battery = 87.0
    print(f"Gönderiliyor → {dest[0]}:{dest[1]}  (Enter: şarj değiştir, Ctrl+C: çık)")
    while True:
        nxt += 1.0
        time.sleep(max(0.0, nxt - time.monotonic()))
        t = time.monotonic() - t0
        battery = min(100.0, battery + 0.5) if state["charging"] else max(0.0, battery - 0.02)
        tilt = 1.5 * math.sin(2 * math.pi * t / 8.0) + random.uniform(-0.3, 0.3)
        p = packet(seq, int(t * 1000), int(battery), tilt, state["charging"], 3 if state["charging"] else 0)
        s.sendto(frame(p) if a.framed else p, dest)
        seq += 1


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
