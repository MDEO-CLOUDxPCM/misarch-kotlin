#!/usr/bin/env python3
"""System-level checks of the defect fixes (docs/INTEGRATION.md "System-level verification of the fixes").

Runs against the seeded misarch-kotlin stack (kseed.py state file). Sections (run all, or name some):

  saga        buy saga per payment method (INVOICE, PREPAYMENT, CREDIT_CARD) with a discounted product, count 2:
              discount validation, amounts (item = round(price x count x prod(1 - d)), order = sum + shipment fees,
              payment totalAmount = order amount), shipment DELIVERED, payment status, one invoice + Order.invoice,
              shoppingcart re-add returns the existing item
  ccretry     credit-card retries against the simulation stub (PAYMENT_SUCCESS_RATE set via POST /ecs/variables):
              success after a retry; FAILED after 3 retries + payment-failed + released items
  ret         return flow on the delivered INVOICE order: createReturn, return shipment created and delivered
  review      createReview, averageRating of reviewed variants, review replica, Flyway V2
  users       user/user/created published exactly once per user
  redelivery  re-publishes real events through the Dapr publish API and checks that nothing is duplicated
  invariants  whole-database invariants (reservations, shipments, invoices, payments per order, phantom items)
  pending     pending Redis stream entries per consumer group

    $PY deploy/integration/fixcheck.py --state deploy/integration/state/state_fix.json --out results/fixcheck.json [sections]
"""
import argparse
import json
import math
import os
import random
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

import redis

P = "misarch-kotlin-"
RES = {}
LOCK = threading.Lock()


def ip(name):
    return subprocess.run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                           P + name], capture_output=True, text=True, check=True).stdout.strip()


