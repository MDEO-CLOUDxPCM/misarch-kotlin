#!/usr/bin/env python3
"""Make the calibration venv usable in the runner container (deploy/server/run.sh campaign).

    python3 deploy/server/calvenv.py [CALIBRATION_DIR]

The .venv copied from the dev VM points at the VM's interpreter. If `.venv/bin/python -c 'import sys'` fails or its
Python version differs from this python3, it is moved aside (.venv.vm-<timestamp>) and recreated with
`python3 -m venv --system-site-packages .venv` (numpy/scipy/pandas/yaml/aiohttp/requests come from the container),
then `pip install -r` every requirements*.txt in the calibration dir. Finally every third-party top-level import of
calibration/scripts/*.py and timing/harness/*.py is tried with the venv python; missing ones are pip-installed
(best effort, needs network) and reported. Exit 1 if the venv python does not work or imports stay missing.
"""
import ast
import glob
import os
import subprocess
import sys
import time

WS = os.environ.get("WORKSPACE", "/home/devbox/workspace")
CAL = sys.argv[1] if len(sys.argv) > 1 else os.path.join(WS, "misarch-optimization", "cases", "misarch", "timing", "calibration")
PAL_HARNESS = os.path.join(WS, "misarch-optimization", "cases", "misarch", "timing", "harness")
PIP_NAME = {"yaml": "pyyaml", "sklearn": "scikit-learn", "dateutil": "python-dateutil", "PIL": "pillow"}


def log(*a):
    print("[calvenv]", *a, flush=True)


def venv_version(py):
    try:
        r = subprocess.run([py, "-c", "import sys; print('%d.%d' % sys.version_info[:2])"], capture_output=True,
                           text=True, timeout=30)
        return r.stdout.strip() if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def third_party_imports(dirs):
    local = set()
    files = []
    for d in dirs:
        fs = glob.glob(os.path.join(d, "*.py"))
        files += fs
        local |= {os.path.splitext(os.path.basename(f))[0] for f in fs}
    std = set(getattr(sys, "stdlib_module_names", ()))
    mods = set()
    for f in files:
        try:
            tree = ast.parse(open(f).read())
        except (SyntaxError, OSError, UnicodeDecodeError):
            continue
        for n in ast.walk(tree):
            if isinstance(n, ast.Import):
                mods |= {a.name.split(".")[0] for a in n.names}
            elif isinstance(n, ast.ImportFrom) and n.module and not n.level:
                mods.add(n.module.split(".")[0])
    return sorted(m for m in mods if m not in std and m not in local and m != "__future__")


def main():
    venv = os.path.join(CAL, ".venv")
    py = os.path.join(venv, "bin", "python")
    mine = "%d.%d" % sys.version_info[:2]
    have = venv_version(py)
    if have != mine:
        if os.path.exists(venv):
            aside = f"{venv}.vm-{time.strftime('%Y%m%d-%H%M%S')}"
            log(f"{py}: {'python ' + have if have else 'not runnable'} (container python {mine}): moving to {aside}")
            os.rename(venv, aside)
        log(f"python3 -m venv --system-site-packages {venv}")
        subprocess.run([sys.executable, "-m", "venv", "--system-site-packages", venv], check=True)
        for req in sorted(glob.glob(os.path.join(CAL, "requirements*.txt"))):
            log(f"pip install -r {req}")
            r = subprocess.run([py, "-m", "pip", "install", "-q", "-r", req])
            if r.returncode != 0:
                log(f"WARNING: pip install -r {req} failed ({r.returncode})")
    else:
        log(f"{py}: python {have}, usable")
    if venv_version(py) != mine:
        log(f"FAIL: {py} still does not run")
        return 1
    mods = third_party_imports([os.path.join(CAL, "scripts"), PAL_HARNESS])
    missing = [m for m in mods if subprocess.run([py, "-c", f"import {m}"], capture_output=True).returncode != 0]
    if missing:
        log(f"missing imports {missing}: pip install")
        subprocess.run([py, "-m", "pip", "install", "-q"] + [PIP_NAME.get(m, m) for m in missing])
        missing = [m for m in missing if subprocess.run([py, "-c", f"import {m}"], capture_output=True).returncode != 0]
    log(f"third-party imports checked: {mods}" + (f"; STILL MISSING {missing}" if missing else ", all importable"))
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
