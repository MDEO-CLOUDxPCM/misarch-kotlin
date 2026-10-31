#!/usr/bin/env python3
"""Shared helpers of deploy/server/run.sh (stdlib only): results dir, container cgroup CPU reader, speed bench,
/proc/stat, cpufreq.

    python3 deploy/server/common.py selftest     cgroup reader against a throwaway busybox container (~15 s)

Cgroup layouts handled (the runner container sees the host's hierarchy: --cgroupns host):
  v2 systemd driver  /sys/fs/cgroup/system.slice/docker-<id>.scope/cpu.stat   (usage_usec)
  v2 cgroupfs driver /sys/fs/cgroup/docker/<id>/cpu.stat
  v1                 /sys/fs/cgroup/cpu,cpuacct/{system.slice/docker-<id>.scope,docker/<id>}/cpuacct.usage (ns)
plus a shallow glob for anything else containing the full container id.
"""
import glob
import json
import os
import re
import statistics as st
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PREFIX = "misarch-kotlin-"
PLACEMENT = f"{PREFIX}placement-1"
SERVICES = ["address", "catalog", "discount", "inventory", "invoice", "media", "notification", "order", "payment",
            "return", "review", "shipment", "shoppingcart", "simulation", "tax", "user", "wishlist"]

# --- speed regime: same probe code and thresholds as
# architecture-optimization/cases/misarch/timing/harness/speedprobe.py (copied, so this runs without that repo)
PLACEMENT_FAST = 28.0
PLACEMENT_SLOW = 32.0
BENCH_FAST_MS = float(os.environ.get("TIMING_BENCH_FAST_MS", "32.6"))   # median on the dev VM, 2026-10-28
BENCH_SLOW_RATIO = 1.3
BENCH_CODE = r"""
import time
def work(n):
    x = 0
    for i in range(n):
        x = (x * 1103515245 + 12345 + i) & 0xffffffff
    return x
r = []
for k in range(7):
    t = time.perf_counter(); work(600000); r.append((time.perf_counter() - t) * 1000)
print(" ".join(f"{x:.3f}" for x in r))
"""


def results_dir(sub=""):
    d = os.environ.get("RESULTS_DIR") or os.path.join(ROOT, "build", "server-results",
                                                      time.strftime("%Y%m%d-%H%M%S") + (f"-{sub}" if sub else ""))
    os.makedirs(d, exist_ok=True)
    return d


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


def run(cmd, timeout=60, **kw):
    """subprocess.run with text output; never raises for a missing binary or a timeout (returncode 127 / 124)."""
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)
    except FileNotFoundError as e:
        return subprocess.CompletedProcess(cmd, 127, "", str(e))
    except subprocess.TimeoutExpired as e:
        return subprocess.CompletedProcess(cmd, 124, e.stdout or "", f"timeout after {timeout}s")


def bench_start():
    return subprocess.Popen([sys.executable, "-I", "-c", BENCH_CODE], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True)


def bench_finish(proc, t, timeout=60):
    try:
        out, _ = proc.communicate(timeout=timeout)
        runs = [float(x) for x in out.split()]
    except Exception as e:
        proc.kill()
        return {"t": t, "error": f"{type(e).__name__}: {e}"[:160]}
    if not runs:
        return {"t": t, "error": "no output"}
    med = st.median(runs)
    return {"t": t, "medianMs": round(med, 2), "minMs": round(min(runs), 2), "runsMs": [round(x, 2) for x in runs],
            "ratio": round(med / BENCH_FAST_MS, 3)}


def bench():
    """speedprobe.bench(): fixed-work LCG loop in a fresh process, {medianMs, minMs, runsMs, ratio vs 32.6 ms}."""
    t = time.time()
    return bench_finish(bench_start(), t)


def regime_of(placement_ms_per_s=None, bench_ratio=None):
    """speedprobe.regime_of: placement decides when it has data, otherwise the bench (ratio > 1.3)."""
    if placement_ms_per_s is not None:
        return "slow" if placement_ms_per_s > PLACEMENT_SLOW else "fast"
    if bench_ratio is not None:
        return "slow" if bench_ratio > BENCH_SLOW_RATIO else "fast"
    return None


