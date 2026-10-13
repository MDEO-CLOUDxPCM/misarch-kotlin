#!/usr/bin/env python3
"""Replays a fixed wishlist scenario against BASE_URL and prints normalized responses (one JSON per line).

usage: wishlist_scenario.py http://localhost:PORT > out.jsonl
"""
import json
import re
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1].rstrip("/")

U1 = "11111111-1111-4111-8111-111111111111"
U2 = "22222222-2222-4222-8222-222222222222"
UX = "99999999-9999-4999-8999-999999999999"
P1 = "a1a1a1a1-0000-4000-8000-000000000001"
P2 = "0b0b0b0b-0000-4000-8000-000000000002"
P3 = "f3f3f3f3-0000-4000-8000-000000000003"
P4 = "7fffffff-0000-4000-8000-000000000004"
P5 = "80000000-0000-4000-8000-000000000005"
PX = "eeeeeeee-0000-4000-8000-00000000000e"
KNOWN = (U1, U2, UX, P1, P2, P3, P4, P5, PX)

BUYER1 = json.dumps({"id": U1, "roles": ["buyer"]})
BUYER2 = json.dumps({"id": U2, "roles": ["buyer"]})
ADMIN = json.dumps({"id": UX, "roles": ["admin"]})

placeholders = {}


