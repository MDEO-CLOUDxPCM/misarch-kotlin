#!/usr/bin/env python3
"""Replays a fixed shoppingcart scenario against BASE_URL and prints normalized responses (one JSON per line).

usage: shoppingcart_scenario.py http://localhost:PORT > out.jsonl
Generated ids and timestamps are replaced by stable placeholders so two implementations can be diffed.
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
PX = "eeeeeeee-0000-4000-8000-00000000000e"

BUYER1 = json.dumps({"id": U1, "roles": ["buyer"]})
BUYER2 = json.dumps({"id": U2, "roles": ["buyer"]})
EMPLOYEE = json.dumps({"id": UX, "roles": ["employee"]})
BAD_ROLE = json.dumps({"id": U1, "roles": ["BUYER"]})

placeholders = {}


def norm(text):
    def uuid_sub(m):
        u = m.group(0).lower()
        if u in (U1, U2, UX, P1, P2, P3, PX):
            return u
        if u not in placeholders:
            placeholders[u] = "<id%d>" % len(placeholders)
        return placeholders[u]
    text = re.sub(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", uuid_sub, text)
    text = re.sub(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(\+00:00|Z)",
                  lambda m: "<ts%s%s>" % ("ms" if m.group(1) else "", m.group(2)), text)
    return text


def http(method, path, body=None, headers=None, raw=False):
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = body.encode() if raw else json.dumps(body).encode()
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def step(name, method, path, body=None, headers=None, raw=False, keep_body=True):
    status, text = http(method, path, body, headers, raw)
    out = {"step": name, "status": status}
    if keep_body:
        try:
            out["body"] = json.loads(norm(text)) if text else ""
        except ValueError:
            out["body"] = norm(text)
    print(json.dumps(out, sort_keys=False))
    return status, text


def gql(name, query, variables=None, header=None):
    body = {"query": query}
    if variables is not None:
        body["variables"] = variables
    headers = {"Authorized-User": header} if header else {}
    return step(name, "POST", "/", body, headers)


def event(name, route, topic, data, content_type="application/cloudevents+json", keep_body=True):
    ce = {"id": "evt", "source": "test", "type": "com.dapr.event.sent", "specversion": "1.0",
          "datacontenttype": "application/json", "pubsubname": "pubsub", "topic": topic, "data": data}
    return step(name, "POST", route, ce, {"Content-Type": content_type}, keep_body=keep_body)


step("subscribe", "GET", "/dapr/subscribe")
step("health", "GET", "/health")
for u in (U1, U2):
    event("user-created " + u, "/on-topic-event", "user/user/created", {"id": u, "username": "x"})
for p in (P1, P2, P3):
    event("pv-created " + p, "/on-topic-event", "catalog/product-variant/created", {"id": p, "productId": p})
event("user-created duplicate", "/on-topic-event", "user/user/created", {"id": U1}, keep_body=False)
event("unknown topic", "/on-topic-event", "foo/bar", {"id": U1}, keep_body=False)
event("no data.id", "/on-topic-event", "user/user/created", {"x": 1}, keep_body=False)
event("bad uuid", "/on-topic-event", "user/user/created", {"id": "nope"}, keep_body=False)
event("text/plain", "/on-topic-event", "user/user/created", {"id": U1}, content_type="text/plain", keep_body=False)
step("malformed json", "POST", "/on-topic-event", "{", {"Content-Type": "application/json"}, raw=True,
     keep_body=False)

ITEM_FIELDS = "id count addedAt productVariant { id }"
CREATE = "mutation($i: CreateShoppingCartItemInput!) { createShoppingcartItem(input: $i) { %s } }" % ITEM_FIELDS
gql("create no header", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": 1, "productVariantId": P1}}})
gql("create bad role", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": 1, "productVariantId": P1}}},
    BAD_ROLE)
gql("create other buyer", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": 1, "productVariantId": P1}}},
    BUYER2)
gql("create unknown user", CREATE, {"i": {"id": UX, "shoppingCartItem": {"count": 1, "productVariantId": PX}}},
    EMPLOYEE)
gql("create unknown pv", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": 1, "productVariantId": PX}}},
    BUYER1)
_, t1 = gql("create 1", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": 1, "productVariantId": P1}}}, BUYER1)
_, t2 = gql("create 1 again (Q1)", CREATE,
            {"i": {"id": U1, "shoppingCartItem": {"count": 2, "productVariantId": P1}}}, BUYER1)
gql("create count 0", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": 0, "productVariantId": P2}}}, BUYER1)
gql("create count -1", CREATE, {"i": {"id": U1, "shoppingCartItem": {"count": -1, "productVariantId": P2}}},
    BUYER1)
gql("create literal braced uuid",
    'mutation { createShoppingcartItem(input: {id: "{%s}", shoppingCartItem: {count: 3, '
    'productVariantId: "urn:uuid:%s"}}) { %s } }' % (U1.upper(), P3, ITEM_FIELDS), None, BUYER1)
gql("create simple uuid",
    'mutation { createShoppingcartItem(input: {id: "%s", shoppingCartItem: {count: 4, '
    'productVariantId: "%s"}}) { %s } }' % (U1.replace("-", ""), P3, ITEM_FIELDS), None, BUYER1)
gql("create invalid uuid 1-2-3-4-5",
    'mutation { createShoppingcartItem(input: {id: "1-2-3-4-5", shoppingCartItem: {count: 4, '
    'productVariantId: "%s"}}) { id } }' % P3, None, BUYER1)

item1 = json.loads(t1)["data"]["createShoppingcartItem"]["id"]

CART = ("query($r: [_Any!]!) { _entities(representations: $r) { __typename ... on User { id shoppingcart { "
        "lastUpdatedAt shoppingcartItems%s { totalCount hasNextPage nodes { %s } } } } } }")
gql("entities user no header", CART % ("", ITEM_FIELDS), {"r": [{"__typename": "User", "id": U1}]})
gql("entities user page desc", CART % ("(first: 2, skip: 1, orderBy: {direction: DESC})", ITEM_FIELDS),
    {"r": [{"__typename": "User", "id": U1}]})
gql("entities user page skip 10", CART % ("(skip: 10)", ITEM_FIELDS), {"r": [{"__typename": "User", "id": U1}]})
gql("entities user first 0", CART % ("(first: 0)", ITEM_FIELDS), {"r": [{"__typename": "User", "id": U1}]})
gql("entities unknown user", CART % ("", ITEM_FIELDS), {"r": [{"__typename": "User", "id": UX}]})
gql("entities mixed", CART % ("", ITEM_FIELDS),
    {"r": [{"__typename": "User", "id": U2}, {"__typename": "User", "id": UX}]})
gql("entities pv", "query($r: [_Any!]!) { _entities(representations: $r) { __typename } }",
    {"r": [{"__typename": "ProductVariant", "id": P1}]})
gql("entities no typename", "query($r: [_Any!]!) { _entities(representations: $r) { __typename } }",
    {"r": [{"id": U1}]})
gql("entities bad id", "query($r: [_Any!]!) { _entities(representations: $r) { __typename } }",
    {"r": [{"__typename": "User", "id": "nope"}]})
gql("entities item", "query($r: [_Any!]!) { _entities(representations: $r) { __typename ... on ShoppingCartItem "
    "{ %s } } }" % ITEM_FIELDS, {"r": [{"__typename": "ShoppingCartItem", "id": item1}]})
gql("order contract",
    "query GetShoppingCartProductVariantIdsAndCounts($representations: [_Any!]!) {\n    _entities(representations: "
    "$representations) {\n        __typename\n        ... on User {\n            shoppingcart {\n                "
    "shoppingcartItems {\n                    nodes {\n                        id,\n                        "
    "productVariant {\n                            id\n                        },\n                        count\n"
    "                    }\n                }\n            }\n        }\n    }\n}",
    {"representations": [{"__typename": "User", "id": U1}]}, BUYER1)

ITEM_Q = "query($id: UUID!) { shoppingcartItem(id: $id) { %s } }" % ITEM_FIELDS
gql("item unknown no header", ITEM_Q, {"id": UX})
gql("item no header", ITEM_Q, {"id": item1})
gql("item other buyer", ITEM_Q, {"id": item1}, BUYER2)
gql("item owner", ITEM_Q, {"id": item1}, BUYER1)
gql("item employee", ITEM_Q, {"id": item1}, EMPLOYEE)

UPD_ITEM = "mutation($i: UpdateShoppingCartItemInput!) { updateShoppingcartItem(input: $i) { %s } }" % ITEM_FIELDS
gql("update item unknown", UPD_ITEM, {"i": {"id": UX, "count": 5}})
gql("update item other", UPD_ITEM, {"i": {"id": item1, "count": 5}}, BUYER2)
gql("update item owner", UPD_ITEM, {"i": {"id": item1, "count": 5}}, BUYER1)

UPD_CART = ("mutation($i: UpdateShoppingCartInput!) { updateShoppingcart(input: $i) { lastUpdatedAt "
            "shoppingcartItems { totalCount hasNextPage nodes { count productVariant { id } } } } }")
gql("update cart null items", UPD_CART, {"i": {"id": U1}}, BUYER1)
gql("update cart null items unknown user", UPD_CART, {"i": {"id": UX}}, EMPLOYEE)
gql("update cart unknown pv only", UPD_CART, {"i": {"id": U1, "shoppingCartItems": [
    {"count": 1, "productVariantId": PX}]}}, BUYER1)
gql("update cart unknown user", UPD_CART, {"i": {"id": UX, "shoppingCartItems": [
    {"count": 1, "productVariantId": P1}]}}, EMPLOYEE)
gql("update cart other buyer", UPD_CART, {"i": {"id": U1, "shoppingCartItems": []}}, BUYER2)
gql("update cart dedup", UPD_CART, {"i": {"id": U2, "shoppingCartItems": [
    {"count": 2, "productVariantId": P1}, {"count": 2, "productVariantId": P1},
    {"count": 3, "productVariantId": P1}, {"count": 1, "productVariantId": P2}]}}, BUYER2)
gql("cart u2 after", CART % ("", "count productVariant { id }"), {"r": [{"__typename": "User", "id": U2}]})
gql("update cart empty", UPD_CART, {"i": {"id": U2, "shoppingCartItems": []}}, BUYER2)

# order event removes item1 (and an unknown id) from U1's cart; second item stays
_, cart_text = http("POST", "/", {"query": CART % ("", "id"), "variables": {"r": [{"__typename": "User", "id": U1}]}})
event("order created", "/on-order-creation-event", "order/order/created",
      {"id": UX, "userId": U1, "orderItems": [{"shoppingCartItemId": item1, "count": 1},
                                              {"shoppingCartItemId": UX, "count": 1}]})
event("order created again", "/on-order-creation-event", "order/order/created",
      {"id": UX, "userId": U1, "orderItems": [{"shoppingCartItemId": item1, "count": 1}]})
event("order created unknown user", "/on-order-creation-event", "order/order/created",
      {"id": UX, "userId": UX, "orderItems": []})
event("order wrong topic", "/on-order-creation-event", "order/order/foo",
      {"id": UX, "userId": UX, "orderItems": []}, keep_body=False)
event("order negative count", "/on-order-creation-event", "order/order/created",
      {"id": UX, "userId": U1, "orderItems": [{"shoppingCartItemId": item1, "count": -1}]}, keep_body=False)
gql("cart after order", CART % ("", ITEM_FIELDS), {"r": [{"__typename": "User", "id": U1}]})

DEL = "mutation($id: UUID!) { deleteShoppingcartItem(id: $id) }"
item2 = json.loads(t2)["data"]["createShoppingcartItem"]["id"]
gql("delete unknown", DEL, {"id": UX}, BUYER1)
gql("delete other", DEL, {"id": item2}, BUYER2)
gql("delete owner", DEL, {"id": item2}, BUYER1)
gql("delete again", DEL, {"id": item2}, BUYER1)
gql("two root fields one failing",
    'query { a: shoppingcartItem(id: "%s") { id } b: shoppingcartItem(id: "%s") { id } }' % (UX, UX), None, BUYER1)
gql("update cart negative count", UPD_CART, {"i": {"id": U1, "shoppingCartItems": [
    {"count": -1, "productVariantId": P1}]}})
gql("update item negative count", UPD_ITEM, {"i": {"id": UX, "count": -1}})
gql("entities first -1", CART % ("(first: -1)", "id"), {"r": [{"__typename": "User", "id": U1}]})
gql("entities skip -1 literal", CART % ("(skip: -1)", "id"), {"r": [{"__typename": "User", "id": U1}]})
gql("create negative literal",
    'mutation { createShoppingcartItem(input: {id: "%s", shoppingCartItem: {count: -1, '
    'productVariantId: "%s"}}) { id } }' % (U1, P3), None, BUYER1)
gql("invalid uuid variable", ITEM_Q, {"id": "nope"})
gql("unknown field", "query { foo }")
step("batch request", "POST", "/", [{"query": "{ __typename }"}], {})
step("get root", "GET", "/", keep_body=False)