# --- containers and cgroups
def container_ids(prefix=PREFIX, running_only=True):
    """{name: full id} of the containers whose name starts with prefix."""
    r = run(["docker", "ps"] + ([] if running_only else ["-a"]) + ["--no-trunc", "--filter", f"name={prefix}",
                                                                    "--format", "{{.ID}}\t{{.Names}}"])
    out = {}
    for line in r.stdout.splitlines():
        cid, _, name = line.partition("\t")
        if name.startswith(prefix):
            out[name] = cid
    return out


def cgroup_dir(cid):
    """(directory, layout) of a container's cgroup, or (None, None)."""
    cands = [(f"/sys/fs/cgroup/system.slice/docker-{cid}.scope", "v2-systemd"),
             (f"/sys/fs/cgroup/docker/{cid}", "v2-cgroupfs"),
             (f"/sys/fs/cgroup/cpu,cpuacct/system.slice/docker-{cid}.scope", "v1-systemd"),
             (f"/sys/fs/cgroup/cpu,cpuacct/docker/{cid}", "v1-cgroupfs"),
             (f"/sys/fs/cgroup/cpuacct/system.slice/docker-{cid}.scope", "v1-systemd"),
             (f"/sys/fs/cgroup/cpuacct/docker/{cid}", "v1-cgroupfs")]
    for d, layout in cands:
        if os.path.isfile(os.path.join(d, "cpu.stat")) or os.path.isfile(os.path.join(d, "cpuacct.usage")):
            return d, layout
    for pat in (f"/sys/fs/cgroup/*/*{cid}*", f"/sys/fs/cgroup/*/*/*{cid}*", f"/sys/fs/cgroup/*/*/*/*{cid}*"):
        for d in glob.glob(pat):
            if os.path.isfile(os.path.join(d, "cpu.stat")) or os.path.isfile(os.path.join(d, "cpuacct.usage")):
                return d, "other"
    return None, None


def usage_usec(d):
    """Cumulative CPU time of a cgroup in microseconds (v2 cpu.stat usage_usec, v1 cpuacct.usage), None if gone."""
    try:
        p = os.path.join(d, "cpu.stat")
        if os.path.isfile(p):
            with open(p) as f:
                for line in f:
                    if line.startswith("usage_usec"):
                        return int(line.split()[1])
        with open(os.path.join(d, "cpuacct.usage")) as f:
            return int(f.read().strip()) // 1000
    except (OSError, ValueError):
        return None
    return None


class CgroupMap:
    """name -> cgroup dir of the running misarch-kotlin-* containers, refreshed on demand."""

    def __init__(self, prefix=PREFIX):
        self.prefix, self.dirs, self.layouts, self.t = prefix, {}, {}, 0.0

    def refresh(self):
        ids = container_ids(self.prefix)
        dirs = {}
        for name, cid in ids.items():
            d, layout = cgroup_dir(cid)
            if d:
                dirs[name] = d
                self.layouts[name] = layout
        self.dirs, self.t = dirs, time.time()
        return dirs

    def read(self):
        return {n: usage_usec(d) for n, d in self.dirs.items()}


# --- host counters
def proc_stat():
    """Aggregate /proc/stat cpu line: dict of jiffies (user nice system idle iowait irq softirq steal) + total."""
    with open("/proc/stat") as f:
        v = [int(x) for x in f.readline().split()[1:]]
    keys = ["user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal"]
    d = dict(zip(keys, v + [0] * (8 - len(v))))
    d["total"] = sum(d[k] for k in keys)
    return d


def stat_delta(a, b):
    """percentages of the interval between two proc_stat() samples."""
    tot = max(1, b["total"] - a["total"])
    pct = {k: 100.0 * (b[k] - a[k]) / tot for k in ("steal", "iowait", "idle")}
    pct["busy"] = 100.0 - pct["idle"] - pct["iowait"]
    return {k: round(x, 2) for k, x in pct.items()}


