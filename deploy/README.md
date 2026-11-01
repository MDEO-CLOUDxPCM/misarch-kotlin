# Deploying misarch-kotlin (docker compose)

`compose/docker-compose.yaml` is **generated** by `compose/gen_compose.py` from the calibration compose of the
original MiSArch (the measurement harness's calibration compose, a trimmed `infrastructure-docker` setup). Regenerate
it after changes: `MISARCH_CALIBRATION_COMPOSE=<calibration compose> python3 compose/gen_compose.py`.

## Topology (identical to the calibration setup of the original)

81 containers: for each of the 16 domain services the app (`misarch-kotlin/<name>:dev`), its Dapr sidecar
(`daprio/daprd:1.15.14`, app port 5000), its experiment-config sidecar (ECS, `ghcr.io/misarch/experiment-config-sidecar:main`,
listens on 5000, forwards to the app on 8080) and its database; gateway (+dapr, +ecs, host port 8080), Keycloak
(+dapr, +ecs, +Postgres, host port 8081), simulation (`misarch-kotlin/simulation:dev`, +dapr, +ecs, +RabbitMQ),
experiment-config (+dapr), Dapr placement, Redis (Dapr pub/sub, host port 6379), otel-collector, Zipkin.
CPU limits mirror the k8s deployment (apps 0.5, gateway 2.4, Keycloak 2.0, ECS 2.0; `compose/set_limits.sh`
re-applies them); health checks every 60 s (`start_interval` 2 s), like the calibration setup.

Differences to the original deployment:

| Item | Original | misarch-kotlin |
|---|---|---|
| app images | `ghcr.io/misarch/<name>:main` (Kotlin, Rust, Node.js) | `misarch-kotlin/<name>:dev` (JDK 25, OTel Java agent) |
| databases of inventory, invoice, order, payment, review, shoppingcart, wishlist | MongoDB (`mongo`, `mongo:6.0`) | Postgres 16 `<name>-db` (schema by Flyway at app start) |
| `inventory-mongosetup` | replica-set init for inventory's transactions | removed |
| media blob store | `minio/minio` (dropped in the calibration setup: image no longer pullable) | `media-minio` = **RustFS 1.0.1** (`rustfs/rustfs:1.0.1`, S3-compatible; MinIO's community server is no longer distributed). Host name kept (`MINIO_ENDPOINT=http://media-minio:9000`, frontend `/api/media` proxy), credentials `admin`/`password` (`RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY`), volume `media-rustfs-data`, health check `curl -fsS http://127.0.0.1:9000/health/ready` (RustFS answers S3 requests with 503 until ready; `media` waits for `service_healthy` because it creates its bucket once at startup, like the original), console off. The media port talks plain S3 (AWS SDK v2, path style), so it also runs unchanged against MinIO in the original deployment (both are tested). |
| env of former Rust/TS apps | `MONGODB_URI`, `DATABASE_URI`, `DATABASE_NAME`, Node.js OTel vars | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, Java OTel vars; all other vars unchanged (`SIMULATION_URL`, `PAYMENT_PROVIDER_URL`, `MISARCH_SHIPMENT_PROVIDER_ENDPOINT`, `RABBITMQ_URL`, `PAYMENT_URL`, `SHIPMENT_URL`, `MINIO_ENDPOINT`) |
| app health check | `wget`/`curl` on `/health` | `wget -qO - http://localhost:8080/health` for all apps |
| JVM options | none | none by default; `MISARCH_JAVA_OPTS="-Xmx256m ..." ./up.sh` passes options to all Kotlin apps |

Unchanged: gateway image and supergraph (the gateway composes the static `misarch/schemas`, the services serve
the same SDL), Keycloak, sidecars, Dapr components (`compose/.dapr`, Redis pub/sub `pubsub`,
`experiment-config-pubsub`), Dapr config with tracing on/off, otel-collector config, published host ports.

## Usage

```
deploy/build-images.sh                 # all 17 images via tools/image.sh (Gradle through tools/build.sh)
deploy/compose/up.sh                   # start (tracing off); TRACING=1 for Dapr tracing
deploy/compose/down.sh [--wipe]        # stop (--wipe: also delete containers and volumes)
deploy/compose/set_limits.sh k8s order=1.0
deploy/compose/redeploy.sh order discount  # swap in rebuilt images: app + its Dapr and ECS sidecar (they share the
                                           # app's network namespace; recreating only the app breaks them)
```

Rules:
- **Never run it together with the original** (project `misarch`): same host ports, and the machine (15.6 GB)
  cannot hold both. `up.sh` refuses to start while `misarch-gateway-1` runs.
- Memory estimate: 17 JVMs at ~300-500 MB RSS (OTel agent included, default heap sizing) + Keycloak + 16 Postgres
  (~30-60 MB each) + sidecars ≈ 9-11 GB. Use `MISARCH_JAVA_OPTS` (e.g. `-XX:MaxRAMPercentage=5`) if RAM is short;
  note that this changes the JVM configuration relative to the original Kotlin services.
- Seeding/load: the calibration scripts (`seed.py`, `loadgen.py`) target the gateway (host port 8080 / container IP),
  Keycloak (8081) and the user sidecar (host port 3510) - the same ports exist here; container names have the
  prefix `misarch-kotlin-` instead of `misarch-`.

## Seeding, load and differential checks (`deploy/integration/`)

Whole-system verification against the original (2026-10-13): **docs/INTEGRATION.md** (how to run, results,
remaining differences). Short version:

```
export MISARCH_CALIBRATION=<calibration directory of the measurement harness>
PY=$MISARCH_CALIBRATION/.venv/bin/python
$PY deploy/integration/kseed.py --out deploy/integration/state/state.json        # calibration seeder, ~2 min
deploy/integration/loadgen.sh kotlin plans/functional.json results/k_functional  # all 23 request types, low rate
$PY deploy/integration/diffprobe.py run --prefix misarch-kotlin- --state deploy/integration/state/state.json --out deploy/integration/results/k_probe.json
deploy/integration/perfsmoke.sh kotlin results/k_perf                            # indicative CPU/latency smoke
```

`kseed.py` runs the calibration `seed.py` unchanged except for its three original-specific helpers (review and
discount defect workarounds, Redis ack), which it points at the Kotlin containers and the Postgres schemas; since
the defect fixes they are skipped unless `KSEED_WORKAROUNDS=1`. `fixcheck.py` checks the fixes end to end
(docs/INTEGRATION.md §9).
`loadgen.sh` runs the calibration `loadgen.py` with its own pool/token files. State and results
(`deploy/integration/state/`, `results/`) are not versioned.

Status of the deployment (2026-10-13): all 17 images build, 81 containers healthy in ~2 min, ~11.4 GB RSS (17 JVMs at
380-420 MB, gateway 2.2 GB). Seeding: 2,280 calls, 0 errors. All 23 loadgen request types: 0 errors. Buy saga,
replication and the simulation provider work as in the original (incl. its defects).
