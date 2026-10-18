"""Uygulamadan dışa aktarılan kayıt zincirini (Kayıtlar → "Zinciri JSON olarak dışa aktar")
telefondan bağımsız olarak doğrular. Jüri/danışman kendi bilgisayarında çalıştırabilir.

    python tools/verify_chain.py cdtp_zincir_XXXX.json

Kural: recordHash = SHA256(prevHash + payloadJson), ilk kaydın prevHash'i 64 sıfır.
"""
import hashlib
import json
import sys

GENESIS = "0" * 64


def main(path):
    with open(path, encoding="utf-8") as f:
        records = json.load(f)
    prev = GENESIS
    expected_idx = 1
    bad = 0
    for r in records:
        problems = []
        h = hashlib.sha256((r["prevHash"] + r["payloadJson"]).encode("utf-8")).hexdigest()
        if h != r["recordHash"]:
            problems.append("hash uyuşmuyor (içerik değişmiş)")
        if r["prevHash"] != prev:
            problems.append("zincir bağı kopuk")
        if r["idx"] != expected_idx:
            problems.append(f"sıra no atlıyor (beklenen {expected_idx})")
        p = json.loads(r["payloadJson"])
        if p.get("passed") != r["passed"]:
            problems.append("GEÇTİ/KALDI sütunu içerikle çelişiyor")
        verdict = "GEÇTİ" if p.get("passed") else "KALDI"
        status = "OK " if not problems else "HATA"
        print(f"[{status}] #{r['idx']:>3}  {p.get('taskTitle', '?'):<40} {verdict:<6} {r['recordHash'][:16]}…  {'; '.join(problems)}")
        bad += bool(problems)
        prev = r["recordHash"]
        expected_idx = r["idx"] + 1
    print()
    print(f"{len(records)} kayıt, {bad} sorunlu → {'ZİNCİR SAĞLAM' if bad == 0 else 'ZİNCİR KIRIK'}")
    if records:
        print("Son hash (raporda/PDF'te karşılaştırın):", records[-1]["recordHash"])
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
