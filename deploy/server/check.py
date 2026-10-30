#!/usr/bin/env python3
"""Preflight of the experiment server (deploy/server/run.sh check): prints an OK/WARN/FAIL/INFO table, writes
$RESULTS_DIR/host.json (+ check.json) and exits 1 if any row is FAIL.

    python3 deploy/server/check.py [--campaign]

--campaign turns the third-pass preconditions (calibration dir, palladio repo, venv, image tags CUT_TAG / TIMING_TAG /
MECH_TAG, plans, data baseline, locks) from INFO/WARN into FAIL rows and also runs `timing/run-third-pass.sh --check`.
"""
import argparse
import json
import os
import re
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402

WS = os.environ.get("WORKSPACE", "/home/devbox/workspace")
CAL = os.path.join(WS, "misarch-optimization", "calibration")
PAL = os.path.join(WS, "palladio-architecture-optimization-domain")
LOCKDIR = os.path.join(WS, "misarch-optimization")
PORTS = [8080, 8081, 6379, 3510]
PY_LIBS = ["numpy", "scipy", "pandas", "yaml", "aiohttp", "requests"]
TOOLS = ["bash", "git", "curl", "jq", "rsync", "zstd", "nsenter", "lscpu", "numactl", "flock", "setsid", "pgrep"]

rows = []


def add(status, name, detail=""):
    rows.append((status, name, str(detail)))


def listening_ports():
    """TCP ports in LISTEN state in this network namespace (host, with --network host)."""
    ports = set()
    for p in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            for line in open(p).readlines()[1:]:
                f = line.split()
                if len(f) > 3 and f[3] == "0A":
                    ports.add(int(f[1].rsplit(":", 1)[1], 16))
        except OSError:
            pass
    return ports


def free_gb(path):
    try:
        s = os.statvfs(path)
        return s.f_bavail * s.f_frsize / 1e9
    except OSError:
        return None


def check_docker():
    r = C.run(["docker", "info", "--format", "{{json .}}"], timeout=30)
    if r.returncode != 0:
        add("FAIL", "docker", (r.stderr or r.stdout).strip()[:200])
        return None
    info = json.loads(r.stdout)
    add("OK", "docker", f"server {info.get('ServerVersion')}, cgroup v{info.get('CgroupVersion')} "
                        f"driver {info.get('CgroupDriver')}, root {info.get('DockerRootDir')}, "
                        f"{info.get('NCPU')} CPUs, {int(info.get('MemTotal', 0)) / 2**30:.1f} GiB")
    r = C.run(["docker", "compose", "version", "--short"])
    v = r.stdout.strip().lstrip("v")
    if r.returncode == 0 and v and v.split(".")[0].isdigit() and int(v.split(".")[0]) >= 2:
        add("OK", "docker compose", v)
    else:
        add("FAIL", "docker compose", f"need the compose v2+ plugin: {(r.stdout + r.stderr).strip()[:150]}")
    return info


def check_cgroup(info):
    """busybox sleep 30 -> cgroup dir from its full Id -> two usage reads. Returns the busybox id or None."""
    if info is None:
        add("FAIL", "container cgroup", "docker not reachable")
        return None
    r = C.run(["docker", "run", "--rm", "-d", "--name", f"misarch-server-check-{os.getpid()}", "busybox", "sleep", "30"],
              timeout=180)
    if r.returncode != 0:
        add("FAIL", "container cgroup", f"docker run busybox failed: {r.stderr.strip()[:200]}")
        return None
    cid = r.stdout.strip()
    d, layout = C.cgroup_dir(cid)
    if not d:
        add("FAIL", "container cgroup", f"no cgroup dir for {cid[:12]} under /sys/fs/cgroup (needs --cgroupns host "
                                        "and the host's /sys/fs/cgroup)")
        return cid
    u0 = C.usage_usec(d)
    time.sleep(1)
    u1 = C.usage_usec(d)
    if u0 is None or u1 is None:
        add("FAIL", "container cgroup", f"{d}: cpu usage not readable")
        return cid
    add("OK", "container cgroup", f"{layout}: {d} (usage_usec {u1})")
    if layout != "v2-systemd":
        add("WARN", "cgroup layout", "palladio timing/harness/speedprobe.py only knows "
                                     "/sys/fs/cgroup/system.slice/docker-<id>.scope: its placement probe will return "
                                     "nothing, the campaign's regime rule then falls back to the bench")
    return cid


