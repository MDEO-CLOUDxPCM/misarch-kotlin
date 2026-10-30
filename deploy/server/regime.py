#!/usr/bin/env python3
"""Speed-regime watch without a stack (deploy/server/run.sh regime): every --interval s the speedprobe bench in a
fresh Python process, per-CPU MHz (idle before the bench and while it runs), /proc/stat steal/busy/iowait over the
interval and loadavg. Writes $RESULTS_DIR/regime.csv (one row per sample, flushed) and regime.json (summary; also on
SIGTERM/SIGINT).

    python3 deploy/server/regime.py [--minutes 10] [--interval 30]

Classification (speedprobe.py rule: slow = bench median > 1.3 x the fast reference) against two references:
  own   the lower-quartile of this run's bench medians (robust "fast" level of this machine)
  vm    32.6 ms, the dev VM's fast median (TIMING_BENCH_FAST_MS overrides) - says how this machine compares to the VM
"""
import argparse
import csv
import os
import signal
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402

FIELDS = ["t", "iso", "bench_median_ms", "bench_min_ms", "bench_ratio_vm", "mhz_src", "mhz_idle_mean", "mhz_busy_mean",
          "mhz_busy_max", "steal_pct", "busy_pct", "iowait_pct", "load1", "load5", "load15", "mhz_busy_per_cpu",
          "error"]


class Stop(Exception):
    pass


def classify(medians, ref):
    flags = ["slow" if m > C.BENCH_SLOW_RATIO * ref else "fast" for m in medians]
    switches = sum(1 for a, b in zip(flags, flags[1:]) if a != b)
    return {"referenceMs": round(ref, 2), "slowThresholdMs": round(C.BENCH_SLOW_RATIO * ref, 2),
            "fast": flags.count("fast"), "slow": flags.count("slow"),
            "slowFraction": round(flags.count("slow") / len(flags), 3) if flags else None, "switches": switches,
            "verdict": None if not flags else "fast" if flags.count("slow") == 0 else
            "slow" if flags.count("fast") == 0 else "mixed"}


def summarize(rows, a, started, status):
    meds = [r["bench_median_ms"] for r in rows if r.get("bench_median_ms") not in (None, "")]
    s = {"status": status, "started": started, "ended": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
         "minutes": a.minutes, "intervalS": a.interval, "samples": len(rows), "benchSamples": len(meds),
         "thresholds": {"benchSlowRatio": C.BENCH_SLOW_RATIO, "benchFastMsVm": C.BENCH_FAST_MS}}
    if meds:
        lq = C.pct(meds, .25)
        s["benchMs"] = {k: round(C.pct(meds, q), 2) for k, q in
                        [("min", 0), ("p25", .25), ("median", .5), ("p75", .75), ("max", 1)]}
        s["benchSpreadP90P10"] = round(C.pct(meds, .9) / C.pct(meds, .1), 3)
        s["own"] = classify(meds, lq)
        s["vm"] = classify(meds, C.BENCH_FAST_MS)
        s["machineVsVmFast"] = round(lq / C.BENCH_FAST_MS, 3)
    for k in ("steal_pct", "busy_pct", "mhz_busy_mean", "mhz_idle_mean"):
        v = [float(r[k]) for r in rows if r.get(k) not in (None, "")]
        if v:
            s[k] = {"mean": round(sum(v) / len(v), 2), "max": round(max(v), 2), "min": round(min(v), 2)}
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=10)
    ap.add_argument("--interval", type=float, default=30)
    a = ap.parse_args()
    out = C.results_dir("regime")
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")

    def on_signal(signum, _f):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        raise Stop(signal.Signals(signum).name)
    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    rows, status = [], "done"
    C.log(f"regime: {a.minutes} min, every {a.interval} s -> {out}/regime.csv")
    f = open(os.path.join(out, "regime.csv"), "w", newline="")
    w = csv.DictWriter(f, fieldnames=FIELDS)
    w.writeheader()
    try:
        end = time.monotonic() + a.minutes * 60
        prev = C.proc_stat()
        nxt = time.monotonic()
        while True:
            t = time.time()
            src, idle = C.cpu_mhz()
            p = C.bench_start()
            time.sleep(0.08)
            _, busy = C.cpu_mhz()
            b = C.bench_finish(p, t)
            cur = C.proc_stat()
            d = C.stat_delta(prev, cur)
            prev = cur
            la = C.loadavg()
            r = {"t": round(t, 2), "iso": time.strftime("%H:%M:%S", time.localtime(t)),
                 "bench_median_ms": b.get("medianMs"), "bench_min_ms": b.get("minMs"), "bench_ratio_vm": b.get("ratio"),
                 "mhz_src": src, "mhz_idle_mean": round(sum(idle) / len(idle), 1) if idle else None,
                 "mhz_busy_mean": round(sum(busy) / len(busy), 1) if busy else None,
                 "mhz_busy_max": max(busy) if busy else None, "steal_pct": d["steal"], "busy_pct": d["busy"],
                 "iowait_pct": d["iowait"], "load1": la[0], "load5": la[1], "load15": la[2],
                 "mhz_busy_per_cpu": " ".join(f"{x:.0f}" for x in busy), "error": b.get("error", "")}
            rows.append(r)
            w.writerow(r)
            f.flush()
            C.log(f"bench {r['bench_median_ms']} ms (min {r['bench_min_ms']}, x{r['bench_ratio_vm']} vs VM), "
                  f"MHz idle {r['mhz_idle_mean']} busy {r['mhz_busy_mean']}/{r['mhz_busy_max']}, "
                  f"steal {d['steal']} %, busy {d['busy']} %, load {la[0]}" + (f" ERROR {b['error']}" if b.get("error") else ""))
            nxt += a.interval
            if nxt >= end:
                break
            time.sleep(max(0.0, nxt - time.monotonic()))
    except Stop as e:
        status = f"cancelled ({e})"
        C.log(f"regime: {status}")
    finally:
        f.close()
        s = summarize(rows, a, started, status)
        C.write_json(os.path.join(out, "regime.json"), s)
    for k in ("own", "vm"):
        if k in s:
            c = s[k]
            print(f"{k:>3}: reference {c['referenceMs']} ms, slow > {c['slowThresholdMs']} ms: {c['fast']} fast, "
                  f"{c['slow']} slow, {c['switches']} switches -> {c['verdict']}")
    if "benchMs" in s:
        print(f"bench ms {s['benchMs']}, p90/p10 {s['benchSpreadP90P10']}, machine/VM fast {s['machineVsVmFast']}")
    print(f"summary: {out}/regime.json")
    return 0 if status == "done" else 130


if __name__ == "__main__":
    sys.exit(main())
