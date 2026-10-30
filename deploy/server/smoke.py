#!/usr/bin/env python3
"""End-to-end smoke on the server (deploy/server/run.sh smoke), < 1 h with the defaults when the images exist.

    python3 deploy/server/smoke.py [--minutes 20] [--tag TAG] [--keep-up] [--build-missing] [--rate 5]
                                   [--warmup-s 120] [--idle-s 60] [--settle-s 60]

1. images: misarch-kotlin/<svc>:TAG (--tag; via a compose override in $RESULTS_DIR, COMPOSE_FILE) or :dev; missing
   ones are an error unless --build-missing (run.sh images [--tag TAG] for them, which can take long)
2. deploy/compose/up.sh, then wait until the gateway answers `{ __typename }`
3. phases settle (--settle-s) -> idle (--idle-s, no load: background CPU) -> warm (--warmup-s) -> measure (--minutes)
   load: open loop at --rate req/s, anonymous GraphQL reads through http://localhost:8080/graphql (Gatling's
   `products` / `product(id)` texts without the employee-only internalName); product ids from the products answers,
   so an empty catalog just means products-only
4. samplers: 1 Hz cgroup usage_usec of every misarch-kotlin-* container, Dapr placement CPU every 60 s (10-s bins),
   speedprobe bench every 30 s
5. outputs in $RESULTS_DIR: cpu.csv, probes.csv, load.csv, phases.json, summary.json, summary.txt
6. deploy/compose/down.sh (stop, volumes kept; never --wipe) unless --keep-up
SIGTERM/SIGINT: stop load and samplers, write the summary (status cancelled), `docker compose stop -t 5`.
"""
import argparse
import csv
import json
import os
import random
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402

COMPOSE_DIR = os.path.join(C.ROOT, "deploy", "compose")
GATEWAY = os.environ.get("SMOKE_GATEWAY", "http://localhost:8080/graphql")
Q_PRODUCTS = ("query getProducts { products(filter: {isPubliclyVisible: true}, first: 10, orderBy: {direction: ASC, "
              "field: ID}, skip: %d) { hasNextPage nodes { id isPubliclyVisible } totalCount } }")
Q_PRODUCT = ("query getProduct($id: UUID!) { product(id: $id) { categories { hasNextPage totalCount } defaultVariant "
             "{ id isPubliclyVisible averageRating } id isPubliclyVisible variants { hasNextPage totalCount } } }")
GROUPS = {"gateway": ["gateway"], "gateway+sidecars": ["gateway", "gateway-dapr", "gateway-ecs"],
          "catalog": ["catalog"], "catalog+sidecars+db": ["catalog", "catalog-dapr", "catalog-ecs", "catalog-db"]}


class Stop(Exception):
    pass


class State:
    def __init__(self):
        self.phase = "start"
        self.stop = threading.Event()
        self.load_on = threading.Event()
        self.lock = threading.Lock()
        self.cpu = {}          # name -> [(t, usage_usec, phase)]
        self.loads = []        # dicts
        self.probes = []
        self.ids = []
        self.total_products = None
        self.phases = []       # [name, t_start, t_end]

    def set_phase(self, name):
        now = time.time()
        with self.lock:
            if self.phases:
                self.phases[-1][2] = now
            self.phases.append([name, now, None])
            self.phase = name
        C.log(f"phase {name}")


