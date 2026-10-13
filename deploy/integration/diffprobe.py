#!/usr/bin/env python3
"""Functional / differential probe for the original MiSArch (compose project `misarch`) and misarch-kotlin
(`misarch-kotlin`). Runs one deterministic scenario against the running stack and writes everything it saw, normalised
(UUIDs -> <uuid>, timestamps -> <ts>), to one JSON file; `diffprobe.py compare A.json B.json` prints the differences.

    diffprobe.py run --prefix misarch-kotlin- --state deploy/integration/state/state.json --out k.json
    diffprobe.py run --prefix misarch- --state <calibration>/results/seed/state.json --out o.json
    diffprobe.py compare o.json k.json

Sections of the result:
  health      GET /health of every app (status, body)
  subscribe   GET /dapr/subscribe of every app (status, sorted subscriptions)
  direct      GraphQL requests straight to every subgraph (app container :8080, Authorized-User header like the
              gateway sends it): unknown / malformed ids on every single-id root field, paging edge cases
              (first: 0, first: -1, skip: -1) on every root connection, missing auth, _entities with unknown keys,
              an unknown field (validation) -> status code, `data` present/null/absent, error messages and paths
  gateway     every loadgen request type (calibration loadgen.py texts) once through the gateway, plus federated
              queries (product page joins, current-user joins) and unknown ids through the gateway
  saga        placeOrder of a fresh order for one buyer of each payment method; order/shipment/payment state polled
              for 45 s (state sequence)
  events      Redis stream deltas over the whole run: published messages per topic, per consumer group delivered /
              pending (failed, awaiting redelivery) / lag
"""
import argparse
import asyncio
import json
import os
import random
import re
import subprocess
import sys
import time
import uuid

import aiohttp

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
CAL = os.environ.get("MISARCH_CALIBRATION", "/home/devbox/workspace/misarch-optimization/calibration")
sys.path.insert(0, os.path.join(CAL, "scripts"))

SERVICES = ["address", "catalog", "discount", "inventory", "invoice", "media", "notification", "order", "payment",
            "return", "review", "shipment", "shoppingcart", "tax", "user", "wishlist"]
RUST = {"invoice", "media", "order", "review", "shoppingcart", "wishlist"}
TS = {"inventory", "payment"}
UUID_RE = re.compile(r"[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")
TS_RE = re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?")
UNKNOWN = "00000000-0000-4000-8000-000000000001"


def norm(x):
    """Normalise ids and timestamps (recursively)."""
    if isinstance(x, str):
        return TS_RE.sub("<ts>", UUID_RE.sub("<uuid>", x))
    if isinstance(x, list):
        return [norm(v) for v in x]
    if isinstance(x, dict):
        return {k: norm(v) for k, v in x.items()}
    return x


def shape(x):
    """Structural signature: keys and value types (lists: shape of the first element)."""
    if isinstance(x, dict):
        return {k: shape(v) for k, v in sorted(x.items())}
    if isinstance(x, list):
        return [shape(x[0])] if x else []
    if x is None:
        return None
    return type(x).__name__


def container_ip(name):
    return subprocess.run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name],
                          capture_output=True, text=True).stdout.strip()


# ------------------------------------------------------------------------------------------------ SDL-driven probes
def sdl_root_fields(svc):
    s = open(os.path.join(REPO, "reference", "sdl", f"{svc}.graphql")).read()
    s = re.sub(r'"""[\s\S]*?"""', "", s)
    s = re.sub(r'"[^"\n]*"', "", s)
    m = re.search(r"type Query[^{]*\{([\s\S]*?)\n\}", s)
    if not m:
        return []
    fields, depth, cur = [], 0, ""
    for ch in m.group(1):
        depth += ch == "("
        depth -= ch == ")"
        if ch == "\n" and depth == 0:
            if cur.strip():
                fields.append(" ".join(cur.split()))
            cur = ""
        else:
            cur += ch
    if cur.strip():
        fields.append(" ".join(cur.split()))
    out = []
    for f in fields:
        name = f.split("(")[0].split(":")[0].strip()
        if name.startswith("_"):
            continue
        args = re.findall(r"(\w+)\s*:\s*([\w!\[\]]+)", f.split(")")[0]) if "(" in f else []
        ret = f.rsplit(":", 1)[1].split("@")[0].strip()
        out.append((name, args, ret))
    return out