def cpu_mhz():
    """per-CPU current MHz: cpufreq scaling_cur_freq if present, else /proc/cpuinfo `cpu MHz`; (source, list)."""
    fs = glob.glob("/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq")
    if fs:
        fs.sort(key=lambda p: int(re.search(r"/cpu(\d+)/", p).group(1)))
        vals = []
        for p in fs:
            try:
                vals.append(round(int(open(p).read()) / 1000.0, 1))
            except (OSError, ValueError):
                pass
        if vals:
            return "scaling_cur_freq", vals
    vals = []
    try:
        for line in open("/proc/cpuinfo"):
            if line.startswith("cpu MHz"):
                vals.append(round(float(line.split(":")[1]), 1))
    except OSError:
        pass
    return "cpuinfo", vals


def loadavg():
    return [float(x) for x in open("/proc/loadavg").read().split()[:3]]


def meminfo():
    out = {}
    for line in open("/proc/meminfo"):
        k, _, v = line.partition(":")
        out[k] = int(v.split()[0]) if v.split() and v.split()[0].isdigit() else v.strip()
    return out


def read_text(p, default=None):
    try:
        return open(p).read().strip()
    except OSError:
        return default


def cpufreq_info():
    """governor / driver / turbo / SMT facts (None where the file does not exist, e.g. in many VMs)."""
    c = "/sys/devices/system/cpu"
    govs = sorted({read_text(p) for p in glob.glob(f"{c}/cpu[0-9]*/cpufreq/scaling_governor")} - {None})
    return {
        "governors": govs,
        "driver": read_text(f"{c}/cpu0/cpufreq/scaling_driver"),
        "intelPstateStatus": read_text(f"{c}/intel_pstate/status"),
        "intelPstateNoTurbo": read_text(f"{c}/intel_pstate/no_turbo"),
        "boost": read_text(f"{c}/cpufreq/boost"),
        "amdPstateStatus": read_text(f"{c}/amd_pstate/status"),
        "eppPreferences": sorted({read_text(p) for p in glob.glob(
            f"{c}/cpu[0-9]*/cpufreq/energy_performance_preference")} - {None}),
        "minMaxKHz": [read_text(f"{c}/cpu0/cpufreq/cpuinfo_min_freq"), read_text(f"{c}/cpu0/cpufreq/cpuinfo_max_freq")],
        "smtActive": read_text(f"{c}/smt/active"),
        "smtControl": read_text(f"{c}/smt/control"),
    }


def pct(xs, q):
    """q-quantile (0..1) with linear interpolation; None for an empty list."""
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    k = (len(xs) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1, default=str)
    os.replace(tmp, path)


# --- selftest
def selftest():
    """Start a busybox burning ~0.5 CPU (--cpus 0.5), read its cgroup for 5 s, expect 0.3-0.7 cores."""
    name = f"misarch-server-selftest-{os.getpid()}"
    r = run(["docker", "run", "--rm", "-d", "--name", name, "--cpus", "0.5", "busybox", "sh", "-c",
             "timeout 30 sh -c 'while :; do :; done'"], timeout=180)
    if r.returncode != 0:
        print("FAIL: docker run busybox:", r.stderr.strip()[:300]); return 1
    cid = r.stdout.strip()
    try:
        d, layout = cgroup_dir(cid)
        if not d:
            print(f"FAIL: no cgroup dir for {cid[:12]}"); return 1
        time.sleep(1)
        u0, t0 = usage_usec(d), time.monotonic()
        time.sleep(5)
        u1, t1 = usage_usec(d), time.monotonic()
        cores = (u1 - u0) / 1e6 / (t1 - t0)
        m = CgroupMap("misarch-server-selftest-")
        m.refresh()
        ok = 0.3 <= cores <= 0.7 and name in m.dirs
        print(f"{'OK' if ok else 'FAIL'}: {layout} {d}: {cores:.3f} cores (expected ~0.5), CgroupMap found "
              f"{sorted(m.dirs)}")
        return 0 if ok else 1
    finally:
        run(["docker", "rm", "-f", cid])


if __name__ == "__main__":
    if sys.argv[1:] == ["selftest"]:
        sys.exit(selftest())
    print(__doc__)
    sys.exit(2)
