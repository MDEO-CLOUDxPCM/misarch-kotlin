# Mechanisms: local copies and response caches (branch `timing-mechanisms`)

Two mechanisms that the architecture optimiser can place, built into today's cut behind switches. **Both switches
are off by default. With both off, the services behave exactly like `main`** (plus the timing spans of
`timing-instrumentation`, which are off by default too). The mechanisms exist so that their costs (copy read, copy
write, cache hit, cache miss) can be measured on today's topology.

| Mechanism | Where | Switch (env / property) | Default |
|---|---|---|---|
| Local copies of Discount and ShipmentMethod for `createOrder` | order (`org.misarch.order.mechanisms.LocalCopies`, migration `V2__local_copies.sql`) | `MISARCH_LOCAL_COPIES` / `misarch.mechanisms.local-copies` = `true` | off |
| Response cache for federation entity fetches | every service with `libs/common` (`org.misarch.common.mechanisms.EntityCacheFilter`); used in shipment (`Order`) and catalog (`ProductVariant`) | `MISARCH_ENTITY_CACHE_TYPES` / `misarch.mechanisms.entity-cache-types` = comma-separated GraphQL type names | off (empty) |
| Cache TTL | as above | `MISARCH_ENTITY_CACHE_TTL_SECONDS` / `misarch.mechanisms.entity-cache-ttl-seconds` | 60 |
| Cache size bound | as above | `MISARCH_ENTITY_CACHE_MAX_ENTRIES` / `misarch.mechanisms.entity-cache-max-entries` | 10000 |

The property names also bind through Spring's relaxed binding (`MISARCH_MECHANISMS_LOCAL_COPIES`, ...); the short
env names above take effect when the property is not set. The switches are read at start, so changing one means
restarting the container.

## Local copies (order)

With the switch on, `createOrder` reads Discount and ShipmentMethod from copies in order's own database instead of
calling discount (S22, `findApplicableDiscounts`) and shipment (S23, `calculateShipmentFees`). Order's sidecar then
only invokes shoppingcart and inventory in `createOrder`.

**Tables** (migration `V2__local_copies.sql`, always created; empty and never read while the switch is off; named like
the other tables: entity class and camelCase columns, unquoted):

| Table | Copy of | Fed by |
|---|---|---|
| `LocalCopyDiscountEntity` (discount, validFrom/validUntil, minOrderAmount, the three applies-to id arrays) | discount Discount | `discount/discount/created`, `discount/discount/updated` (new subscriptions) |
| `LocalCopyCouponEntity` (coupon id → discountId) | discount Coupon | `discount/coupon/created` (already subscribed; additionally written) |
| `LocalCopyProductEntity` (product id → categoryIds) | catalog Product | `catalog/product/created`, `catalog/product/updated` (new) |
| `LocalCopyProductVariantEntity` (variant id → productId) | catalog ProductVariant | `catalog/product-variant/created` (new) |
| `LocalCopyShipmentMethodEntity` (baseFees, feesPerItem, feesPerKg, archived) | shipment ShipmentMethod | `shipment/shipment-method/created` (already subscribed; additionally written), `shipment/shipment-method/archived` (new) |
| `LocalCopyProductVariantVersionEntity` (version id → weight) | catalog ProductVariantVersion.weight | `catalog/product-variant-version/created` (already subscribed; additionally written) |

- **Subscriptions.** The six new topics are `@Topic` methods of `LocalCopiesEventController`, a bean that only exists
  with the switch on, so `/dapr/subscribe` lists them (each on `/subscription/<topic>`, like order's other topics)
  only then. With it off the list is the same as without the mechanism.
- **Copy writes.** The three topics order subscribes to anyway are first handled as before by order's
  `EventController`, which then calls `LocalCopies.observe*` (a no-op while off). The copy writes are idempotent
  (repository `@Query` upserts), so Dapr's redeliveries are harmless. For them, order's `CouponDTO`,
  `ShipmentMethodDTO` and `ProductVariantVersionDTO` also read `discountId`, the fees and `weight` (nullable, unused
  while off).
- **Copy reads in `createOrder`:**
  - **Discounts.** One statement maps the items' variants to their products and categories (a join of the variant
    and product copies). A second statement
    selects the valid, coupon-free discounts above the order amount that can apply to one of the items: an array
    overlap on variants, products or categories. The exact match per item is done in memory. This is
    `DiscountService.findApplicableDiscounts` without the per-user usage limit.
  - **Shipment fees.** The methods and the version weights are read. The fees are computed as shipment does:
    `baseFees + quantity × feesPerItem + (weight × feesPerKg).toInt()` per method group.