def sdl_key_types(svc):
    s = open(os.path.join(REPO, "reference", "sdl", f"{svc}.graphql")).read()
    res = []
    for m in re.finditer(r"type (\w+)(?:\s+implements [\w &]+)?\s*@key\(fields\s*:\s*\"id\"(\s*,\s*resolvable\s*:\s*(\w+))?",
                         s):
        if m.group(3) != "false":
            res.append(m.group(1))
    return sorted(set(res))


def direct_probes(svc):
    probes = []
    for name, args, ret in sdl_root_fields(svc):
        argn = dict(args)
        conn = ret.rstrip("!").endswith("Connection")
        sel = "{ totalCount }" if conn else ("{ id }" if not ret.rstrip("!").startswith(("Int", "Boolean", "[")) else "")
        if list(argn) == ["id"]:
            probes.append((f"{name}:unknown-id", f'{{ {name}(id: "{UNKNOWN}") {sel} }}', "admin"))
            probes.append((f"{name}:bad-id", f'{{ {name}(id: "not-a-uuid") {sel} }}', "admin"))
            probes.append((f"{name}:no-auth", f'{{ {name}(id: "{UNKNOWN}") {sel} }}', None))
        elif conn:
            probes.append((f"{name}:default", f"{{ {name} {{ totalCount hasNextPage }} }}", "admin"))
            if "first" in argn:
                probes.append((f"{name}:first0", f"{{ {name}(first: 0) {{ totalCount hasNextPage nodes {{ __typename }} }} }}", "admin"))
                probes.append((f"{name}:first-1", f"{{ {name}(first: -1) {{ totalCount hasNextPage nodes {{ __typename }} }} }}", "admin"))
            if "skip" in argn:
                probes.append((f"{name}:skip-1", f"{{ {name}(first: 1, skip: -1) {{ totalCount hasNextPage nodes {{ __typename }} }} }}", "admin"))
            probes.append((f"{name}:no-auth", f"{{ {name} {{ totalCount }} }}", None))
            probes.append((f"{name}:buyer", f"{{ {name} {{ totalCount }} }}", "buyer"))
    for t in sdl_key_types(svc):
        probes.append((f"_entities:{t}:unknown",
                       '{ _entities(representations: [{__typename: "%s", id: "%s"}]) { __typename ... on %s { id } } }'
                       % (t, UNKNOWN, t), "admin"))
    probes.append(("validation:unknown-field", "{ thisFieldDoesNotExist }", "admin"))
    probes.append(("syntax-error", "{ ", "admin"))
    return probes


def summarize_gql(status, raw):
    try:
        js = json.loads(raw)
    except Exception:
        return {"status": status, "nonJson": norm(raw[:200].decode(errors="replace"))}
    if not isinstance(js, dict):
        return {"status": status, "json": norm(js)}
    out = {"status": status, "keys": sorted(js.keys())}
    if "data" in js:
        out["data"] = "null" if js["data"] is None else shape(js["data"])
        out["dataValue"] = norm(js["data"])
    if js.get("errors"):
        out["errors"] = [{"message": norm(e.get("message")), "path": norm(e.get("path")),
                          "extKeys": sorted((e.get("extensions") or {}).keys()),
                          "code": (e.get("extensions") or {}).get("code"),
                          "locations": bool(e.get("locations"))} for e in js["errors"]]
    if js.get("extensions"):
        out["extKeys"] = sorted(js["extensions"].keys())
    return out