def gql(query, variables=None, timeout=30):
    body = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(GATEWAY, data=body, headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        data, status = e.read(), e.code
    except Exception as e:
        return (time.perf_counter() - t0) * 1000, 0, None, f"{type(e).__name__}: {e}"[:120]
    ms = (time.perf_counter() - t0) * 1000
    try:
        doc = json.loads(data)
    except ValueError:
        return ms, status, None, f"non-JSON answer ({len(data)} bytes)"
    err = ""
    if doc.get("errors"):
        err = "graphql: " + "; ".join(str(e.get("message", e))[:80] for e in doc["errors"][:2])
    elif status != 200:
        err = f"http {status}"
    return ms, status, doc.get("data"), err


# --- threads
def cpu_sampler(S, cg, f):
    w = csv.writer(f)
    w.writerow(["t", "phase", "container", "usage_usec"])
    last_refresh = 0.0
    nxt = time.monotonic()
    while not S.stop.is_set():
        if time.monotonic() - last_refresh > 30:
            cg.refresh()
            last_refresh = time.monotonic()
        t = time.time()
        vals = cg.read()
        phase = S.phase
        with S.lock:
            for n, u in vals.items():
                if u is not None:
                    S.cpu.setdefault(n, []).append((t, u, phase))
                    w.writerow([f"{t:.3f}", phase, n, u])
        f.flush()
        nxt += 1.0
        S.stop.wait(max(0.0, nxt - time.monotonic()))


def probe_thread(S, f):
    w = csv.writer(f)
    w.writerow(["t", "phase", "kind", "value", "unit", "detail", "regime"])
    t0 = time.monotonic()
    next_bench, next_bin = t0, t0 + 10
    bins, pl_dir, pl_prev = [], None, None
    while not S.stop.is_set():
        now = time.monotonic()
        if pl_dir is None:
            cid = C.container_ids().get(C.PLACEMENT)
            pl_dir = C.cgroup_dir(cid)[0] if cid else None
            pl_prev = (C.usage_usec(pl_dir), now) if pl_dir else None
        if now >= next_bin:
            next_bin += 10
            if pl_dir and pl_prev and pl_prev[0] is not None:
                u = C.usage_usec(pl_dir)
                if u is None:
                    pl_dir = None
                else:
                    bins.append((u - pl_prev[0]) / 1000 / (now - pl_prev[1]))
                    pl_prev = (u, now)
            if len(bins) >= 6:
                med = C.pct(bins, .5)
                row = {"t": time.time(), "phase": S.phase, "kind": "placement", "value": round(med, 2), "unit": "ms/s",
                       "detail": " ".join(f"{b:.1f}" for b in bins), "regime": C.regime_of(placement_ms_per_s=med)}
                bins = []
                with S.lock:
                    S.probes.append(row)
                w.writerow(row.values())
                f.flush()
                C.log(f"probe placement {row['value']} ms/s -> {row['regime']}")
        if now >= next_bench:
            next_bench += 30
            b = C.bench()
            row = {"t": b["t"], "phase": S.phase, "kind": "bench", "value": b.get("medianMs"), "unit": "ms",
                   "detail": b.get("error") or f"min {b.get('minMs')} ratio {b.get('ratio')}",
                   "regime": C.regime_of(bench_ratio=b.get("ratio"))}
            with S.lock:
                S.probes.append(row)
            w.writerow(row.values())
            f.flush()
        S.stop.wait(max(0.0, min(next_bench, next_bin) - time.monotonic()))


def load_thread(S, rate, f):
    w = csv.writer(f)
    w.writerow(["t", "phase", "type", "latency_ms", "status", "ok", "error"])
    wl = threading.Lock()
    pool = ThreadPoolExecutor(max_workers=32)
    rnd = random.Random(1)

    def one(kind, t, phase):
        if kind == "product":
            ms, status, data, err = gql(Q_PRODUCT, {"id": rnd.choice(S.ids)})
        else:
            skip = 0
            if S.total_products and S.total_products > 10 and rnd.random() < 0.5:
                skip = rnd.randrange(0, min(S.total_products, 500) - 9)
            ms, status, data, err = gql(Q_PRODUCTS % skip)
            if data and data.get("products"):
                p = data["products"]
                S.total_products = p.get("totalCount")
                with S.lock:
                    for n in p.get("nodes") or []:
                        if n.get("id") and n["id"] not in S.ids and len(S.ids) < 1000:
                            S.ids.append(n["id"])
        row = {"t": round(t, 3), "phase": phase, "type": kind, "latency_ms": round(ms, 2), "status": status,
               "ok": 0 if err else 1, "error": err}
        with wl:
            w.writerow(row.values())
        with S.lock:
            S.loads.append(row)

    nxt = time.monotonic()
    while not S.stop.is_set():
        if not S.load_on.is_set():
            S.load_on.wait(0.5)
            nxt = time.monotonic()
            continue
        nxt += rnd.expovariate(rate)          # Poisson arrivals
        S.stop.wait(max(0.0, nxt - time.monotonic()))
        if S.stop.is_set() or not S.load_on.is_set():
            continue
        kind = "product" if S.ids and rnd.random() < 0.4 else "products"
        pool.submit(one, kind, time.time(), S.phase)
        if int(nxt) % 5 == 0:
            with wl:
                f.flush()
    pool.shutdown(wait=True, cancel_futures=True)
    f.flush()


# --- summary
def cpu_ms_per_s(series, phase):
    """mean CPU (ms per s) of a container over the samples of one phase, robust to counter resets (restarts)."""
    pts = [(t, u) for t, u, p in series if p == phase]
    if len(pts) < 2:
        return None
    used = sum(max(0, b[1] - a[1]) for a, b in zip(pts, pts[1:]))
    return used / 1000 / (pts[-1][0] - pts[0][0])


def summarize(S, a, status, meta):
    with S.lock:
        cpu = {n: list(v) for n, v in S.cpu.items()}
        loads, probes = list(S.loads), list(S.probes)
        phases = [list(p) for p in S.phases]
    per = {}
    for n, series in sorted(cpu.items()):
        short = n[len(C.PREFIX):].rsplit("-1", 1)[0] if n.endswith("-1") else n[len(C.PREFIX):]
        per[short] = {ph: (round(v, 2) if v is not None else None) for ph in ("idle", "warm", "measure")
                      for v in [cpu_ms_per_s(series, ph)]}
    meas = [r for r in loads if r["phase"] == "measure"]
    dur = next((p[2] - p[1] for p in phases if p[0] == "measure" and p[2]), None)
    tput = len(meas) / dur if dur else None
    ok = [r for r in meas if r["ok"]]
    lat = {}
    for kind in sorted({r["type"] for r in meas}):
        xs = [r["latency_ms"] for r in meas if r["type"] == kind and r["ok"]]
        lat[kind] = {"n": sum(1 for r in meas if r["type"] == kind), "ok": len(xs),
                     **({k: round(C.pct(xs, q), 1) for k, q in [("p50", .5), ("p90", .9), ("p99", .99)]} if xs else {})}
    errs = {}
    for r in meas:
        if not r["ok"]:
            k = f"{r['status']} {r['error'][:90]}"
            errs[k] = errs.get(k, 0) + 1
    per_req = {}
    for g, members in GROUPS.items():
        m = [per.get(x, {}).get("measure") for x in members]
        i = [per.get(x, {}).get("idle") for x in members]
        if tput and all(v is not None for v in m):
            gross = sum(m) / tput
            net = (sum(m) - sum(i)) / tput if all(v is not None for v in i) else None
            per_req[g] = {"grossMsPerReq": round(gross, 3), "netOfIdleMsPerReq": round(net, 3) if net is not None
                          else None, "measureMsPerS": round(sum(m), 2),
                          "idleMsPerS": round(sum(i), 2) if all(v is not None for v in i) else None}
    tot_m = sum(v["measure"] for v in per.values() if v.get("measure") is not None)
    tot_i = sum(v["idle"] for v in per.values() if v.get("idle") is not None)
    # regime over time: per minute of the run, placement decides when present, else the bench (speedprobe rule)
    t0 = phases[0][1] if phases else time.time()
    minutes = {}
    for r in probes:
        mm = int((r["t"] - t0) // 60)
        d = minutes.setdefault(mm, {"minute": mm, "phase": r["phase"]})
        if r["kind"] == "placement":
            d["placementMsPerS"] = r["value"]
        elif r["value"] is not None:
            d.setdefault("benchMs", []).append(r["value"])
    timeline = []
    for mm in sorted(minutes):
        d = minutes[mm]
        bm = C.pct(d.get("benchMs", []), .5)
        d["benchMs"] = round(bm, 2) if bm is not None else None
        d["regime"] = C.regime_of(d.get("placementMsPerS"), bm / C.BENCH_FAST_MS if bm else None)
        d["rule"] = "placement" if d.get("placementMsPerS") is not None else "bench"
        timeline.append(d)
    verdicts = [d["regime"] for d in timeline if d["regime"]]
    verdicts_m = [d["regime"] for d in timeline if d["regime"] and d["phase"] == "measure"]
    benches = [r["value"] for r in probes if r["kind"] == "bench" and r["value"] is not None]
    lq = C.pct(benches, .25)
    return {
        "status": status, **meta, "phases": [{"name": p[0], "start": p[1], "end": p[2],
                                              "seconds": round(p[2] - p[1], 1) if p[2] else None} for p in phases],
        "load": {"rateTarget": a.rate, "measureRequests": len(meas), "measureOk": len(ok),
                 "throughputPerS": round(tput, 3) if tput else None,
                 "errorRate": round(1 - len(ok) / len(meas), 4) if meas else None, "latencyMs": lat,
                 "errors": dict(sorted(errs.items(), key=lambda kv: -kv[1])[:15]),
                 "productsInCatalog": S.total_products, "productIdsSeen": len(S.ids)},
        "cpuPerRequest": per_req,
        "stackMsPerS": {"idle": round(tot_i, 1), "measure": round(tot_m, 1)},
        "containersMsPerS": per,
        "regime": {"timeline": timeline, "fastMinutes": verdicts.count("fast"), "slowMinutes": verdicts.count("slow"),
                   "measureVerdict": None if not verdicts_m else "fast" if "slow" not in verdicts_m else
                   "slow" if "fast" not in verdicts_m else "mixed",
                   "benchMedianMs": round(C.pct(benches, .5), 2) if benches else None,
                   "benchOwnLowerQuartileMs": round(lq, 2) if lq else None,
                   "benchSlowVsOwn": sum(1 for b in benches if b > C.BENCH_SLOW_RATIO * lq) if lq else None,
                   "benchSlowVsVm": sum(1 for b in benches if b > C.BENCH_SLOW_RATIO * C.BENCH_FAST_MS),
                   "benchSamples": len(benches),
                   "thresholds": {"placementSlowMsPerS": C.PLACEMENT_SLOW, "benchFastMsVm": C.BENCH_FAST_MS,
                                  "benchSlowRatio": C.BENCH_SLOW_RATIO}},
    }


def summary_text(s):
    L = [f"smoke {s['status']}  images :{s.get('imageTag')}  commit {s.get('commit')}  started {s.get('started')}"]
    for p in s["phases"]:
        L.append(f"  phase {p['name']:<8} {p['seconds']} s")
    ld = s["load"]
    L.append(f"load: {ld['measureRequests']} requests in measure, {ld['throughputPerS']} /s (target {ld['rateTarget']}), "
             f"error rate {ld['errorRate']}, products in catalog {ld['productsInCatalog']}")
    for k, v in ld["latencyMs"].items():
        L.append(f"  {k:<9} n={v['n']} ok={v['ok']} p50 {v.get('p50')} p90 {v.get('p90')} p99 {v.get('p99')} ms")
    for k, v in ld["errors"].items():
        L.append(f"  error x{v}: {k}")
    L.append("CPU per request (ms CPU / request; net = minus the idle phase's background):")
    for g, v in s["cpuPerRequest"].items():
        L.append(f"  {g:<22} gross {v['grossMsPerReq']}  net {v['netOfIdleMsPerReq']}  "
                 f"(measure {v['measureMsPerS']} ms/s, idle {v['idleMsPerS']} ms/s)")
    L.append(f"stack total: idle {s['stackMsPerS']['idle']} ms/s, measure {s['stackMsPerS']['measure']} ms/s")
    L.append("per container mean CPU ms/s (idle / warm / measure), top 25 by measure:")
    top = sorted(s["containersMsPerS"].items(), key=lambda kv: -(kv[1].get("measure") or 0))[:25]
    for n, v in top:
        L.append(f"  {n:<26} {v['idle']} / {v['warm']} / {v['measure']}")
    r = s["regime"]
    L.append(f"regime: measure verdict {r['measureVerdict']}, {r['fastMinutes']} fast / {r['slowMinutes']} slow minutes; "
             f"bench median {r['benchMedianMs']} ms (own LQ {r['benchOwnLowerQuartileMs']}, VM fast "
             f"{r['thresholds']['benchFastMsVm']}); slow benches vs own {r['benchSlowVsOwn']}, vs VM {r['benchSlowVsVm']}"
             f" of {r['benchSamples']}")
    for d in r["timeline"]:
        L.append(f"  min {d['minute']:>3} {d['phase']:<8} placement {d.get('placementMsPerS')} ms/s bench "
                 f"{d.get('benchMs')} ms -> {d['regime']} ({d['rule']})")
    return "\n".join(L) + "\n"


# --- stack
def git(*args):
    r = C.run(["git", "-c", f"safe.directory={C.ROOT}", "-C", C.ROOT] + list(args))
    return r.stdout.strip() if r.returncode == 0 else None


def image_present(ref):
    return C.run(["docker", "image", "inspect", ref]).returncode == 0


def prepare_images(a, out, env):
    tag = a.tag or "dev"
    missing = [s for s in C.SERVICES if not image_present(f"misarch-kotlin/{s}:{tag}")]
    if missing:
        if not a.build_missing:
            raise SystemExit(f"missing images misarch-kotlin/<svc>:{tag} for {missing}: run `deploy/server/run.sh "
                             f"images{' --tag ' + a.tag if a.tag else ''}` first or pass --build-missing")
        C.log(f"building missing images: {missing}")
        cmd = [os.path.join(C.HERE, "run.sh"), "images", "--services", ",".join(missing)] + \
              (["--tag", a.tag] if a.tag else [])
        if subprocess.run(cmd, env=env).returncode != 0:
            raise SystemExit("image build failed")
    ids = {s: C.run(["docker", "image", "inspect", "-f", "{{.Id}}", f"misarch-kotlin/{s}:{tag}"]).stdout.strip()
           for s in C.SERVICES}
    if a.tag:
        # compose only names :dev; pin the TAG images by an override instead of retagging (:dev stays untouched).
        # up.sh still requires :dev to exist, so a missing :dev is tagged from TAG (logged).
        ov = os.path.join(out, "compose-images.yaml")
        with open(ov, "w") as f:
            f.write(f"# generated by deploy/server/smoke.py: the 17 apps on misarch-kotlin/<svc>:{a.tag}\nservices:\n")
            for s in C.SERVICES:
                f.write(f"  {s}:\n    image: misarch-kotlin/{s}:{a.tag}\n")
        env["COMPOSE_FILE"] = os.path.join(COMPOSE_DIR, "docker-compose.yaml") + ":" + ov
        for s in C.SERVICES:
            if not image_present(f"misarch-kotlin/{s}:dev"):
                C.log(f"tagging misarch-kotlin/{s}:{a.tag} as :dev (up.sh requires :dev to exist)")
                C.run(["docker", "tag", f"misarch-kotlin/{s}:{a.tag}", f"misarch-kotlin/{s}:dev"])
    return ids


def wait_gateway(timeout_s):
    end = time.monotonic() + timeout_s
    last = ""
    while time.monotonic() < end:
        ms, status, data, err = gql("{ __typename }", timeout=10)
        if status == 200 and not err:
            return True
        if err != last:
            C.log(f"gateway not ready: {status} {err}")
            last = err
        time.sleep(5)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=20)
    ap.add_argument("--tag")
    ap.add_argument("--keep-up", action="store_true")
    ap.add_argument("--build-missing", action="store_true")
    ap.add_argument("--rate", type=float, default=5.0, help="requests per second (open loop)")
    ap.add_argument("--warmup-s", type=float, default=120)
    ap.add_argument("--idle-s", type=float, default=60)
    ap.add_argument("--settle-s", type=float, default=60)
    a = ap.parse_args()
    out = C.results_dir("smoke")
    env = dict(os.environ)
    S = State()
    meta = {"started": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "jobId": os.environ.get("JOB_ID"),
            "commit": git("rev-parse", "HEAD"), "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
            "imageTag": a.tag or "dev", "args": vars(a), "gateway": GATEWAY}
    status, stack_touched, threads, files = "done", False, [], []

    def on_signal(signum, _f):
        for sg in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(sg, signal.SIG_IGN)
        raise Stop(signal.Signals(signum).name)
    for sg in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sg, on_signal)

    try:
        C.log(f"smoke: images :{a.tag or 'dev'}, measure {a.minutes} min at {a.rate}/s -> {out}")
        if C.run(["docker", "ps", "-q", "--filter", "name=^misarch-gateway-1$"]).stdout.strip():
            raise SystemExit("the original MiSArch (project misarch) is running - stop it first")
        meta["imageIds"] = prepare_images(a, out, env)
        S.set_phase("up")
        stack_touched = True
        t_up = time.time()
        r = subprocess.run([os.path.join(COMPOSE_DIR, "up.sh")], env=env, timeout=1500)
        if r.returncode != 0:
            raise SystemExit(f"up.sh failed ({r.returncode})")
        if not wait_gateway(600):
            raise SystemExit("gateway did not answer { __typename } within 10 min after up.sh")
        meta["upSeconds"] = round(time.time() - t_up, 1)
        cg = C.CgroupMap()
        cg.refresh()
        meta["containers"] = len(cg.dirs)
        meta["cgroupLayouts"] = sorted(set(cg.layouts.values()))
        C.log(f"stack up in {meta['upSeconds']} s, {len(cg.dirs)} containers, cgroup {meta['cgroupLayouts']}")
        files = [open(os.path.join(out, n), "w", newline="") for n in ("cpu.csv", "probes.csv", "load.csv")]
        threads = [threading.Thread(target=cpu_sampler, args=(S, cg, files[0]), daemon=True),
                   threading.Thread(target=probe_thread, args=(S, files[1]), daemon=True),
                   threading.Thread(target=load_thread, args=(S, a.rate, files[2]), daemon=True)]
        for t in threads:
            t.start()
        for name, secs, load in (("settle", a.settle_s, False), ("idle", a.idle_s, False),
                                 ("warm", a.warmup_s, True), ("measure", a.minutes * 60, True)):
            S.set_phase(name)
            (S.load_on.set if load else S.load_on.clear)()
            end = time.monotonic() + secs
            while time.monotonic() < end:
                time.sleep(min(30.0, max(0.0, end - time.monotonic())))
                if name in ("warm", "measure"):
                    with S.lock:
                        lp = [r for r in S.loads if r["phase"] == name]
                    nerr = sum(1 for r in lp if not r["ok"])
                    C.log(f"{name}: {len(lp)} requests, {nerr} errors, {len(S.ids)} product ids, "
                          f"catalog {S.total_products} products")
        S.load_on.clear()
        S.set_phase("end")
    except Stop as e:
        status = f"cancelled ({e})"
        C.log(f"smoke: {status}, cleaning up")
    except SystemExit as e:
        status = f"failed: {e}"
        C.log(f"smoke: {status}")
    except Exception as e:
        status = f"failed: {type(e).__name__}: {e}"
        C.log(f"smoke: {status}")
    finally:
        S.load_on.clear()
        S.stop.set()
        for t in threads:
            t.join(timeout=10 if status == "done" else 3)
        for f in files:
            try:
                f.close()
            except Exception:
                pass
        with S.lock:
            if S.phases and S.phases[-1][2] is None:
                S.phases[-1][2] = time.time()
        meta["ended"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        s = summarize(S, a, status, meta)
        C.write_json(os.path.join(out, "summary.json"), s)
        txt = summary_text(s)
        open(os.path.join(out, "summary.txt"), "w").write(txt)
        print(txt, flush=True)
        if stack_touched and not a.keep_up:
            if status == "done" or status.startswith("failed"):
                C.log("deploy/compose/down.sh (stop, volumes kept)")
                subprocess.run([os.path.join(COMPOSE_DIR, "down.sh")], env=env)
            else:   # cancelled: the manager's grace period is 30 s
                C.log("docker compose stop -t 5")
                subprocess.run(["docker", "compose", "stop", "-t", "5"], cwd=COMPOSE_DIR, env=env)
        elif a.keep_up:
            C.log("--keep-up: stack left running")
    return 0 if status == "done" else 1


if __name__ == "__main__":
    sys.exit(main())