def check_disk(info, busybox_id):
    paths = {"workspace": WS if os.path.isdir(WS) else os.path.expanduser("~"), "/ (container root)": "/"}
    vals = {k: free_gb(p) for k, p in paths.items()}
    root = (info or {}).get("DockerRootDir")
    if root and busybox_id:
        r = C.run(["docker", "run", "--rm", "-v", f"{root}:/d:ro", "busybox", "df", "-Pk", "/d"], timeout=60)
        try:
            vals[f"docker root {root}"] = int(r.stdout.splitlines()[-1].split()[3]) * 1024 / 1e9
        except (IndexError, ValueError):
            pass
    known = {k: v for k, v in vals.items() if v is not None}
    detail = ", ".join(f"{k} {v:.1f} GB" for k, v in known.items())
    low = min(known.values()) if known else 0
    add("FAIL" if low < 15 else "WARN" if low < 30 else "OK", "free disk", detail + " (warn < 30, fail < 15)")


def check_host():
    mi = C.meminfo()
    tot, av = mi.get("MemTotal", 0) / 2**20, mi.get("MemAvailable", 0) / 2**20
    add("FAIL" if tot < 12 else "OK", "RAM", f"{tot:.1f} GiB total, {av:.1f} GiB available "
                                              "(stack ~10-11 GB; fail < 12 total)")
    if tot >= 12 and av < 11:
        add("WARN", "RAM available", f"{av:.1f} GiB available < 11: something else is using memory")
    aff = len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None
    add("WARN" if (os.cpu_count() or 0) < 4 else "INFO", "cores", f"{os.cpu_count()} online, {aff} in our affinity")
    lscpu = C.run(["lscpu"]).stdout
    model = re.search(r"Model name:\s*(.*)", lscpu)
    hyp = re.search(r"Hypervisor vendor:\s*(.*)", lscpu)
    tpc = re.search(r"Thread\(s\) per core:\s*(.*)", lscpu)
    numa = re.search(r"NUMA node\(s\):\s*(.*)", lscpu)
    add("INFO", "cpu", f"{model.group(1).strip() if model else '?'}; threads/core {tpc.group(1) if tpc else '?'}, "
                       f"NUMA nodes {numa.group(1) if numa else '?'}, hypervisor {hyp.group(1) if hyp else 'none'}")
    f = C.cpufreq_info()
    add("INFO", "cpufreq", f"driver {f['driver']}, governors {f['governors'] or '-'}, intel_pstate "
                           f"{f['intelPstateStatus']} no_turbo={f['intelPstateNoTurbo']}, boost={f['boost']}, "
                           f"amd_pstate {f['amdPstateStatus']}, EPP {f['eppPreferences'] or '-'}, "
                           f"SMT active={f['smtActive']} ({f['smtControl']})")
    src, mhz = C.cpu_mhz()
    if mhz:
        add("INFO", "cpu MHz", f"{src}: min {min(mhz):.0f}, median {C.pct(mhz, .5):.0f}, max {max(mhz):.0f}")
    st = C.proc_stat()
    add("INFO", "steal since boot", f"{100.0 * st['steal'] / max(1, st['total']):.2f} % of all CPU time")
    add("INFO", "kernel", f"{os.uname().release}; cmdline {C.read_text('/proc/cmdline', '?')[:160]}")


def check_tools():
    java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
    r = C.run([java, "-version"])
    m = re.search(r'version "(\d+)', r.stderr + r.stdout)
    if m and m.group(1) == "25":
        add("OK", "java 25", f"{java}: {(r.stderr or r.stdout).splitlines()[0]}")
    else:
        add("FAIL", "java 25", f"{java}: {(r.stderr + r.stdout).strip().splitlines()[:1] or 'not found'} "
                               "(Gradle would try to download a JDK 25 via foojay)")
    bad = []
    for lib in PY_LIBS:
        if C.run(["python3", "-c", f"import {lib}"]).returncode != 0:
            bad.append(lib)
    add("FAIL" if bad else "OK", "python libs", f"missing {bad}" if bad else f"python3 {sys.version.split()[0]}: "
                                                                            + ", ".join(PY_LIBS))
    miss = [t for t in TOOLS if not shutil.which(t)]
    add("WARN" if miss else "OK", "tools", f"missing {miss}" if miss else " ".join(TOOLS))
    gh = os.environ.get("GRADLE_USER_HOME") or ("/home/devbox/.gradle" if os.path.isdir("/home/devbox/.gradle")
                                                else os.path.expanduser("~/.gradle"))
    add("INFO", "gradle home", f"{gh} ({'exists' if os.path.isdir(gh) else 'new'}; run.sh images uses it)")