def norm(text):
    def uuid_sub(m):
        u = m.group(0).lower()
        if u in KNOWN:
            return u
        if u not in placeholders:
            placeholders[u] = "<id%d>" % len(placeholders)
        return placeholders[u]
    text = re.sub(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", uuid_sub, text)
    text = re.sub(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(\+00:00|Z)",
                  lambda m: "<ts%s%s>" % ("ms" if m.group(1) else "", m.group(2)), text)
    return text


def http(method, path, body=None, headers=None):
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def step(name, method, path, body=None, headers=None, keep_body=True):
    status, text = http(method, path, body, headers)
    out = {"step": name, "status": status}
    if keep_body:
        try:
            out["body"] = json.loads(norm(text)) if text else ""
        except ValueError:
            out["body"] = norm(text)
    print(json.dumps(out))
    return status, text


def gql(name, query, variables=None, header=None):
    body = {"query": query}
    if variables is not None:
        body["variables"] = variables
    return step(name, "POST", "/", body, {"Authorized-User": header} if header else {})


def event(name, topic, data, keep_body=True):
    ce = {"id": "evt", "source": "test", "type": "com.dapr.event.sent", "specversion": "1.0",
          "datacontenttype": "application/json", "pubsubname": "pubsub", "topic": topic, "data": data}
    return step(name, "POST", "/on-topic-event", ce, {"Content-Type": "application/cloudevents+json"},
                keep_body=keep_body)


step("subscribe", "GET", "/dapr/subscribe")
for u in (U1, U2):
    event("user-created " + u, "user/user/created", {"id": u})
for p in (P1, P2, P3, P4, P5):
    event("pv-created " + p, "catalog/product-variant/created", {"id": p})
event("pv duplicate", "catalog/product-variant/created", {"id": P1}, keep_body=False)
event("unknown topic", "x/y", {"id": P1}, keep_body=False)

WL = "id name createdAt lastUpdatedAt user { id } productVariants%s { totalCount hasNextPage nodes { id } }"
CREATE = "mutation($i: CreateWishlistInput!) { createWishlist(input: $i) { %s } }" % (WL % "")
gql("create no header", CREATE, {"i": {"userId": U1, "productVariantIds": [P1], "name": "a"}})
gql("create other", CREATE, {"i": {"userId": U1, "productVariantIds": [P1], "name": "a"}}, BUYER2)
gql("create unknown pv only", CREATE, {"i": {"userId": U1, "productVariantIds": [PX], "name": "a"}}, BUYER1)
gql("create unknown user", CREATE, {"i": {"userId": UX, "productVariantIds": [P1], "name": "a"}}, ADMIN)
gql("create unknown user empty", CREATE, {"i": {"userId": UX, "productVariantIds": [], "name": "a"}}, ADMIN)
_, t = gql("create w1", CREATE, {"i": {"userId": U1, "productVariantIds": [P1, P2, P3, P4, P5, P1],
                                       "name": "beta"}}, BUYER1)
w1 = json.loads(t)["data"]["createWishlist"]["id"]
names = ["alpha", "Zeta", "\u00e4pfel", "beta", "", "alpha", "omega"]
ids = [w1]
for n in names:
    _, t = gql("create " + n, CREATE, {"i": {"userId": U1, "productVariantIds": [P2], "name": n}}, BUYER1)
    ids.append(json.loads(t)["data"]["createWishlist"]["id"])

PVQ = "query($id: UUID!) { wishlist(id: $id) { %s } }"
gql("wishlist pv desc page", PVQ % (WL % "(first: 2, skip: 1, orderBy: {direction: DESC})"), {"id": w1}, BUYER1)
gql("wishlist pv asc", PVQ % (WL % "(orderBy: {field: ID})"), {"id": w1}, BUYER1)
gql("wishlist unknown no header", PVQ % "id", {"id": UX})
gql("wishlist other", PVQ % "id", {"id": w1}, BUYER2)
gql("wishlist nested user wishlists", PVQ % "id user { id wishlists(first: 1) { totalCount nodes { id } } }",
    {"id": w1}, BUYER1)

LIST = ("query($r: [_Any!]!) { _entities(representations: $r) { ... on User { id wishlists%s "
        "{ totalCount hasNextPage nodes { name } } } } }")
R1 = {"r": [{"__typename": "User", "id": U1}]}
gql("list no header", LIST % "", R1)
gql("list other", LIST % "", R1, BUYER2)
for field in ("ID", "USER_ID", "NAME", "CREATED_AT", "LAST_UPDATED_AT"):
    for d in ("ASC", "DESC"):
        gql("list %s %s" % (field, d), LIST % ("(orderBy: {field: %s, direction: %s})" % (field, d)), R1, BUYER1)
for first, skip in ((None, None), (0, None), (3, None), (3, 0), (3, 2), (3, 4), (2, 5), (7, 1), (3, 7), (3, 8),
                    (3, 20), (0, 7)):
    args = []
    if first is not None:
        args.append("first: %d" % first)
    if skip is not None:
        args.append("skip: %d" % skip)
    args.append("orderBy: {field: NAME}")
    gql("list first=%s skip=%s" % (first, skip), LIST % ("(%s)" % ", ".join(args)), R1, BUYER1)
gql("list u2 empty", LIST % "", {"r": [{"__typename": "User", "id": U2}]}, BUYER2)
gql("entities unknown user", LIST % "", {"r": [{"__typename": "User", "id": UX}]}, ADMIN)
gql("entities wishlist other", "query($r: [_Any!]!) { _entities(representations: $r) { ... on Wishlist { id } } }",
    {"r": [{"__typename": "Wishlist", "id": w1}]}, BUYER2)
gql("entities wishlist owner", "query($r: [_Any!]!) { _entities(representations: $r) { ... on Wishlist { id name } } }",
    {"r": [{"__typename": "Wishlist", "id": w1}]}, BUYER1)

UPD = "mutation($i: UpdateWishlistInput!) { updateWishlist(input: $i) { %s } }" % (WL % "")
gql("update unknown", UPD, {"i": {"id": UX, "name": "n"}})
gql("update other", UPD, {"i": {"id": w1, "name": "n"}}, BUYER2)
gql("update nothing", UPD, {"i": {"id": w1}}, BUYER1)
gql("update name", UPD, {"i": {"id": w1, "name": "renamed"}}, BUYER1)
gql("update unknown pv only", UPD, {"i": {"id": w1, "productVariantIds": [PX], "name": "x"}}, BUYER1)
gql("update pvs", UPD, {"i": {"id": w1, "productVariantIds": [P3, P3]}}, BUYER1)
gql("update pvs empty", UPD, {"i": {"id": w1, "productVariantIds": []}}, ADMIN)

DEL = "mutation($id: UUID!) { deleteWishlist(id: $id) }"
gql("delete unknown", DEL, {"id": UX}, BUYER1)
gql("delete other", DEL, {"id": w1}, BUYER2)
gql("delete owner", DEL, {"id": w1}, BUYER1)
gql("delete again", DEL, {"id": w1}, BUYER1)
gql("first negative", LIST % "(first: -1)", R1, BUYER1)
