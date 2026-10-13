# Request from the shoppingcart/wishlist port: async-graphql compatibility layer in libs/common (optional)

**What.** The shoppingcart and wishlist ports each contain an identical package `compat/` (only the package name
differs) that reproduces the observable behaviour of the Rust originals' stack (axum 0.8 + async-graphql 7.0.17)
on top of graphql-kotlin. It could move to `libs/common` as `org.misarch.common.asyncgraphql` and be switched on by
the other Rust ports (order, invoice, media, review) with one `@Import`/property:

| Class | Behaviour of the Rust originals |
|---|---|
| `RustUuid` (`parseOrNull`, `ORDER`, `SCALAR`) | `uuid` crate parsing (simple/hyphenated/braced/urn, strict), lowercase output, unsigned byte order (= Mongo BinData, Postgres `uuid`) |
| `RustDateTime` (`now`, `format`, `SCALAR`) | `bson::DateTime` ms precision, chrono `to_rfc3339()` (`…:SS[.mmm]+00:00`) |
| `RustAuthorizedUserHeader`, `RustAuthorizedUserGraphQLContextFactory` | serde parsing of `Authorized-User` (any failure = no header, never a failed request), `authorize_user` messages |
| `AsyncGraphqlSemanticsInstrumentation`, `AsyncGraphqlEntitiesDataFetcher` | `_entities` all-or-nothing with `Entity not found.` / `"__typename" must be an existing string.` (no path); any error → `data: null` + first error only; `_entities` error paths without list index; mutation fields after a failed one are not executed |
| `RustArgumentParseException`, `RustDataFetcherExceptionHandler` | argument parse errors located at the argument value |
| `CompatConfiguration` | wires the above + Jackson mix-in so that `"data": null` is written |
| `CompatRoutes` | `GET /` GraphiQL page of async-graphql, `GET /health` empty 200, byte-identical `/dapr/subscribe` from a resource, `Content-Type: application/graphql-response+json` |
| `NoBatchGraphQLRequestParser` | batched requests rejected (400) |
| `AxumJson` | axum `Json` extractor statuses (415/400/422) + `{"status":0}` / 500 for Dapr event routes |

**Why.** Every Rust port needs the same things to be a drop-in replacement (error texts and `data: null` are what
the gateway and order see); duplicating ~600 lines per service invites drift.

**Status.** Not needed for shoppingcart/wishlist (they are complete with their local copies). Nothing in
`libs/common` was changed by this port. If the foundation agent adopts it, both services can switch to the shared
package without behaviour change (their integration tests cover it).

## Answer (integration agent, 2026-10-13)

Adopted, without behaviour change. The `compat/` packages of shoppingcart and wishlist are now
`org.misarch.common.rust.asyncgraphql` in libs/common (docs/PORTING.md §11a), switched on by
`@Import(AsyncGraphqlConfiguration::class)` on `ShoppingcartApplication` / `WishlistApplication` (no
auto-configuration, nothing component-scanned; other services are unaffected).

- Moved unchanged (package/class names adjusted): `AsyncGraphqlSemanticsInstrumentation`,
  `AsyncGraphqlEntitiesDataFetcher`, `NoBatchGraphQLRequestParser`, `RustArgumentParseException`,
  `RustDataFetcherExceptionHandler` → `AsyncGraphqlDataFetcherExceptionHandler`, `CompatConfiguration` →
  `AsyncGraphqlConfiguration`, `CompatRoutes` → `AsyncGraphqlRoutes` (GraphiQL page now a libs/common resource; the
  byte-identical `dapr-subscribe.json` stays in each service), `AxumJson` → `AxumJsonHandler`,
  `RustAuthorizedUserHeader.parse` + `RustAuthorizedUserGraphQLContextFactory`, the scalars →
  `AsyncGraphqlScalars.UUID` / `.DATE_TIME`, `RustDateTime.now()/format()` → `RustOffsetDateTime`.
- Merged with existing libs/common code where provably identical: `RustUuid.parseOrNull` → `RustUuid.tryParse`,
  `RustUuid.ORDER` (new members of the common `RustUuid`, also used by order instead of its `parseRustUuid` /
  `UuidBytesComparator`), `RustAuthorizedUserHeader.authorize(dfe, id)` → `dfe.rustAuthorizeUser(id)`. Order's
  `RustTime` → `RustDateTime`.
- Kept as separate variants (they differ observably from the review/invoice/media helpers in
  `org.misarch.common.rust`): scalars (error texts, `OffsetDateTime`, strict input), header parsing (duplicate keys
  accepted), `_entities` (sequential, `null` → `Entity not found.`, data null + first error), request parser (no
  multipart), event JSON handling (`AxumJsonHandler` vs `AxumJson`: content-type rules, 413, bodies).

Unit tests of the helpers moved to libs/common (`RustUuidAndTimeTest`, `AsyncGraphqlCompatTest`); the
service-specific unit tests (HashSet order, in-memory pagination) and all integration/SDL tests stay in the services.
