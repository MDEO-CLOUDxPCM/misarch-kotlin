#!/usr/bin/env python3
"""Summarise a perfsmoke.sh run: per phase the request latencies per type (from loadgen's meta.json) and the mean CPU
(cores) per container and per container class (from cpumon's cpu.csv / host.csv). Writes DIR/summary.json.

    perfsummary.py DIR
"""
import csv
import json
import os
import re
import sys
from collections import defaultdict

DOMAIN = ["address", "catalog", "discount", "inventory", "invoice", "media", "notification", "order", "payment",
          "return", "review", "shipment", "shoppingcart", "tax", "user", "wishlist", "simulation"]


def short(name):
    return re.sub(r"^misarch-(kotlin-)?", "", name)[:-2] if name.endswith("-1") else name


def klass(s):
    if s in DOMAIN:
        return "apps (16 services + simulation)"
    if s.endswith("-dapr") or s == "placement":
        return "dapr sidecars"
    if s.endswith("-ecs"):
        return "experiment-config sidecars"
    if s.endswith("-db") or s in ("keycloak-db",):
        return "databases"
    if s == "gateway":
        return "gateway"
    if s == "keycloak":
        return "keycloak"
    if s.startswith("engine:"):
        return "docker engine"
    return "other infra"


def main(d):
    meta = json.load(open(os.path.join(d, "meta.json")))
    series = defaultdict(list)   # key -> [(t, usage_usec)]
    for r in csv.DictReader(open(os.path.join(d, "cpu.csv"))):
        series[r["key"]].append((float(r["t"]), int(r["usage_usec"])))
    host = [(float(r["t"]), int(r["host_busy_usec"])) for r in csv.DictReader(open(os.path.join(d, "host.csv")))]

    def rate(pts, a, b):
        pts = [p for p in pts if a <= p[0] <= b]
        if len(pts) < 2:
            return None
        return (pts[-1][1] - pts[0][1]) / 1e6 / (pts[-1][0] - pts[0][0])

    out = {"phases": {}}
    for ph in meta["phases"]:
        a, b = ph["start"] + 15, ph["end"] - 2   # skip the transient at the phase start
        per = {}
        for k, pts in series.items():
            if k.startswith("misarch-") or k.startswith("engine:"):
                v = rate(pts, a, b)
                if v is not None:
                    per[short(k) if k.startswith("misarch-") else k] = round(v, 4)
        cls = defaultdict(float)
        for k, v in per.items():
            cls[klass(k)] += v
        hb = rate(host, a, b)
        total = sum(v for k, v in per.items())
        lat = meta["summary"].get(ph["name"], {})
        out["phases"][ph["name"]] = {
            "rates": ph["rates"], "cpuCores": dict(sorted(per.items(), key=lambda x: -x[1])),
            "cpuByClass": {k: round(v, 3) for k, v in sorted(cls.items(), key=lambda x: -x[1])},
            "stackCores": round(total, 3), "hostBusyCores": round(hb, 3) if hb else None,
            "foreignCores": round(hb - total, 3) if hb else None,
            "latency": {t: {k: v[k] for k in ("n", "errRate", "mean", "p50", "p95")} for t, v in sorted(lat.items())}}
    json.dump(out, open(os.path.join(d, "summary.json"), "w"), indent=1)
    for name, p in out["phases"].items():
        print(f"== {name}: stack {p['stackCores']} cores, host busy {p['hostBusyCores']}, foreign {p['foreignCores']}")
        print("   by class:", ", ".join(f"{k} {v}" for k, v in p["cpuByClass"].items()))
        print("   top:", ", ".join(f"{k} {v}" for k, v in list(p["cpuCores"].items())[:12]))
        for t, v in p["latency"].items():
            print(f"   {t:22s} n={v['n']:5d} err={v['errRate']:.3f} mean={v['mean']} p50={v['p50']} p95={v['p95']}")


if __name__ == "__main__":
    main(sys.argv[1])
