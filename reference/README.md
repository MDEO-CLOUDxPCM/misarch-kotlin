# Reference data of the original MiSArch (fetched 2026-10-13 ~14:10 from the calibration setup, no load)

| Directory | Content | How obtained |
|---|---|---|
| `sdl/<service>.graphql` | `{ _service { sdl } }` of each running original (`ghcr.io/misarch/<service>:main`, June 2025 images) | `POST http://<container-ip>:8080/graphql` (Rust services: `/`), one request per service |
| `schemas/<service>.graphql` | copy of `misarch/schemas` (what the gateway composes its supergraph from, `gateway/supergraph.yaml`) | [MiSArch/schemas](https://github.com/MiSArch/schemas) |
| `dapr-subscribe/<service>.json` | `GET /dapr/subscribe` of each original app (topics, routes, pubsub component); the services' integration tests compare their topics with it | one request per service |
| `events/<topic with _>.json` | the newest CloudEvent of each Dapr pub/sub topic (Redis stream) produced by the seeded/measured original, e.g. `catalog_product-variant_created.json` | `XREVRANGE <topic> + - COUNT 1` on the calibration Redis, 2026-10-13 15:03 (topics without retained messages are missing: `address/user-address/archived`, `catalog/product-variant/updated`, `media/media/created`, `notification/notification/create`, `payment/payment/payment-failed`, `return/return/created`) |

**The services' reference is `sdl/`** (`SdlComparisonTest`, `tools/sdl-diff.sh`); `tools/sdl-diff.sh <svc> --schemas`
compares with `schemas/`.

## `sdl/` vs `schemas/` (semantic comparison with `SdlCanonicalizer`)

The `schemas/` files are not raw `_service` SDLs: the services' CI fetches the full schema (graphql-kotlin `/sdl`
route, async-graphql/NestJS printers) and the `misarch/graphql-schema-transform` action normalizes it (identical
`schema @link(url: ".../federation/v2.5", import: ["@shareable", "@key", "@inaccessible", "FieldSet"])` header for
all 16 files, federation boilerplate rewritten). Raw text therefore differs a lot (link version: graphql-kotlin
v2.5, async-graphql and @apollo/subgraph print their own `@link`/`extend schema`, `federation__FieldSet`, directive
definitions). Semantically (ordering, whitespace and federation boilerplate ignored):

| Service | Difference `sdl/` → `schemas/` |
|---|---|
| address, catalog, invoice, media, notification, order, return, shipment, tax, user, wishlist | none |
| discount | `DiscountsForProductVariant` has `@shareable` only in `schemas/` |
| inventory | `ProductItemConnection` has `@shareable` only in `schemas/` |
| payment | `PaymentConnection`, `PaymentInformationConnection` have `@shareable` only in `schemas/` |
| shoppingcart | `ShoppingCart` has `@shareable` only in `schemas/` |
| review | `schemas/` is older/different: `Product.averageRating: Float!` and `ProductVariant.averageRating: Float!` (live: nullable `Float`), `Query.review: Review!` (live: nullable), `Review @shareable` (live: no `@shareable`) |

The `@shareable` additions come from the schema-transform step (types defined in several subgraphs must be
shareable for composition). Since the gateway composes from `schemas/`, these differences do not affect runtime
behaviour; the services reproduce the live SDL (`sdl/`).

Health answers of the originals (`GET /health`, recorded on the same day): Kotlin originals `{"status":"UP"}`, TypeScript (inventory, payment) `{"status":"OK"}`, Rust
(invoice, media, order, review, shoppingcart, wishlist) empty body - all HTTP 200.

Event samples: Dapr re-serializes the CloudEvent (Go), so JSON key order is alphabetical there and says nothing about
the publisher's order; values, names, `null`s vs. missing keys and number/date formats are the publisher's (e.g.
`catalog/product-variant/created` has `publiclyVisible` - Jackson bean naming of `isPubliclyVisible` -, and the Rust
order's dates are `2026-10-13T12:57:22.897Z`, millisecond precision).