def check_stacks(info):
    if info is None:
        return False
    r = C.run(["docker", "ps", "--format", "{{.Names}}"])
    names = r.stdout.split()
    ours = [n for n in names if n.startswith("misarch-kotlin-")]
    orig = [n for n in names if n.startswith("misarch-") and not n.startswith("misarch-kotlin-")
            and not n.startswith("misarch-server-")]
    other = [n for n in names if not n.startswith("misarch-")]
    add("FAIL" if orig else "OK", "original MiSArch", f"running: {orig[:5]}" if orig else "not running")
    add("WARN" if ours else "OK", "misarch-kotlin stack",
        f"{len(ours)} containers already running (smoke reuses them; campaign's timing-up.sh takes over)"
        if ours else "not running")
    add("WARN" if other else "OK", "other containers", f"{len(other)} running: {other[:8]} (machine not quiet)"
        if other else "none")
    lp = listening_ports()
    held = [p for p in PORTS if p in lp]
    if held and ours:
        add("WARN", "host ports", f"{held} in use (by the running misarch-kotlin stack?)")
    else:
        add("FAIL" if held else "OK", "host ports", f"{held} in use" if held else f"{PORTS} free")
    have = [s for s in C.SERVICES if C.run(["docker", "image", "inspect", f"misarch-kotlin/{s}:dev"]).returncode == 0]
    add("INFO", "images :dev", f"{len(have)}/17 misarch-kotlin/<svc>:dev present"
        + ("" if len(have) == 17 else f" (missing {sorted(set(C.SERVICES) - set(have))}; run.sh images)"))
    r = C.run(["docker", "image", "ls", "--format", "{{.Repository}}:{{.Tag}}", "--filter", "reference=misarch-kotlin/*"])
    tags = {}
    for ref in r.stdout.split():
        tags.setdefault(ref.split(":", 1)[1], set()).add(ref.split(":", 1)[0])
    add("INFO", "image tags", ", ".join(f"{t} ({len(v)})" for t, v in sorted(tags.items())) or "none")
    return True


def lock_state(p):
    if not os.path.exists(p):
        return None
    return C.read_text(p, "")[:200].replace("\n", " ")


def image_exists(ref):
    return C.run(["docker", "image", "inspect", ref]).returncode == 0


def check_campaign(strict):
    lvl = "FAIL" if strict else "INFO"
    lv2 = "FAIL" if strict else "WARN"
    cal_ok, pal_ok = os.path.isdir(CAL), os.path.isdir(PAL)
    add("OK" if cal_ok else lvl, "calibration dir", CAL + ("" if cal_ok else " missing (copied tonight)"))
    add("OK" if pal_ok else lvl, "palladio repo", PAL + ("" if pal_ok else " missing"))
    m = lock_state(os.path.join(LOCKDIR, "MEASURING.lock"))
    if m is not None:
        add("WARN" if not strict else "FAIL", "MEASURING.lock", f"exists ({m}): tools/build.sh and the campaign wait/refuse")
    s = lock_state(os.path.join(LOCKDIR, "STACK.lock"))
    if s is not None and not s.startswith("T3 "):
        add(lv2, "STACK.lock", f"held by someone else: {s}")
    if cal_ok:
        vpy = os.path.join(CAL, ".venv", "bin", "python")
        r = C.run([vpy, "-c", "import sys; print('%d.%d' % sys.version_info[:2])"])
        mine = "%d.%d" % sys.version_info[:2]
        if r.returncode == 0 and r.stdout.strip() == mine:
            add("OK", "calibration venv", f"{vpy}: python {mine}")
        else:
            add("INFO", "calibration venv", f"{vpy}: {'python ' + r.stdout.strip() if r.returncode == 0 else 'broken'}"
                                            f" (container {mine}): `run.sh campaign` recreates it")
    if not pal_ok:
        return
    tim = os.path.join(PAL, "timing")
    cut, ttag, mtag = (os.environ.get("CUT_TAG", "cut-20261029"), os.environ.get("TIMING_TAG", "timing-20261029"),
                       os.environ.get("MECH_TAG", "mech-20261029"))
    missing = [f"{s}:{ttag}" for s in C.SERVICES if not image_exists(f"misarch-kotlin/{s}:{ttag}")]
    missing += [f"{s}:{cut}" for s in ["address", "catalog", "discount", "notification", "return", "shipment"]
                if not image_exists(f"misarch-kotlin/{s}:{cut}")]
    missing += [f"{s}:{mtag}" for s in ["order", "catalog", "shipment"] if not image_exists(f"misarch-kotlin/{s}:{mtag}")]
    pins = os.path.join(CAL, "compose-cut", "docker-compose.cut.yaml")
    if os.path.isfile(pins):
        for img in re.findall(r"image:\s*(\S+)", open(pins).read()):
            if not image_exists(img):
                missing.append(img)
    elif cal_ok:
        add(lvl, "cut pins", f"{pins} missing")
    add(lvl if missing else "OK", "campaign images",
        f"CUT_TAG={cut} TIMING_TAG={ttag} MECH_TAG={mtag}: " + (f"missing {len(missing)}: {missing[:8]}"
                                                                 if missing else "all present"))
    ci = os.path.join(tim, "docker-compose.cut-indexes.yaml")
    if not (os.path.isfile(ci) and cut in open(ci).read()):
        add(lvl, "cut-indexes pins", f"{ci} does not pin :{cut}")
    for f, hint in [("plans/approx3/manifest.json", "gen_plans.py approx3"), ("plans/approx3/bursts.json", "burst_table.py"),
                    ("data/baseline_counts.json", "timing/data/README.md"), ("data/profile.json", "")]:
        p = os.path.join(tim, f)
        add("OK" if os.path.isfile(p) else lvl, f"timing/{f}", "present" if os.path.isfile(p) else f"missing ({hint})")
    bdir = os.path.join(tim, "data", "baseline")
    if os.path.isdir(bdir):
        n = sum(len(fs) for _, _, fs in os.walk(bdir))
        add("OK" if n else lvl, "data baseline dir", f"timing/data/baseline: {n} files")
    else:
        add(lvl, "data baseline dir", "timing/data/baseline missing")
    if strict and cal_ok:
        r = C.run([os.path.join(tim, "run-third-pass.sh"), "--check"], timeout=300, cwd=PAL)
        out = (r.stdout + r.stderr).strip().replace("\n", " | ")
        add("OK" if r.returncode == 0 else "FAIL", "run-third-pass --check", out[-300:])


