"""Bağlantı ekranından dışa aktarılan CSV ile 1 Hz testini değerlendirir (Plan 7.10).

    python tools/analyze_telemetry.py telemetri_XXXX.csv

Kabul: ortalama periyot 1000 ms ±50, kayıp < %1, hiçbir aralık > 2000 ms.
"""
import csv
import statistics
import sys


def main(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    if len(rows) < 2:
        print("Yeterli veri yok")
        return 2
    rx = [int(r["rx_elapsed_ms"]) for r in rows]
    seq = [int(r["seq"]) for r in rows]
    gaps = [b - a for a, b in zip(rx, rx[1:])]
    lost = 0
    for a, b in zip(seq, seq[1:]):
        d = (b - a) & 0xFFFF
        if 2 <= d <= 1000:
            lost += d - 1
    mean = statistics.mean(gaps)
    loss_pct = 100 * lost / (len(rows) + lost)
    duration = (rx[-1] - rx[0]) / 1000
    ok_period = abs(mean - 1000) <= 50
    ok_loss = loss_pct < 1
    ok_gap = max(gaps) <= 2000
    print(f"Süre            : {duration / 60:.1f} dk ({len(rows)} paket)")
    print(f"Ortalama periyot: {mean:.1f} ms (std {statistics.pstdev(gaps):.1f})  -> {'GEÇTİ' if ok_period else 'KALDI'}")
    print(f"Kayıp           : {lost} paket (%{loss_pct:.2f})  -> {'GEÇTİ' if ok_loss else 'KALDI'}")
    print(f"En büyük aralık : {max(gaps)} ms  -> {'GEÇTİ' if ok_gap else 'KALDI'}")
    if duration < 600:
        print("Not: Plan 10 dk test istiyor; bu kayıt daha kısa.")
    return 0 if (ok_period and ok_loss and ok_gap) else 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
