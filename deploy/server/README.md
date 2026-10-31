# Experiment server jobs (`deploy/server/run.sh`)

Entry point for jobs on the experiment server, run by the web manager inside its runner container (Debian, root,
`--network host --pid host --cgroupns host`, docker socket, host `/home/devbox` at the same path, JDK 25, python3 with
numpy/scipy/pandas/pyyaml/aiohttp/requests). Also safe by hand on the dev VM. Helpers beside it are stdlib-only Python.

Everything worth downloading goes to `$RESULTS_DIR` (default `build/server-results/<timestamp>-<cmd>`). Exit code =
job result. Run it as the job's only command (`exec deploy/server/run.sh ...`): a cancel is SIGTERM to the top process
(30 s grace), and `smoke`, `regime` and `campaign` clean up on it.

| Command | Needs stack | Duration | Outputs |
|---|---|---|---|
| `check [--campaign]` | no | ~30 s | OK/WARN/FAIL/INFO table, `host.json`, `check.json`; exit 1 on FAIL |
| `images [--tag TAG] [--services a,b] [--no-build]` | no | 10-40 min | `misarch-kotlin/<svc>:dev` (+ `:TAG`), `images.json`, `images.log` |
| `regime [--minutes 10] [--interval 30]` | no | as given | `regime.csv`, `regime.json` |
| `smoke [--minutes 20] [--tag TAG] [--keep-up] [--build-missing] [--rate 5]` | brings it up | ~35-45 min | `cpu.csv`, `probes.csv`, `load.csv`, `summary.json`, `summary.txt` |
| `campaign [run-third-pass.sh args]` | via palladio | ~10 h | `calvenv.log`, `raw/` (new files of palladio `timing/raw`) |
| `selftest` | no | ~10 s | cgroup reader vs. a busybox burning 0.5 CPU |

## check
Docker reachable, compose v2+, a container's cgroup CPU readable (busybox `sleep 30`; layouts
`/sys/fs/cgroup/system.slice/docker-<id>.scope` and `/sys/fs/cgroup/docker/<id>`, v1 as fallback), java 25, python
libs, free disk on the workspace, `/` and the docker root (warn < 30 GB, fail < 15 GB), RAM (fail < 12 GB total),
cores, host ports 8080/8081/6379/3510 free, no original MiSArch running (a running misarch-kotlin stack or other
containers are WARN), `:dev` images and tags present, cpufreq governor / driver / turbo / intel_pstate / SMT /
hypervisor (info). Campaign preconditions (calibration dir, palladio repo, calibration venv, images
`CUT_TAG`/`TIMING_TAG`/`MECH_TAG` (env, defaults as in run-third-pass.sh) plus the calibration cut pins, plans,
data baseline, `MEASURING.lock`/`STACK.lock`) are INFO rows; `--campaign` makes them FAIL rows and also runs
`timing/run-third-pass.sh --check`. A non-systemd cgroup layout is a WARN: palladio's `speedprobe.py` only knows the
systemd path, so the campaign's placement probe then returns nothing and its regime rule falls back to the bench.

## images
`deploy/build-images.sh` (Gradle via `tools/build.sh`: waits on `MEASURING.lock`, `MISARCH_MEASURING_LOCK` overrides,
and on free RAM), or `tools/image.sh <svc> --no-build` from existing jars; then `docker tag ... :TAG`.
`GRADLE_USER_HOME` defaults to `/home/devbox/.gradle` (HOME is /root in the container). The JDK 25 toolchain
(`jvmToolchain(25)`, no vendor) is satisfied by the JVM Gradle runs on (`JAVA_HOME`), so foojay auto-provisioning is
not used; the first build downloads Gradle 9.8.1, dependencies, the OTel agent and `eclipse-temurin:25-jre`.
`images.json`: commit, branch, number of changed tracked files, tag, image id per service.

## regime
Every `--interval` s: speedprobe's fixed-work bench in a fresh process (same code and thresholds as
`palladio .../timing/harness/speedprobe.py`), per-CPU MHz idle and during the bench (`scaling_cur_freq`, else
`/proc/cpuinfo`), `/proc/stat` steal/busy/iowait over the interval, loadavg. Summary: slow = bench median > 1.3 x
reference, against the run's own lower-quartile median (`own`) and against the dev VM's 32.6 ms (`vm`), with counts,
switches and a fast/slow/mixed verdict each.

## smoke
Images `:dev` or, with `--tag`, `:TAG` through a compose override in `$RESULTS_DIR` (`COMPOSE_FILE`; `:dev` is not
retagged, except that a missing `:dev` is tagged from TAG because `up.sh` checks for it). Missing images are an error
unless `--build-missing`. `deploy/compose/up.sh`, wait for the gateway, then phases settle 60 s, idle 60 s (no load:
background CPU), warm 120 s, measure `--minutes`. Load: open loop (Poisson, `--rate` req/s), anonymous GraphQL reads
through `http://localhost:8080/graphql` (Gatling's `products` and `product(id)` texts without the employee-only
`internalName`); no seeding, so on an empty catalog only `products` runs. Samplers: 1 Hz `usage_usec` of every
`misarch-kotlin-*` container, placement CPU (median of 10-s bins) every 60 s, bench every 30 s. Summary: per
container mean CPU per phase, CPU per request (gross and net of idle) for gateway, gateway+sidecars, catalog,
catalog+sidecars+db, latency percentiles, errors, regime per minute (placement decides when present, else bench).
Then `down.sh` (stop, volumes kept) unless `--keep-up`; on cancel `docker compose stop -t 5`.

## campaign
Requires `$WORKSPACE/misarch-optimization` (the optimisation repo, formerly palladio-architecture-optimization-domain;
the calibration tooling is its `cases/misarch/timing/calibration`, since its AB52; the timing paths below are
relative to its `cases/misarch/`, the locks stay in its root).
Fixes the calibration venv (`calvenv.py`: if the copied `.venv` does not run or has another Python version, it is moved
to `.venv.vm-<ts>` and recreated with `--system-site-packages`, then `requirements*.txt` and any missing third-party
import of `timing/calibration/scripts` and `timing/harness` are pip-installed), then runs `timing/run-third-pass.sh "$@"` in
its own process group. Its outputs stay in misarch-optimization's `cases/misarch/timing/raw`; files new since the start are copied to
`$RESULTS_DIR/raw` at the end. On cancel: SIGTERM to the group, KILL after 8 s, a stale `MEASURING.lock` (owner gone)
is removed, `timing/timing-up.sh down`, collect.

## Manager command lines
```
exec deploy/server/run.sh check                       # add --campaign before tonight's run
exec deploy/server/run.sh regime --minutes 10
exec deploy/server/run.sh smoke                       # [--tag TAG] [--minutes 20] [--build-missing]
exec deploy/server/run.sh images --tag X
exec deploy/server/run.sh campaign                    # CUT_TAG=... TIMING_TAG=... MECH_TAG=... env as needed
```
