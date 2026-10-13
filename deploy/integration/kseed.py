#!/usr/bin/env python3
"""Seed the misarch-kotlin stack with the calibration seeder of the original (unchanged data model and API calls).

Runs misarch-optimization/calibration/scripts/seed.py in-process and only swaps its three original-specific helpers:
  * the review defect workaround (seed.py writes {_id, product_id} into review's MongoDB) -> the same rows into the
    review port's Postgres table `product_variants(id, product_id)`,
  * the discount schema workaround (workarounds/discount_usage_columns.sql) -> applied to misarch-kotlin-discount-db-1
    (the port keeps the original's schema and the snake_case upsert defect, docs/specs/discount.md Q1),
  * ack of the known defect deliveries -> redisq against misarch-kotlin-dapr-redis-1.
Endpoints are the published host ports (gateway 8080, Keycloak 8081, user sidecar 3510), identical in both stacks.

Since the defect fixes (`git log faithful..main`) none of the three is needed: review's replica is fed by
catalog/product-variant/created, discount's usage upsert uses the real columns, and nothing is left pending. They are
therefore off by default (no-ops) and only applied with KSEED_WORKAROUNDS=1 (e.g. for the `faithful` tag, whose own
kseed.py applies them unconditionally). Applied to the fixed services they are harmless (verified 2026-10-13,
docs/INTEGRATION.md "System-level verification of the fixes").

    deploy/integration/kseed.py --out deploy/integration/state/state.json   (same flags as seed.py)
"""
import os
import subprocess
import sys

CAL = os.environ.get("MISARCH_CALIBRATION", "/home/devbox/workspace/misarch-optimization/calibration")
sys.path.insert(0, os.path.join(CAL, "scripts"))
import redisq  # noqa: E402
import seed  # noqa: E402

PREFIX = os.environ.get("MISARCH_PREFIX", "misarch-kotlin-")
redisq.R[2] = PREFIX + "dapr-redis-1"


def psql(db, sql):
    return subprocess.run(["docker", "exec", "-i", f"{PREFIX}{db}-db-1", "psql", "-q", "-U", "postgres", "-d", db,
                           "-v", "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True)


def review_variant_workaround(prods):
    rows = ",".join(f"('{v}','{p['id']}')" for p in prods for v in p["variants"])
    out = psql("review", f"INSERT INTO product_variants (id, product_id) VALUES {rows} ON CONFLICT (id) DO NOTHING;")
    return out.returncode if out.returncode else "ok"


def apply_sql_workarounds():
    return psql("discount", open(os.path.join(CAL, "workarounds", "discount_usage_columns.sql")).read()).returncode


def ack_known_defects():
    return str({f"{s}|{g}": redisq.ack(s, g) for s, g in redisq.KNOWN})


if os.environ.get("KSEED_WORKAROUNDS") == "1":
    seed.review_variant_workaround = review_variant_workaround
    seed.apply_sql_workarounds = apply_sql_workarounds
    seed.ack_known_defects = ack_known_defects
else:
    seed.review_variant_workaround = lambda prods: "skipped (fixed, KSEED_WORKAROUNDS=1 to apply)"
    seed.apply_sql_workarounds = lambda: "skipped (fixed, KSEED_WORKAROUNDS=1 to apply)"
    seed.ack_known_defects = lambda: "skipped (fixed, KSEED_WORKAROUNDS=1 to apply)"

if __name__ == "__main__":
    seed.main()