def host_facts(info):
    def j(cmd):
        r = C.run(cmd)
        try:
            return json.loads(r.stdout)
        except ValueError:
            return r.stdout or r.stderr
    keys = ["ServerVersion", "CgroupDriver", "CgroupVersion", "NCPU", "MemTotal", "DockerRootDir", "Driver",
            "KernelVersion", "OperatingSystem", "Architecture", "Containers", "ContainersRunning", "Images",
            "SecurityOptions", "Runtimes", "DefaultRuntime", "LiveRestoreEnabled"]
    return {
        "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "jobId": os.environ.get("JOB_ID"),
        "uname": dict(zip(["sysname", "nodename", "release", "version", "machine"], os.uname())),
        "cmdline": C.read_text("/proc/cmdline"), "osRelease": C.read_text("/etc/os-release"),
        "lscpu": j(["lscpu", "-J"]), "numactl": C.run(["numactl", "-H"]).stdout,
        "meminfo": C.meminfo(), "cpufreq": C.cpufreq_info(), "cpuMHz": C.cpu_mhz(),
        "procStat": C.proc_stat(), "loadavg": C.loadavg(),
        "cpuOnline": C.read_text("/sys/devices/system/cpu/online"),
        "docker": {k: (info or {}).get(k) for k in keys},
        "vulnerabilities": {os.path.basename(p): C.read_text(p) for p in
                            sorted(__import__("glob").glob("/sys/devices/system/cpu/vulnerabilities/*"))},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--campaign", action="store_true", help="campaign preconditions are FAIL rows")
    a = ap.parse_args()
    out = C.results_dir("check")
    info = check_docker()
    bid = check_cgroup(info)
    try:
        check_disk(info, bid)
    finally:
        if bid:
            C.run(["docker", "rm", "-f", bid])
    check_host()
    check_tools()
    check_stacks(info)
    check_campaign(a.campaign)
    C.write_json(os.path.join(out, "host.json"), host_facts(info))
    C.write_json(os.path.join(out, "check.json"), [{"status": s, "check": n, "detail": d} for s, n, d in rows])
    w = max(len(n) for _, n, _ in rows)
    for s, n, d in rows:
        print(f"{s:<5} {n:<{w}}  {d}")
    nfail = sum(1 for s, _, _ in rows if s == "FAIL")
    print(f"\n{'FAIL' if nfail else 'OK'}: {nfail} failed, {sum(1 for s, _, _ in rows if s == 'WARN')} warnings; "
          f"host facts in {out}/host.json")
    return 1 if nfail else 0


if __name__ == "__main__":
    sys.exit(main())