def http(url, body=None, headers=None, method=None, form=False):
    data = None
    h = dict(headers or {})
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            h.setdefault("Content-Type", "application/x-www-form-urlencoded")
        else:
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=h, method=method or ("POST" if data is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read()


import urllib.parse  # noqa: E402


class Ctx:
    def __init__(self, state):
        self.st = json.load(open(state))
        self.gw = f"http://{ip('gateway-1')}:8080/graphql"
        self.kc = f"http://{ip('keycloak-1')}:80/keycloak/realms/Misarch/protocol/openid-connect/token"
        self.r = redis.Redis(host="localhost", port=6379, password="redis", decode_responses=True)
        self.toks = {}

    def token(self, user):
        if user not in self.toks:
            _, raw = http(self.kc, {"client_id": "frontend", "grant_type": "password", "username": user,
                                    "password": "123"}, form=True)
            self.toks[user] = json.loads(raw)["access_token"]
        return self.toks[user]

    def q(self, query, variables=None, user=None):
        h = {"Authorization": "Bearer " + self.token(user)} if user else {}
        body = {"query": query}
        if variables is not None:
            body["variables"] = variables
        status, raw = http(self.gw, body, h)
        js = json.loads(raw)
        return js

    def direct(self, svc, query, path="/graphql"):
        status, raw = http(f"http://{ip(svc + '-1')}:8080{path}", {"query": query})
        return json.loads(raw)

    def psql(self, db, sql):
        out = subprocess.run(["docker", "exec", "-i", f"{P}{db}-db-1", "psql", "-At", "-F", "|", "-U", "postgres",
                              "-d", db, "-c", sql], capture_output=True, text=True)
        if out.returncode:
            raise RuntimeError(out.stderr)
        return [ln.split("|") for ln in out.stdout.splitlines() if ln]

    def scalar(self, db, sql):
        rows = self.psql(db, sql)
        return rows[0][0] if rows else None

    def stream(self, topic):
        """[(entry id, cloud event data)] of a Dapr Redis stream."""
        out = []
        for eid, fields in self.r.xrange(topic, "-", "+"):
            raw = fields.get("data")
            try:
                ce = json.loads(raw)
                out.append((eid, ce.get("data") if isinstance(ce, dict) and "specversion" in ce else ce))
            except Exception:
                out.append((eid, raw))
        return out

    def publish(self, app, topic, data):
        """Publish through the Dapr sidecar of `app` (shares the app's network namespace, port 3500)."""
        return http(f"http://{ip(app + '-1')}:3500/v1.0/publish/pubsub/{topic}", data)[0]


def rec(section, key, ok, evidence):
    with LOCK:
        RES.setdefault(section, {})[key] = {"ok": bool(ok), "evidence": evidence}
    print(f"[{section}] {key}: {'PASS' if ok else 'FAIL'} {json.dumps(evidence, default=str)[:600]}", flush=True)


def data(js, *path):
    cur = (js or {}).get("data")
    for p in path:
        cur = (cur or {}).get(p) if isinstance(cur, dict) else None
    return cur


# ------------------------------------------------------------------------------------------------------------- saga
Q_ADD = ('mutation($u: UUID!, $v: UUID!, $c: Int!) { createShoppingcartItem(input: {id: $u, shoppingCartItem: '
         '{count: $c, productVariantId: $v}}) { id count } }')
Q_CREATE = ('mutation($i: CreateOrderInput!) { createOrder(input: $i) { id compensatableOrderAmount orderItems { '
            'nodes { id count compensatableAmount productVariantVersion { id retailPrice } shipmentMethod { id } '
            'discounts { nodes { id discount } } } } } }')
Q_PLACE = 'mutation($id: UUID!) { placeOrder(input: {id: $id, paymentAuthorization: {cvc: 123}}) { id orderStatus } }'
Q_TRACK = ('query($id: UUID!) { order(id: $id) { orderStatus rejectionReason compensatableOrderAmount '
           'shipments { nodes { id status } } } }')
Q_INVOICE = 'query($id: UUID!) { order(id: $id) { id invoice { id orderId issuedAt vatNumber content } } }'


def discounted_products(c):
    """Products of the state with at least one applicable discount (product- or category-level)."""
    if "discountedProducts" in c.st:
        return c.st["discountedProducts"]
    disc = {}
    for did in c.st["discounts"]:
        js = c.q('query($id: UUID!) { discount(id: $id) { id discount discountAppliesToProducts { nodes { id } } '
                 'discountAppliesToCategories { nodes { id } } } }', {"id": did}, "gatling")
        d = data(js, "discount")
        disc[did] = d
    out = []
    for p in c.st["products"]:
        ds = [d["discount"] for d in disc.values()
              if p["id"] in [x["id"] for x in d["discountAppliesToProducts"]["nodes"]]
              or set(p["categories"]) & {x["id"] for x in d["discountAppliesToCategories"]["nodes"]}]
        if ds:
            out.append((p, ds))
    c.st["discountedProducts"] = out
    return out


def buyer_with(c, method, used):
    for b in c.st["buyers"]:
        if b["username"] in used or not b.get("addresses"):
            continue
        pis = [pi for pi in b["paymentInformations"] if pi["paymentMethod"] == method]
        if pis:
            used.add(b["username"])
            return b, pis[0]["id"]
    raise RuntimeError("no buyer for " + method)


def expected_amounts(c, order):
    items = order["orderItems"]["nodes"]
    exp_items = {}
    for it in items:
        amt = it["productVariantVersion"]["retailPrice"] * it["count"]
        for d in sorted(it["discounts"]["nodes"], key=lambda x: uuid.UUID(x["id"]).bytes):
            amt *= 1.0 - d["discount"]
        exp_items[it["id"]] = math.floor(amt + 0.5)
    fees_in = ", ".join(f'{{productVariantVersionId: "{it["productVariantVersion"]["id"]}", quantity: {it["count"]}, '
                        f'shipmentMethodId: "{it["shipmentMethod"]["id"]}"}}' for it in items)
    fees = data(c.direct("shipment", f"{{ calculateShipmentFees(input: {{items: [{fees_in}]}}) }}"),
                "calculateShipmentFees")
    return exp_items, fees


def buy(c, b, pi, product, count=2, sm_idx=0, wait_delivered=90, tag=None, section="saga"):
    """Cart -> createOrder -> placeOrder -> poll. Returns a dict with all observations."""
    tag = tag or b["username"]
    user = b["username"]
    v = product["variants"][0]
    r1 = data(c.q(Q_ADD, {"u": b["id"], "v": v, "c": count}, user), "createShoppingcartItem")
    r2 = data(c.q(Q_ADD, {"u": b["id"], "v": v, "c": 1}, user), "createShoppingcartItem")
    out = {"buyer": user, "product": product["id"], "variant": v, "cartItem": r1, "reAdd": r2}
    sm = c.st["shipmentMethods"][sm_idx]
    js = c.q(Q_CREATE, {"i": {"userId": b["id"], "orderItemInputs": [{"shoppingCartItemId": r1["id"], "couponIds": [],
                                                                       "shipmentMethodId": sm}],
                              "vatNumber": "AB1234", "invoiceAddressId": b["addresses"][0],
                              "shipmentAddressId": b["addresses"][0], "paymentInformationId": pi}}, user)
    order = data(js, "createOrder")
    out["createOrderErrors"] = js.get("errors")
    if not order:
        return out
    oid = order["id"]
    out["order"] = oid
    out["created"] = order
    exp_items, fees = expected_amounts(c, order)
    out["expected"] = {"items": exp_items, "fees": fees, "total": sum(exp_items.values()) + (fees or 0)}
    t0 = time.time()
    js = c.q(Q_PLACE, {"id": oid}, user)
    out["placeErrors"] = js.get("errors")
    seq = []
    while time.time() - t0 < wait_delivered:
        o = data(c.q(Q_TRACK, {"id": oid}, user), "order") or {}
        snap = (o.get("orderStatus"), tuple(sorted(s["status"] for s in (o.get("shipments") or {}).get("nodes") or [])))
        if not seq or seq[-1][1] != snap:
            seq.append((round(time.time() - t0, 1), snap))
        if snap[1] and all(s == "DELIVERED" for s in snap[1]):
            break
        time.sleep(1)
    out["sequence"] = seq
    out["final"] = seq[-1][1] if seq else None
    time.sleep(2)
    out["payment"] = c.psql("payment", "SELECT p.id, p.totalamount, p.status, p.numberofretries, p.payedat IS NOT NULL "
                                       f"FROM paymententity p JOIN openorderentity o ON o.paymentid = p.id "
                                       f"WHERE o.orderid = '{oid}'")
    out["invoiceRows"] = int(c.scalar("invoice", f"SELECT count(*) FROM InvoiceEntity WHERE orderId = '{oid}'"))
    out["invoiceGql"] = c.q(Q_INVOICE, {"id": oid}, user)
    out["reservedItems"] = c.psql("inventory", f"SELECT inventorystatus, count(*) FROM productitementity "
                                               f"WHERE orderid = '{oid}' GROUP BY 1")
    out["discountValidated"] = any((d or {}).get("order", {}).get("id") == oid
                                   for _, d in c.stream("discount/order/validation-succeeded"))
    out["discountFailed"] = any((d or {}).get("order", {}).get("id") == oid
                                for _, d in c.stream("discount/order/validation-failed"))
    return out


def sec_saga(c):
    dps = discounted_products(c)
    rng = random.Random(11)
    picks = rng.sample(dps, 3)
    used = set(c.st.get("usedBuyers", []))
    results = {}
    threads = []

    def run(k, m):
        b, pi = buyer_with(c, m, used)
        p, ds = picks[k]
        results[m] = buy(c, b, pi, p, count=2, sm_idx=k % len(c.st["shipmentMethods"]))
        results[m]["applicableDiscounts"] = ds

    for k, m in enumerate(["INVOICE", "PREPAYMENT", "CREDIT_CARD"]):
        t = threading.Thread(target=run, args=(k, m))
        t.start()
        threads.append(t)
        time.sleep(0.3)
    for t in threads:
        t.join()
    c.st["usedBuyers"] = sorted(used)
    c.st["sagaOrders"] = {m: {"order": r.get("order"), "buyer": r["buyer"]} for m, r in results.items()}
    for m, r in results.items():
        cr = r.get("created") or {}
        items = (cr.get("orderItems") or {}).get("nodes") or []
        rec("saga", f"{m}: createOrder (seeded user, own address)", bool(r.get("order")) and not r["createOrderErrors"],
            {"order": r.get("order"), "errors": r["createOrderErrors"]})
        if not r.get("order"):
            continue
        rec("saga", f"{m}: shoppingcart re-add returns the existing item",
            r["cartItem"]["id"] == r["reAdd"]["id"] and r["reAdd"]["count"] == 2,
            {"first": r["cartItem"], "reAdd": r["reAdd"]})
        rec("saga", f"{m}: discount applied and validated (no workaround)",
            bool(items and items[0]["discounts"]["nodes"]) and r["discountValidated"] and not r["discountFailed"],
            {"orderItemDiscounts": [d["discount"] for it in items for d in it["discounts"]["nodes"]],
             "validationSucceeded": r["discountValidated"], "validationFailed": r["discountFailed"]})
        got_items = {it["id"]: it["compensatableAmount"] for it in items}
        exp = r["expected"]
        pay = r["payment"]
        rec("saga", f"{m}: amounts (count, discount, shipment fees) and payment total",
            got_items == exp["items"] and cr["compensatableOrderAmount"] == exp["total"] and len(pay) == 1
            and float(pay[0][1]) == exp["total"],
            {"items": [(it["productVariantVersion"]["retailPrice"], it["count"],
                        [d["discount"] for d in it["discounts"]["nodes"]], it["compensatableAmount"]) for it in items],
             "fees": exp["fees"], "orderAmount": cr["compensatableOrderAmount"], "expected": exp["total"],
             "paymentTotal": pay[0][1] if pay else None})
        rec("saga", f"{m}: saga completes (shipment DELIVERED, payment SUCCEEDED)",
            r["final"] and r["final"][1] and all(s == "DELIVERED" for s in r["final"][1]) and len(pay) == 1
            and pay[0][2] == "SUCCEEDED" and pay[0][4] == "t",
            {"sequence": r["sequence"], "payment": pay, "reserved": r["reservedItems"]})
        inv = data(r["invoiceGql"], "order", "invoice") or {}
        rec("saga", f"{m}: exactly one invoice, Order.invoice resolves",
            r["invoiceRows"] == 1 and inv.get("orderId") == r["order"] and not r["invoiceGql"].get("errors"),
            {"rows": r["invoiceRows"], "invoice": {k: inv.get(k) for k in ("id", "orderId", "vatNumber")},
             "content": (inv.get("content") or "")[:160], "errors": r["invoiceGql"].get("errors")})
    RES.setdefault("raw", {})["saga"] = results


# ------------------------------------------------------------------------------------------------------- cc retry
def set_payment_rate(c, rate):
    return http(f"http://{ip('simulation-1')}:8080/ecs/variables", {"PAYMENT_SUCCESS_RATE": rate})[0]


def cc_payment(c, oid):
    rows = c.psql("payment", "SELECT p.status, p.numberofretries FROM paymententity p JOIN openorderentity o "
                             f"ON o.paymentid = p.id WHERE o.orderid = '{oid}'")
    return rows[0] if rows else None


def sec_ccretry(c):
    dps = discounted_products(c)
    used = set(c.st.get("usedBuyers", []))
    rng = random.Random(23)
    pf_before = len(c.stream("payment/payment/payment-failed"))
    out = {}
    try:
        # (a) success after one retry: rate 0 until the first retry is registered, then 1
        set_payment_rate(c, 0)
        b, pi = buyer_with(c, "CREDIT_CARD", used)
        p, _ = rng.choice(dps)
        res = {}
        th = threading.Thread(target=lambda: res.update(buy(c, b, pi, p, count=1, wait_delivered=120)))
        th.start()
        t0 = time.time()
        while time.time() - t0 < 60:
            oid = res.get("order") or (c.scalar("order", f"SELECT id FROM OrderEntity WHERE userId = '{b['id']}' "
                                                         "AND orderStatus = 'PLACED' ORDER BY placedAt DESC LIMIT 1"))
            st = cc_payment(c, oid) if oid else None
            if st and int(st[1]) >= 1:
                break
            time.sleep(0.5)
        set_payment_rate(c, 1)
        th.join()
        t1 = time.time()
        while time.time() - t1 < 60 and (cc_payment(c, res["order"]) or ["PENDING"])[0] == "PENDING":
            time.sleep(1)
        res["payment"] = c.psql("payment", "SELECT p.id, p.totalamount, p.status, p.numberofretries, "
                                           "p.payedat IS NOT NULL FROM paymententity p JOIN openorderentity o "
                                           f"ON o.paymentid = p.id WHERE o.orderid = '{res['order']}'")
        out["retrySuccess"] = res
        pay = res.get("payment") or []
        rec("ccretry", "credit card: FAILED callback is retried at payment/register, then SUCCEEDED",
            len(pay) == 1 and pay[0][2] == "SUCCEEDED" and float(pay[0][3]) >= 1 and pay[0][4] == "t",
            {"order": res.get("order"), "payment": pay, "sequence": res.get("sequence")})
        # (b) 4 failures: FAILED after 3 retries, payment-failed, items released
        set_payment_rate(c, 0)
        b, pi = buyer_with(c, "CREDIT_CARD", used)
        p, _ = rng.choice(dps)
        res = buy(c, b, pi, p, count=1, wait_delivered=0)
        oid = res.get("order")
        t0 = time.time()
        st = None
        while time.time() - t0 < 90:
            st = cc_payment(c, oid)
            if st and st[0] == "FAILED":
                break
            time.sleep(1)
        time.sleep(3)
        pf = [d for _, d in c.stream("payment/payment/payment-failed")[pf_before:]
              if ((d or {}).get("order") or {}).get("id") == oid]
        items = c.psql("inventory", f"SELECT inventorystatus, count(*) FROM productitementity WHERE orderid = '{oid}' "
                                    "GROUP BY 1")
        ships = int(c.scalar("shipment", f"SELECT count(*) FROM shipmententity WHERE orderid = '{oid}'"))
        out["finalFailure"] = {"order": oid, "payment": st, "paymentFailedEvents": len(pf), "items": items,
                               "shipments": ships, "secs": round(time.time() - t0, 1)}
        # credit card (like invoice) publishes payment-enabled when the payment starts (original design), so the
        # shipment already exists; the final failure releases the items (no item state machine, docs/fixes/inventory.md)
        rec("ccretry", "credit card: 3 retries, then FAILED, exactly one payment-failed, items released",
            st and st[0] == "FAILED" and float(st[1]) == 3 and len(pf) == 1 and not items, out["finalFailure"])
    finally:
        set_payment_rate(c, 0.95)
    c.st["usedBuyers"] = sorted(used)
    RES.setdefault("raw", {})["ccretry"] = out


# ------------------------------------------------------------------------------------------------------- return
def sec_ret(c):
    so = c.st["sagaOrders"]["INVOICE"]
    oid, user = so["order"], so["buyer"]
    js = c.q('query($id: UUID!) { order(id: $id) { orderItems { nodes { id compensatableAmount } } } }', {"id": oid},
             user)
    items = data(js, "order", "orderItems")["nodes"]
    rc_before = len(c.stream("return/return/created"))
    js = c.q('mutation($i: CreateReturnInput!) { createReturn(input: $i) { id refundedAmount } }',
             {"i": {"orderItemIds": [items[0]["id"]], "reason": "fix verification"}}, user)
    r = data(js, "createReturn")
    rec("return", "createReturn after DELIVERED", bool(r) and not js.get("errors"),
        {"return": r, "errors": js.get("errors"), "itemAmount": items[0]["compensatableAmount"]})
    if not r:
        return
    rid = r["id"]
    t0, seq, ship = time.time(), [], None
    while time.time() - t0 < 60:
        rows = c.psql("shipment", f"SELECT id, status FROM shipmententity WHERE returnid = '{rid}'")
        snap = tuple(sorted(x[1] for x in rows))
        if not seq or seq[-1][1] != snap:
            seq.append((round(time.time() - t0, 1), snap))
        if rows and all(x[1] == "DELIVERED" for x in rows):
            break
        time.sleep(1)
    gql = c.q('query($id: UUID!) { return(id: $id) { id reason refundedAmount shipment { id status } '
              'returnedItems { totalCount } } }', {"id": rid}, user)
    events = [d for _, d in c.stream("return/return/created")[rc_before:]]
    cf = [d for _, d in c.stream("shipment/shipment/creation-failed") if (d or {}).get("returnId") == rid]
    rec("return", "return shipment created (and delivered), Return.shipment resolves",
        seq and len(seq[-1][1]) == 1 and seq[-1][1][0] == "DELIVERED" and not cf
        and (data(gql, "return", "shipment") or {}).get("status") == "DELIVERED",
        {"returnShipmentStates": seq, "gateway": data(gql, "return"), "errors": gql.get("errors"),
         "returnCreatedEvents": len(events), "creationFailed": cf})


# ------------------------------------------------------------------------------------------------------- reviews
def sec_review(c):
    allv = [(p, v) for p in c.st["products"] for v in p["variants"]]
    reviewed = set(tuple(x) for x in c.st.get("reviews", []))
    rng = random.Random(5)
    b = rng.choice(c.st["buyers"])
    p, v = next((p, v) for p, v in rng.sample(allv, len(allv)) if (b["id"], v) not in reviewed)
    js = c.q('mutation($i: CreateReviewInput!) { createReview(input: $i) { id rating } }',
             {"i": {"userId": b["id"], "productVariantId": v, "body": "fix verification review", "rating": "FOUR_STARS",
                    "isVisible": True}}, b["username"])
    rec("review", "createReview (variant replica fed by catalog events, no workaround)",
        bool(data(js, "createReview")) and not js.get("errors"), {"result": data(js, "createReview"),
                                                                 "errors": js.get("errors")})
    dup = c.q('mutation($i: CreateReviewInput!) { createReview(input: $i) { id } }',
              {"i": {"userId": b["id"], "productVariantId": v, "body": "dup", "rating": "ONE_STARS",
                     "isVisible": True}}, b["username"])
    rec("review", "duplicate review rejected", bool(dup.get("errors")),
        {"errors": [e.get("message") for e in dup.get("errors") or []]})
    # averageRating for reviewed variants (seeded reviews + the new one)
    rv = list({x[1] for x in reviewed} | {v})
    by_var = {vv: pp for pp in c.st["products"] for vv in pp["variants"]}
    sample = rng.sample(rv, min(20, len(rv))) + [v]
    nulls, vals = [], {}
    for vv in sample:
        js = c.q('query($id: UUID!) { product(id: $id) { variants { nodes { id averageRating } } } }',
                 {"id": by_var[vv]["id"]}, b["username"])
        for n in (data(js, "product", "variants") or {}).get("nodes") or []:
            if n["id"] == vv:
                vals[vv] = n["averageRating"]
                if n["averageRating"] is None:
                    nulls.append(vv)
    exp = c.psql("review", "SELECT product_variant_id, avg(CASE rating WHEN 'OneStars' THEN 1 WHEN 'TwoStars' THEN 2 "
                           "WHEN 'ThreeStars' THEN 3 WHEN 'FourStars' THEN 4 WHEN 'FiveStars' THEN 5 END) FROM reviews "
                           "WHERE is_visible GROUP BY 1")
    expd = {r[0]: float(r[1]) for r in exp}
    mism = {k: (vals[k], expd.get(k)) for k in vals if vals[k] is None or abs(vals[k] - expd.get(k, -9)) > 1e-5}
    rec("review", "averageRating non-null and correct for reviewed variants", not nulls and not mism,
        {"checked": len(vals), "nulls": nulls, "mismatches": mism, "example": {v: vals.get(v)}})
    fl = c.psql("review", "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")
    nvar = int(c.scalar("review", "SELECT count(*) FROM product_variants"))
    ncat = int(c.scalar("catalog", "SELECT count(*) FROM productvariantentity"))
    rec("review", "Flyway V2 applied cleanly; variant replica complete",
        any(r[0] == "2" and r[2] == "t" for r in fl) and all(r[2] == "t" for r in fl) and nvar == ncat,
        {"flyway": fl, "reviewVariants": nvar, "catalogVariants": ncat})


# ------------------------------------------------------------------------------------------------------- users
def sec_users(c):
    created = c.stream("user/user/created")
    ids = [(d or {}).get("id") for _, d in created]
    for i, n in c.st.get("injectedUserCreated", {}).items():   # re-published by the redelivery section
        for _ in range(n):
            ids.remove(i)
    dups = {i: ids.count(i) for i in set(ids) if ids.count(i) > 1}
    n_users = int(c.scalar("user", "SELECT count(*) FROM userentity"))
    n_create = len(c.stream("user/user/create"))
    rec("users", "user/user/created exactly once per user", not dups and len(ids) == n_users,
        {"createdEvents": len(ids), "distinct": len(set(ids)), "usersInDb": n_users, "userCreateEvents": n_create,
         "duplicates": dups})
    # replicas: every consumer has every user
    counts = {}
    for db, sql in [("address", "SELECT count(*) FROM userentity"), ("notification", "SELECT count(*) FROM userentity"),
                    ("discount", "SELECT count(*) FROM userentity"), ("invoice", "SELECT count(*) FROM userentity"),
                    ("order", "SELECT count(*) FROM userentity"), ("review", "SELECT count(*) FROM users"),
                    ("shoppingcart", "SELECT count(*) FROM userentity"), ("wishlist", "SELECT count(*) FROM userentity"),
                    ("payment", "SELECT count(DISTINCT userid) FROM paymentinformationentity")]:
        try:
            counts[db] = int(c.scalar(db, sql))
        except Exception as e:
            counts[db] = str(e)[:120]
    pi_dups = c.psql("payment", "SELECT userid, paymentmethod, count(*) FROM paymentinformationentity "
                                "WHERE paymentmethod IN ('PREPAYMENT','INVOICE') GROUP BY 1, 2 HAVING count(*) > 1")
    rec("users", "user replicas in all 9 consumers, default payment informations once",
        all(v == n_users for v in counts.values()) and not pi_dups, {"replicas": counts, "duplicatePaymentInfos": pi_dups})


# ------------------------------------------------------------------------------------------------------- redelivery
def counts_for(c, oid):
    return {
        "reservedItems": int(c.scalar("inventory", f"SELECT count(*) FROM productitementity WHERE orderid = '{oid}'")),
        "shipments": int(c.scalar("shipment", f"SELECT count(*) FROM shipmententity WHERE orderid = '{oid}'")),
        "payments": int(c.scalar("payment", f"SELECT count(*) FROM openorderentity WHERE orderid = '{oid}'")),
        "invoices": int(c.scalar("invoice", f"SELECT count(*) FROM InvoiceEntity WHERE orderId = '{oid}'")),
        "itemsTotal": int(c.scalar("inventory", "SELECT count(*) FROM productitementity")),
        "phantoms": int(c.scalar("inventory", "SELECT count(*) FROM productitementity WHERE productvariantid IS NULL")),
        "returnOrders": int(c.scalar("return", f"SELECT count(*) FROM orderentity WHERE id = '{oid}'")),
    }


def event_for(c, topic, oid, key=lambda d: ((d or {}).get("order") or {}).get("id")):
    for _, d in reversed(c.stream(topic)):
        if key(d) == oid:
            return d
    return None


def sec_redelivery(c):
    so = c.st["sagaOrders"]["PREPAYMENT"]
    oid = so["order"]
    before = counts_for(c, oid)
    lens_before = {t: c.r.xlen(t) for t in ["payment/payment/payment-enabled", "shipment/shipment/created",
                                             "invoice/invoice/created", "inventory/product-item/reservation-succeeded",
                                             "user/user/created", "discount/order/validation-succeeded"]}
    sent = {}
    # 1. order/order/created (inventory reservation, shoppingcart, return, ...): twice
    ev = event_for(c, "order/order/created", oid, key=lambda d: (d or {}).get("id"))
    sent["order/order/created"] = [c.publish("order", "order/order/created", ev) for _ in range(2)]
    # 2. discount/order/validation-succeeded (payment, invoice)
    ev = event_for(c, "discount/order/validation-succeeded", oid)
    sent["discount/order/validation-succeeded"] = [c.publish("discount", "discount/order/validation-succeeded", ev)
                                                   for _ in range(2)]
    # 3. payment/payment/payment-enabled (shipment, inventory)
    ev = event_for(c, "payment/payment/payment-enabled", oid)
    sent["payment/payment/payment-enabled"] = [c.publish("payment", "payment/payment/payment-enabled", ev)
                                               for _ in range(2)]
    # 4. shipment/shipment/created + status-updated (inventory, return, order)
    ev = event_for(c, "shipment/shipment/created", oid, key=lambda d: (d or {}).get("orderId"))
    sent["shipment/shipment/created"] = [c.publish("shipment", "shipment/shipment/created", ev)]
    ev = event_for(c, "shipment/shipment/status-updated", oid, key=lambda d: (d or {}).get("orderId"))
    sent["shipment/shipment/status-updated"] = [c.publish("shipment", "shipment/shipment/status-updated", ev)]
    # 5. a user's user/user/created (9 replicas) and the Keycloak user/user/create (user)
    b = c.st["buyers"][0]
    ev = event_for(c, "user/user/created", b["id"], key=lambda d: (d or {}).get("id"))
    sent["user/user/created"] = [c.publish("user", "user/user/created", ev)]
    c.st.setdefault("injectedUserCreated", {})[b["id"]] = c.st.get("injectedUserCreated", {}).get(b["id"], 0) + 1
    ev = event_for(c, "user/user/create", b["id"], key=lambda d: (d or {}).get("id"))
    sent["user/user/create"] = [c.publish("user", "user/user/create", ev)] if ev else "no event found"
    # 6. catalog replication events (inventory, review, discount, shoppingcart, wishlist, order, shipment, return)
    p = c.st["products"][0]
    for topic, key in [("catalog/product-variant/created", lambda d: (d or {}).get("id")),
                       ("catalog/product/created", lambda d: (d or {}).get("id"))]:
        ident = p["variants"][0] if "variant" in topic else p["id"]
        ev = event_for(c, topic, ident, key=key)
        sent[topic] = [c.publish("catalog", topic, ev)] if ev else "no event found"
    # 7. release events for an order without items (phantom-item check): the real payload with a fresh order id
    ev = json.loads(json.dumps(event_for(c, "discount/order/validation-succeeded", oid)))
    ev["order"]["id"] = str(uuid.uuid4())
    sent["payment/payment/payment-failed (unknown order)"] = [c.publish("payment", "payment/payment/payment-failed",
                                                                        {"order": ev["order"]})]
    sent["discount/order/validation-failed (unknown order)"] = [
        c.publish("discount", "discount/order/validation-failed", {"order": ev["order"], "failingDiscountIds": []})]
    time.sleep(20)
    after = counts_for(c, oid)
    lens_after = {t: c.r.xlen(t) for t in lens_before}
    pi_after = c.psql("payment", "SELECT paymentmethod, count(*) FROM paymentinformationentity "
                                 f"WHERE userid = '{b['id']}' GROUP BY 1")
    rec("redelivery", "re-published events accepted by the sidecars",
        all(isinstance(v, str) or all(s == 204 for s in v) for v in sent.values()), sent)
    rec("redelivery", "no duplicate reservations, shipments, payments, invoices; no phantom items",
        {k: before[k] for k in before if k != "itemsTotal"} == {k: after[k] for k in after if k != "itemsTotal"}
        and before["itemsTotal"] == after["itemsTotal"] and after["phantoms"] == 0,
        {"before": before, "after": after})
    # the stream lengths include the injected events themselves: only those may be added (inventory re-publishes
    # reservation-succeeded for a redelivered order/created by design, discount then re-validates: documented gap)
    injected = {t: len(v) for t, v in sent.items() if isinstance(v, list)}
    delta = {t: lens_after[t] - lens_before[t] for t in lens_before}
    buyer_id = next(x["id"] for x in c.st["buyers"] if x["username"] == so["buyer"])
    usages = c.psql("discount", f"SELECT discountid, usages FROM discountusageentity WHERE userid = '{buyer_id}'")
    rec("redelivery", "no extra user/user/created, payment-enabled, shipment/created or invoice/created",
        all(delta[t] == injected.get(t, 0) for t in ["payment/payment/payment-enabled", "shipment/shipment/created",
                                                    "invoice/invoice/created", "user/user/created"]),
        {"streamLengthDelta": delta, "injected": injected, "paymentInfosOfUser": pi_after,
         "discountUsagesOfBuyer": usages})
    RES.setdefault("raw", {})["redelivery"] = {"order": oid, "sent": sent}


# ------------------------------------------------------------------------------------------------------- invariants
def sec_invariants(c):
    order_items = c.psql("order", "SELECT o.id, o.orderStatus, sum(i.count), count(DISTINCT i.shipmentMethodId) "
                                  "FROM OrderEntity o JOIN OrderItemEntity i ON i.orderId = o.id GROUP BY 1, 2")
    placed = {r[0]: (int(r[2]), int(r[3])) for r in order_items if r[1] == "PLACED"}
    reserved = {r[0]: int(r[1]) for r in c.psql("inventory", "SELECT orderid, count(*) FROM productitementity "
                                                             "WHERE orderid IS NOT NULL GROUP BY 1")}
    over = {o: (n, placed.get(o)) for o, n in reserved.items() if o in placed and n > placed[o][0]}
    not_placed = [o for o in reserved if o not in placed]
    ships = {r[0]: int(r[1]) for r in c.psql("shipment", "SELECT orderid, count(*) FROM shipmententity "
                                                         "WHERE orderid IS NOT NULL GROUP BY 1")}
    ship_over = {o: (n, placed.get(o)) for o, n in ships.items() if o not in placed or n > placed[o][1]}
    item_links = c.psql("shipment", "SELECT l.orderitemid, count(*) FROM shipmenttoorderitementity l JOIN shipmententity s "
                                    "ON s.id = l.shipmentid WHERE s.orderid IS NOT NULL GROUP BY 1 HAVING count(*) > 1")
    inv = c.psql("invoice", "SELECT orderId, count(*) FROM InvoiceEntity GROUP BY 1 HAVING count(*) > 1")
    n_inv = int(c.scalar("invoice", "SELECT count(*) FROM InvoiceEntity"))
    n_valid = len({((d or {}).get("order") or {}).get("id") for _, d in c.stream("discount/order/validation-succeeded")})
    pays = c.psql("payment", "SELECT orderid, count(*) FROM openorderentity GROUP BY 1 HAVING count(*) > 1")
    phantoms = int(c.scalar("inventory", "SELECT count(*) FROM productitementity WHERE productvariantid IS NULL"))
    rf = len(c.stream("inventory/product-item/reservation-failed"))
    vf = len(c.stream("discount/order/validation-failed"))
    pending_payments = c.psql("payment", "SELECT status, paymentmethod, count(*) FROM paymententity p JOIN "
                                         "paymentinformationentity i ON i.id = p.paymentinformationid GROUP BY 1, 2")
    ship_states = c.psql("shipment", "SELECT status, (orderid IS NULL), count(*) FROM shipmententity GROUP BY 1, 2")
    ev = {"placedOrders": len(placed), "ordersWithReservations": len(reserved), "overReserved": over,
          "reservationsOfUnplacedOrders": not_placed, "ordersWithShipments": len(ships), "extraShipments": ship_over,
          "orderItemsInTwoShipments": item_links, "invoices": n_inv, "validatedOrders": n_valid,
          "duplicateInvoices": inv, "duplicatePayments": pays, "phantomItems": phantoms,
          "reservationFailedEvents": rf, "validationFailedEvents": vf, "payments": pending_payments,
          "shipmentStates": ship_states}
    rec("invariants", "per order: reservations <= items, shipments <= methods, <= 1 invoice/payment, no phantoms",
        not over and not not_placed and not ship_over and not item_links and not inv and not pays and phantoms == 0
        and n_inv == n_valid, ev)


# ------------------------------------------------------------------------------------------------------- pending
def sec_pending(c):
    out = {}
    for k in c.r.scan_iter("*"):
        if c.r.type(k) != "stream":
            continue
        for g in c.r.xinfo_groups(k):
            if g["pending"]:
                out[f"{k}|{g['name']}"] = g["pending"]
    rec("pending", "no pending Redis stream entries in any consumer group", not out, out)


SECTIONS = {"saga": sec_saga, "ccretry": sec_ccretry, "ret": sec_ret, "review": sec_review, "users": sec_users,
            "redelivery": sec_redelivery, "invariants": sec_invariants, "pending": sec_pending}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--state", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--ctx", help="json file to keep cross-section context (saga orders, used buyers)")
    ap.add_argument("sections", nargs="*")
    a = ap.parse_args()
    c = Ctx(a.state)
    ctx_file = a.ctx or a.out + ".ctx.json"
    if os.path.exists(ctx_file):
        c.st.update(json.load(open(ctx_file)))
    try:
        for s in a.sections or list(SECTIONS):
            try:
                SECTIONS[s](c)
            except Exception as e:  # keep going, record the failure
                import traceback
                traceback.print_exc()
                rec(s, "section crashed", False, repr(e))
    finally:
        json.dump({k: c.st[k] for k in ("sagaOrders", "usedBuyers", "discountedProducts", "injectedUserCreated")
                   if k in c.st},
                  open(ctx_file, "w"))
        old = json.load(open(a.out)) if os.path.exists(a.out) else {}
        for k, v in RES.items():
            old.setdefault(k, {}).update(v)
        json.dump(old, open(a.out, "w"), indent=1, default=str)
        fails = [(s, k) for s, d in RES.items() if s != "raw" for k, v in d.items() if not v["ok"]]
        print("FAILED:" if fails else "all passed", fails if fails else "")


if __name__ == "__main__":
    main()