class Probe:
    def __init__(self, a):
        self.a = a
        self.p = a.prefix
        self.state = json.load(open(a.state))
        self.rng = random.Random(7)
        self.res = {"prefix": a.prefix, "started": time.strftime("%Y-%m-%dT%H:%M:%S")}
        self.ips = {s: container_ip(f"{self.p}{s}-1") for s in SERVICES + ["simulation"]}
        self.gw = f"http://{container_ip(self.p + 'gateway-1')}:8080/graphql"
        self.kc = f"http://{container_ip(self.p + 'keycloak-1')}:80/keycloak/realms/Misarch/protocol/openid-connect/token"
        self.redis = self.p + "dapr-redis-1"

    # ---------------------------------------------------------------- helpers
    async def token(self, user):
        async with self.s.post(self.kc, data={"client_id": "frontend", "grant_type": "password", "username": user,
                                              "password": "123"}) as r:
            return (await r.json(content_type=None))["access_token"]

    async def post(self, url, query, variables=None, headers=None):
        body = {"query": query}
        if variables is not None:
            body["variables"] = variables
        h = {"Content-Type": "application/json", **(headers or {})}
        t0 = time.perf_counter()
        async with self.s.post(url, data=json.dumps(body), headers=h) as r:
            raw = await r.read()
            return r.status, raw, (time.perf_counter() - t0) * 1000

    async def gw_q(self, query, variables=None, token=None):
        status, raw, _ = await self.post(self.gw, query, variables, {"Authorization": "Bearer " + token} if token else {})
        try:
            js = json.loads(raw)
        except Exception:
            js = None
        return status, raw, js

    def redis_snapshot(self):
        R = ["docker", "exec", self.redis, "redis-cli", "-a", "redis", "--no-auth-warning"]

        def rc(*a):
            return subprocess.run(R + list(a), capture_output=True, text=True).stdout
        snap = {}
        for k in rc("--scan", "--pattern", "*").split():
            if rc("TYPE", k).strip() != "stream":
                continue
            d = {"len": int(rc("XLEN", k).strip() or 0), "groups": {}}
            lines = rc("XINFO", "GROUPS", k).splitlines()
            cur = {}
            for i in range(0, len(lines) - 1, 2):
                if lines[i] == "name" and cur:
                    d["groups"][cur["name"]] = cur
                    cur = {}
                cur[lines[i]] = lines[i + 1]
            if cur:
                d["groups"][cur["name"]] = cur
            for g in d["groups"].values():
                for f in ("pending", "entries-read", "lag"):
                    try:
                        g[f] = int(g.get(f))
                    except (TypeError, ValueError):
                        g[f] = None
            snap[k] = d
        return snap

    # ---------------------------------------------------------------- sections
    async def health(self):
        out = {}
        for s in SERVICES + ["simulation"]:
            async with self.s.get(f"http://{self.ips[s]}:8080/health") as r:
                body = await r.read()
                out[s] = {"status": r.status, "body": body.decode(errors="replace")[:200],
                          "contentType": (r.headers.get("Content-Type") or "").split(";")[0]}
        self.res["health"] = out

    async def subscribe(self):
        out = {}
        for s in SERVICES + ["simulation"]:
            async with self.s.get(f"http://{self.ips[s]}:8080/dapr/subscribe") as r:
                body = await r.read()
                try:
                    js = json.loads(body)
                    js = sorted(js, key=lambda x: (x.get("topic", ""), x.get("route", ""))) if isinstance(js, list) else js
                except Exception:
                    js = body.decode(errors="replace")[:200]
                out[s] = {"status": r.status, "body": js}
        self.res["subscribe"] = out

    async def direct(self):
        admin = self.state["admin"]["id"]
        buyer = self.state["buyers"][0]["id"]
        hdr = {"admin": {"Authorized-User": json.dumps({"id": admin, "roles": ["buyer", "admin", "employee"]})},
               "buyer": {"Authorized-User": json.dumps({"id": buyer, "roles": ["buyer"]})}, None: {}}
        out = {}
        for svc in SERVICES:
            path = "/" if svc in RUST else "/graphql"
            url = f"http://{self.ips[svc]}:8080{path}"
            for name, q, who in direct_probes(svc):
                status, raw, _ = await self.post(url, q, None, hdr[who])
                out[f"{svc}/{name}"] = summarize_gql(status, raw)
            # invalid Authorized-User header (present but not parseable)
            status, raw, _ = await self.post(url, "{ __typename }", None, {"Authorized-User": '{"id":"x"}'})
            out[f"{svc}/invalid-auth-header"] = summarize_gql(status, raw)
        self.res["direct"] = out

    async def gateway(self):
        import loadgen as L   # request texts of the calibration load generator (GW/KC IPs come from the env)
        st = self.state
        rng = self.rng
        out = {}
        b = next(x for x in st["buyers"] if x.get("wishlists") and len(x["addresses"]) >= 1)
        btok = await self.token(b["username"])
        atok = await self.token("gatling")
        p = st["products"][3]
        v = p["variants"][0]

        async def rec(name, q, variables=None, tok=btok):
            status, raw, js = await self.gw_q(q, variables, tok)
            out[name] = summarize_gql(status, raw)
            return js

        out["token"] = {"status": 200 if btok else 0}
        await rec("products", L.Q_PRODUCTS % 0)
        await rec("product", L.Q_PRODUCT % p["id"])
        await rec("users", L.Q_USER)
        await rec("address", L.Q_ADDRESS)
        js = await rec("createShoppingcartItem", L.Q_CART % (b["id"], v))
        cart_id = (((js or {}).get("data") or {}).get("createShoppingcartItem") or {}).get("id")
        await rec("shipmentMethods", L.Q_SHIPMENT_METHODS)
        await rec("paymentInformations", L.Q_PAYMENT_INFOS)
        pi = b["paymentInformations"][0]["id"]
        js = await rec("createOrder", L.Q_CREATE_ORDER % (b["id"], cart_id, st["shipmentMethods"][0], b["addresses"][0],
                                                          b["addresses"][0], pi))
        oid = (((js or {}).get("data") or {}).get("createOrder") or {}).get("id")
        await rec("placeOrder", L.Q_PLACE_ORDER % oid)
        await asyncio.sleep(8)
        await rec("orderTracking", L.Q_ORDER_TRACKING % oid)
        wid = b["wishlists"][0]
        await rec("wishlistView", L.Q_WISHLIST_VIEW % wid)
        await rec("addToWishlist", L.Q_WISHLIST_ADD, {"i": {"id": wid, "productVariantIds": [v, p["variants"][-1]]}})
        # a (user, variant) pair without a review (one review per pair); unique per run
        rb = st["buyers"][int(time.time()) % len(st["buyers"])]
        rtok = await self.token(rb["username"])
        reviewed = set(tuple(x) for x in st.get("reviews", []))
        allv = [vv for pp in st["products"] for vv in pp["variants"]]
        rv = next(vv for vv in rng.sample(allv, len(allv)) if (rb["id"], vv) not in reviewed)
        await rec("createReview", L.Q_REVIEW, {"i": {"userId": rb["id"], "productVariantId": rv, "body": "probe review",
                                                     "rating": "FOUR_STARS", "isVisible": True}}, tok=rtok)
        await rec("createReview:duplicate", L.Q_REVIEW, {"i": {"userId": rb["id"], "productVariantId": rv,
                                                               "body": "probe review", "rating": "FOUR_STARS",
                                                               "isVisible": True}}, tok=rtok)
        version = {"name": "probe version", "description": "probe", "retailPrice": 1234, "weight": 1.0,
                   "canBeReturnedForDays": 30, "taxRateId": p["taxRateId"], "mediaIds": [],
                   "categoricalCharacteristicValues": [{"characteristicId": c["brand"], "value": "Acme"} for c in p["charCats"]],
                   "numericalCharacteristicValues": [{"characteristicId": c["size"], "value": 42.0} for c in p["charCats"]]}
        await rec("adminUpdateProduct", L.Q_ADMIN_UPDATE_PRODUCT, {"i": {"id": p["id"], "internalName": f"P{p['idx']:04d}"}},
                  tok=atok)
        await rec("adminNewPrice", L.Q_ADMIN_NEW_PRICE, {"i": dict(version, productVariantId=v)}, tok=atok)
        await rec("adminCreateVariant", L.Q_ADMIN_CREATE_VARIANT,
                  {"i": {"productId": p["id"], "isPubliclyVisible": True, "initialVersion": version}}, tok=atok)
        await rec("adminCreateProduct", L.Q_ADMIN_CREATE_PRODUCT,
                  {"i": {"internalName": "PROBE", "isPubliclyVisible": True, "categoryIds": p["categories"],
                         "defaultVariant": {"isPubliclyVisible": True, "initialVersion": version}}}, tok=atok)
        # buyer without admin rights (only the role buyer) on an admin mutation
        await rec("adminUpdateProduct:anonymous", L.Q_ADMIN_UPDATE_PRODUCT,
                  {"i": {"id": p["id"], "internalName": "x"}}, tok=None)
        # ---- federation: product page joins (catalog, review, inventory, discount, tax), user joins
        await rec("federation:productPage", """query($id: UUID!) { product(id: $id) { id internalName
            categories { totalCount nodes { id name } } averageRating
            defaultVariant { id isPubliclyVisible averageRating inventoryCount
              currentVersion { id name retailPrice weight canBeReturnedForDays version taxRate { id name currentVersion { rate } } }
              reviews(first: 3) { totalCount nodes { id rating body user { id username } } }
              discounts(first: 3) { totalCount } }
            variants { totalCount nodes { id currentVersion { retailPrice } } } } }""", {"id": p["id"]})
        await rec("federation:currentUser", """{ currentUser { id username name { firstName lastName }
            addresses { totalCount nodes { id city } }
            paymentInformations { totalCount nodes { id paymentMethod payments { totalCount } } }
            orders(first: 5) { totalCount nodes { id orderStatus } }
            wishlists { totalCount nodes { id name productVariants { totalCount } } }
            shoppingcart { lastUpdatedAt shoppingcartItems { totalCount } }
            reviews { totalCount } notifications { totalCount } returns { totalCount }
            coupons { totalCount } discountUsages { totalCount } } }""")
        await rec("federation:orderJoins", """query($id: UUID!) { order(id: $id) { id orderStatus user { id username }
            invoiceAddress { id city }
            orderItems { totalCount nodes { id count productVariant { id currentVersion { name } }
              productVariantVersion { id retailPrice } shipmentMethod { id name } } }
            shipments { totalCount nodes { id status shipmentMethod { name } } } } }""", {"id": oid})
        await rec("federation:shipmentMethods", "{ shipmentMethods { totalCount nodes { id name baseFees } } }")
        await rec("federation:taxRates", "{ taxRates { totalCount nodes { id name currentVersion { rate } } } }", tok=atok)
        # ---- unknown ids through the gateway
        for name, q in [("product", "{ product(id: \"%s\") { id } }"), ("order", "{ order(id: \"%s\") { id } }"),
                        ("wishlist", "{ wishlist(id: \"%s\") { id } }"), ("taxRate", "{ taxRate(id: \"%s\") { id } }"),
                        ("address", "{ address(id: \"%s\") { id } }"), ("productItem", "{ productItem(id: \"%s\") { id } }"),
                        ("review", "{ review(id: \"%s\") { id } }"), ("user", "{ user(id: \"%s\") { id } }"),
                        ("shoppingcartItem", "{ shoppingcartItem(id: \"%s\") { id } }"),
                        ("invoice", "{ invoice(id: \"%s\") { id } }"), ("discount", "{ discount(id: \"%s\") { id } }")]:
            await rec(f"unknown:{name}", q % UNKNOWN, tok=atok)
        await rec("unauthenticated:currentUser", L.Q_USER, tok=None)
        self.res["gateway"] = out
        self.res["ids"] = {"order": oid, "cart": cart_id}

    async def saga(self):
        """placeOrder for one buyer per payment method of nodes[0]; poll order / shipment / payment states."""
        import loadgen as L
        st = self.state
        out = {}
        by_method = {}
        for b in st["buyers"]:
            m = b["paymentInformations"][0]["paymentMethod"]
            if m not in by_method and b.get("addresses"):
                by_method[m] = b
        tasks = []

        async def one(m, b, k):
            tok = await self.token(b["username"])
            q_pay = ("{ currentUser { paymentInformations { nodes { id paymentMethod payments { totalCount nodes "
                     "{ id status totalAmount numberOfRetries } } } } } }")
            _, _, js = await self.gw_q(q_pay, None, tok)
            before = {pi["id"]: pi["payments"]["totalCount"]
                      for pi in (((js or {}).get("data") or {}).get("currentUser") or {}).get("paymentInformations", {}).get("nodes", [])
                      if pi.get("payments")}
            _, _, js = await self.gw_q(L.Q_CART % (b["id"], st["products"][5 + k]["variants"][0]), None, tok)
            cid = js["data"]["createShoppingcartItem"]["id"]
            pi = b["paymentInformations"][0]["id"]
            _, raw, js = await self.gw_q(L.Q_CREATE_ORDER % (b["id"], cid, st["shipmentMethods"][1], b["addresses"][0],
                                                            b["addresses"][0], pi), None, tok)
            oid = js["data"]["createOrder"]["id"]
            _, raw, js = await self.gw_q(L.Q_PLACE_ORDER % oid, None, tok)
            seq, t0 = [], time.time()
            q = ("{ order(id: \"%s\") { orderStatus rejectionReason shipments { nodes { status } } } }" % oid)
            while time.time() - t0 < 45:
                _, raw, js = await self.gw_q(q, None, tok)
                o = ((js or {}).get("data") or {}).get("order") or {}
                errs = [norm(e.get("message")) for e in (js or {}).get("errors") or []]
                snap = {"orderStatus": o.get("orderStatus"), "rejectionReason": o.get("rejectionReason"),
                        "shipments": sorted(x["status"] for x in ((o.get("shipments") or {}).get("nodes") or [])),
                        "errors": errs}
                if not seq or seq[-1]["s"] != snap:
                    seq.append({"t": round(time.time() - t0, 1), "s": snap})
                await asyncio.sleep(1.5)
            _, _, js = await self.gw_q(q_pay, None, tok)
            pays = []
            for pinfo in (((js or {}).get("data") or {}).get("currentUser") or {}).get("paymentInformations", {}).get("nodes", []):
                if pinfo["id"] == pi and pinfo.get("payments"):
                    pays = [{"status": x["status"], "retries": x["numberOfRetries"]} for x in pinfo["payments"]["nodes"]]
                    pays = {"newPayments": pinfo["payments"]["totalCount"] - before.get(pi, 0),
                            "statuses": sorted(x["status"] for x in pinfo["payments"]["nodes"])[-3:]}
            out[m] = {"order": oid, "sequence": seq, "final": seq[-1]["s"] if seq else None, "payments": pays}
        # one variant per order: concurrent reservations of the same variant conflict (WriteConflict in both stacks)
        for k, (m, b) in enumerate(sorted(by_method.items())):
            tasks.append(one(m, b, k))
        await asyncio.gather(*tasks)
        self.res["saga"] = out

    async def run(self):
        os.environ.setdefault("GW_IP", self.gw.split("//")[1].split(":")[0])
        os.environ.setdefault("KC_IP", self.kc.split("//")[1].split(":")[0])
        os.environ.setdefault("HOP_IP", self.ips["shipment"])
        os.environ.setdefault("USER_IP", self.ips["user"])
        before = self.redis_snapshot()
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=90)) as s:
            self.s = s
            await self.health()
            await self.subscribe()
            await self.direct()
            await self.gateway()
            await self.saga()
        await asyncio.sleep(10)
        after = self.redis_snapshot()
        ev = {}
        for k in sorted(set(before) | set(after)):
            a, b = before.get(k, {"len": 0, "groups": {}}), after.get(k, {"len": 0, "groups": {}})
            d = {"published": b["len"] - a["len"], "groups": {}}
            for g, gb in b["groups"].items():
                ga = a["groups"].get(g, {})
                d["groups"][g] = {"read": (gb.get("entries-read") or 0) - (ga.get("entries-read") or 0),
                                  "pendingNow": gb.get("pending"), "pendingDelta": (gb.get("pending") or 0) - (ga.get("pending") or 0),
                                  "lag": gb.get("lag")}
            if d["published"] or any(x["read"] or x["pendingDelta"] for x in d["groups"].values()):
                ev[k] = d
        self.res["events"] = ev
        self.res["finished"] = time.strftime("%Y-%m-%dT%H:%M:%S")