- **Where.** `CheckoutService.createOrder` asks `LocalCopies.applicableDiscounts` / `shipmentFees` with the request
  it would send (`FindApplicableDiscountsInput`, `CalculateShipmentFeesInput`) and calls `CheckoutClient` only on null.
- **Fallback to the remote call.** The remote call is made as before whenever the copy cannot answer exactly:
  - coupons are given (discount checks coupon validity and usage);
  - a variant is not in the copy;
  - a shipment method is unknown or archived;
  - a version weight is unknown;
  - a quantity is ≤ 0.

  The error texts of the original therefore stay unchanged. The load generator's `createOrder` (`couponIds: []`) is
  always answered from the copies once the copies are complete.
- **Deviation.** discount's per-user usage limit (`maxUsagesPerUser`) is not checked locally. discount still enforces
  it when it validates the order (saga), and the seed's limit (1000) is never reached.
- **Filling the copies on an existing stack.** The copies only see events published while the switch is on. On a
  stack whose data was seeded with the switch off, the copies are filled once from the source databases. The timing
  harness does this as a preparation step: `timing/harness/mech_backfill.py` in the domain project, which reads
  discount-db and shipment-db and upserts into order-db (into the tables above; before the unification the migration
  was `V3__local_copies.sql` with snake_case `local_copy_*` tables).

## Response cache (libs/common)

`EntityCacheFilter` is a `WebFilter`, registered by `EntityCacheConfiguration` (imported by
`MisarchCommonAutoConfiguration`) only when the types are set.

- **What is cached.** A POST whose GraphQL query contains `_entities` and whose representations are all of the
  configured types. The representations are every variable whose name ends in `representations`: the gateway sends
  `representations` or, in batched fetches, `_v<n>_representations`.
- **Where it is used.**
  - shipment, `Order`: `orderTracking`'s `Order.shipments`.
  - catalog, `ProductVariant`: `wishlistView`'s variants. This cache serves every ProductVariant entity fetch to
    catalog, not only `wishlistView`'s.
- **Key and value.** The key is the request body (query text + variables) plus the `Authorized-User` header. The
  value is the complete response body. Only status-200 responses without `errors` are stored.
- **Lifetime.**
  - Entries live for the TTL. There is no invalidation: staleness is bounded by the TTL.
  - TTL 0 makes every lookup miss. Responses are still stored, so a TTL-0 miss costs the same as any other miss.
  - When the map reaches the max entries, it is cleared.
- **Placement.** The cache sits in the provider subgraph, behind its sidecar. A hit saves the provider's GraphQL and
  DB work. The gateway → sidecar → provider hop remains, because the gateway image is fixed.
- **Counters.** `GET /mechanisms/entity-cache` on the app port answers the counters since start,
  `{"types", "ttlMs", "hits", "misses", "entries"}`, without tracing. The timing harness reads them at every segment
  boundary. Every 200 lookups the filter also logs `entity cache [types]: hits h, misses m, entries n`.
- **Spans.** With the timing spans on (`MISARCH_TIMING_SPANS=true` plus the OTel agent), every lookup of a cached
  type gets a span `cache <Type>` with `misarch.entity=<Type>` and `misarch.cache=hit|miss`. The span lasts from the
  lookup until the response is written. Requests that are not cached get no span.

## Not included

Everything else of the round-2 optimised topology is left out:
- merged deployables (`libs/deployable`, in-process invocation between members);
- the shared DB server;
- replicas;
- the sync-call and orchestration elements.

The topology is today's (`deploy/compose/docker-compose.yaml`).

## Tests

- `libs/common` `EntityCacheTest`:
  - no filter bean without types; the property and the short env name both switch it on;
  - hit and miss, and the key's parts (representation, user);
  - the `_v0_representations` variable;
  - what is not cached: other types, non-entity queries, responses with `errors`;
  - TTL 0 and expiry;
  - the counter endpoint;
  - `misarch.cache=hit|miss` spans, and no spans while the timing spans are off.
- order `LocalCopiesIntegrationTest` (switch on):
  - `/dapr/subscribe` lists the new topics (the test removes them from the Dapr SDK's static registry afterwards, so
    the switched-off contexts of the same JVM do not see them);
  - `createOrder` invokes only shoppingcart and inventory, with variant and category discounts and the fees computed
    from the copies;
  - fallback to the discount call with a coupon, to the shipment call for an archived method, and to the discount
    call for a variant without a copy;
  - updated discounts and products replace their copies.
- With the switches off, all other suites pass unchanged. Exception: shipment's Flyway checksum test, which already
  fails on `main`, because it does not list V1_6.