def compare(fa, fb, show_values=False):
    A, B = json.load(open(fa)), json.load(open(fb))
    n = 0
    for sec in ("health", "subscribe", "direct", "gateway"):
        a, b = A.get(sec, {}), B.get(sec, {})
        for k in sorted(set(a) | set(b)):
            x, y = a.get(k), b.get(k)
            if not show_values:
                x = {kk: vv for kk, vv in (x or {}).items() if kk != "dataValue"} if isinstance(x, dict) else x
                y = {kk: vv for kk, vv in (y or {}).items() if kk != "dataValue"} if isinstance(y, dict) else y
            if x != y:
                n += 1
                print(f"[{sec}] {k}\n   A: {json.dumps(x)[:600]}\n   B: {json.dumps(y)[:600]}")
    for m in sorted(set(A.get("saga", {})) | set(B.get("saga", {}))):
        x, y = A["saga"].get(m, {}), B["saga"].get(m, {})
        sx = [s["s"] for s in x.get("sequence", [])]
        sy = [s["s"] for s in y.get("sequence", [])]
        if sx != sy or x.get("payments") != y.get("payments"):
            n += 1
            print(f"[saga] {m}\n   A: {json.dumps(sx)} {x.get('payments')}\n   B: {json.dumps(sy)} {y.get('payments')}")
    ea, eb = A.get("events", {}), B.get("events", {})
    for k in sorted(set(ea) | set(eb)):
        x, y = ea.get(k, {}), eb.get(k, {})
        fx = (x.get("published"), {g: (v["read"], v["pendingDelta"]) for g, v in x.get("groups", {}).items()})
        fy = (y.get("published"), {g: (v["read"], v["pendingDelta"]) for g, v in y.get("groups", {}).items()})
        if fx != fy:
            n += 1
            print(f"[events] {k}\n   A: {fx}\n   B: {fy}")
    print(f"{n} differences")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--prefix", required=True)
    r.add_argument("--state", required=True)
    r.add_argument("--out", required=True)
    c = sub.add_parser("compare")
    c.add_argument("a")
    c.add_argument("b")
    c.add_argument("--values", action="store_true")
    a = ap.parse_args()
    if a.cmd == "compare":
        compare(a.a, a.b, a.values)
        return
    p = Probe(a)
    # loadgen resolves the container IPs at import time
    os.environ["GW_IP"] = p.gw.split("//")[1].split(":")[0]
    os.environ["KC_IP"] = p.kc.split("//")[1].split(":")[0]
    os.environ["HOP_IP"] = p.ips["shipment"]
    os.environ["USER_IP"] = p.ips["user"]
    try:
        asyncio.run(p.run())
    finally:
        json.dump(p.res, open(a.out, "w"), indent=1, ensure_ascii=False)
        print("written", a.out)


if __name__ == "__main__":
    main()
