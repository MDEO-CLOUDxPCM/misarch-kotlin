# Relational port of the former MongoDB services (2026-10-31)

The services ported from Rust/TypeScript (originals on MongoDB) mimicked the document store on Postgres: an order was
loaded with all its items and their discounts in one `orders ⟕ order_items ⟕ order_item_discounts` statement by the
root resolver, and the child connections paged the in-memory lists. Since 2026-10-31 these services use the database
integration of the Kotlin originals (address, catalog, discount, notification, return, shipment, tax, user):
a resolver reads its own table, a to-one reference is resolved by its field (DataLoader where batched), a child
collection is a connection over the child/link table read by the field that exposes it - `nodes`
(`SELECT t.* ... ORDER BY ... LIMIT first OFFSET skip`), `totalCount` (`SELECT COUNT(t.id)`) and `hasNextPage`
(`SELECT 1 ... LIMIT 1 OFFSET first + skip`), each statement only when its field is selected - and nothing is loaded
that the query did not ask for. The rule is in [PORTING.md §6](PORTING.md) ("Former MongoDB services").
GraphQL schemas, results, ordering, paging semantics, messages, authorization, events and transactions are unchanged
(exceptions: §3).

## 1. How the Kotlin originals access the database (the target pattern)

| What | Kotlin originals | Example |
|---|---|---|
| root / entity resolver | own row by id: `repository.findById` or an `IdDataLoader` (batched `SELECT ... WHERE id IN (...)` per request) | catalog `Query.product` → `ProductDataLoader` |
| to-one reference field | DataLoader by id in the field resolver, only when selected | catalog `ProductVariant.product`, `Product.defaultVariant` |
| to-many child / link set | `BaseConnection` subclass built by the field resolver with a predicate on the parent key (`Condition.eq("t.productId", id)`) or a join on the link table (`INNER JOIN ProductToCategoryEntity j ON ...`); its `nodes`/`totalCount`/`hasNextPage` are three separate statements, each run only when selected; one connection per parent (no batching across parents) | catalog `Product.variants`, `Product.categories`, shipment `Shipment.orderItems` |
| writes of an aggregate | one INSERT per row (`save`/`saveAll`) inside the mutation's transaction | catalog `createProduct` (product, variant, version, category links) |

`libs/common`: `ConnectionQuery` is `BaseConnection`'s SQL without its GraphQL type (`BaseConnection` now delegates to
it; the generated SQL of the Kotlin services is unchanged), for connection types whose field descriptions differ.
`SqlStatementLog` (test fixtures) records the statements sent over R2DBC.

## 2. Survey: document-pattern persistence in the Rust/TS ports

Services: order, shoppingcart, wishlist, review, invoice, media (Rust); inventory, payment, simulation (TypeScript).
media (S3, no database) and simulation (RabbitMQ, no database) have no persistence to change.

| # | Service / place | Document pattern | Kotlin-original pattern | Done |
|---|---|---|---|---|
| O1 | order `OrderStore.selectOrders` - used by `Query.order`, `Query.orderItem` (`findOrderOfItem`: the whole order of the item), `_entities(Order)`, `_entities(OrderItem)`, `User.orders` pages, the `createOrder` re-read, the three loads of `placeOrder`, the `shipment/shipment/creation-failed` handler | eager aggregate load: `orders ⟕ order_items ⟕ order_item_discounts` in one statement whatever the query selects | `orders` row by its resolver; items and discounts only by their connection fields | yes |
| O2 | order `Order.orderItems`, `OrderItem.discounts` (`paginateInMemory`) | in-memory sort/skip/take/count of the loaded lists | connection over `order_items WHERE order_id` / `order_item_discounts WHERE order_item_id` | yes |
| O3 | order `User.orders` | count always, then a page of `first + 1` orders with their items and discounts | lazy connection over `orders WHERE user_id` | yes |
| O4 | order `OrderStore.insertOrder` | the whole aggregate in one data-modifying-CTE statement (one `insert_one`) | one transaction, one INSERT per row | yes |
| O5 | order `users.user_address_ids uuid[]` | array read as a whole to check two ids; `$push`/`$pull` | table `user_addresses (user_id, id)` | yes (V2) |
| O6 | order `order_compensations.order_item_ids uuid[]` | array unnested to find compensated items | link table `order_compensation_items` (like shipment's `ShipmentToOrderItemEntity`) | yes (V2) |
| O7 | order `order/order/created` payload | built from the eagerly loaded aggregate | items and discount ids read explicitly for the event | yes |
| S1 | shoppingcart `findUserWithCart` (`User` entity resolver, `updateShoppingcart` result) | `UserEntity ⟕ ShoppingCartItemEntity` in one statement | `UserEntity` row (the cart's `lastUpdatedAt` is a column of it) | yes |
| S2 | shoppingcart `ShoppingCart.shoppingcartItems` | in-memory paging of the joined items | connection over `ShoppingCartItemEntity WHERE userId` | yes |
| W1 | wishlist `WishlistEntity.productVariantIds UUID[]`, `Wishlist.productVariants` | array read with every wishlist, paged in memory, written as a whole | link table `WishlistToProductVariantEntity`, connection, DELETE + INSERT per row in a transaction | yes (V2) |
| W2 | wishlist `User.wishlists` | count always + page of `first + 1` | lazy connection | yes |
| R1 | review `Query.reviews`, `User/Product/ProductVariant.reviews` | count always + page of `first + 1` | lazy connection | yes |
| P1 | payment `PaymentService.populate` (Mongoose `populate`) | every payment loads its payment information (one SELECT per payment), selected or not | `Payment.paymentInformation` resolved by a DataLoader (one batched SELECT per request, only when selected) | yes |
| P2 | payment/inventory connections (NestJS `buildConnection`) | already selection-driven (`nodes` only if selected, count only for `totalCount`/`hasNextPage`); `hasNextPage` from the count | (probe) | **kept**: the original's connection, not a document artifact; the count-based `hasNextPage` is tied to the explicit-`first: null` quirk (`skip + (first ?: 0) < total`), a probe would change it |
| I1 | invoice `invoices.user_address`/`vendor_address` JSONB | embedded address snapshots read as a whole (serde validation: an incomplete snapshot = "not found") | flattened columns | **kept**: single embedded objects read as a whole are allowed by PORTING §6; no child collection, one row per root read |
| I2 | invoice `users.addresses`/`user_addresses` JSONB | legacy arrays (always `[]`/NULL since fix D3; addresses live in `user_address`) | - | **kept** (unused columns; not read for any answer) |
| N1 | inventory `ProductItemEntity` | flat collection, already one row per item; connections selection-driven | - | nothing to change |

## 3. Behaviour points

All observable behaviour is unchanged, except (none of them reachable in normal operation):

- **Snapshot consistency**: the statements of one query are separate (as in the Kotlin originals); a write between them
  can make `totalCount` and `nodes` of one connection disagree (the document read was one snapshot).
- **wishlist `User.wishlists` database errors**: `Retrieving wishlists failed in MongoDB.` is still the message, but its
  `path` is the failing subfield (`…wishlists.nodes`/`totalCount`/`hasNextPage`) instead of `…wishlists`.
- **review unreadable rows**: the `RustPanic` (HTTP 500) for an undeserializable stored rating (`FourStarst`) now
  fires only when `nodes` is selected (before also for `totalCount`/`hasNextPage`-only queries). Such rows cannot
  exist: migration V2 of review repaired them and the value is no longer written.

Kept by locking the parent row first (review of the port): a replacement of a child set (DELETE, INSERTs) is one
document write in the original, so the last writer wins. `WishlistRepository.updateProductVariants` starts with the
UPDATE of the wishlist row (a wishlist deleted meanwhile: nothing changes), `deleteWishlist` with `SELECT … FOR
UPDATE`, shoppingcart's `replaceItems` with the UPDATE of the user row. Without it, concurrent updates of one wishlist
with overlapping variants failed on the link table's key, disjoint ones left the union, and an update racing a delete
hit the foreign key (WishlistConcurrencyTest fails on the unlocked version).

## 4. Changes per service

| Service | Code | Schema |
|---|---|---|
| libs/common | `ConnectionQuery` (new), `BaseConnection` delegates to it (same SQL); test fixture `SqlStatementLog` | - |
| order | `OrderStore` (row reads, `ordersOfUser`/`orderItemsOfOrder`/`discountsOfOrderItem` connections, transactional per-row inserts), `Order.orderItems`, `OrderItem.discounts`, `User.orders`, connections, `Query.orderItem`, entity resolvers, `OrderService`, `OrderEventPublisher`, `EventHandlers`; `OrderItemWithDiscounts` | `V2__relational_children.sql`: `user_addresses`, `order_compensation_items` (data moved, array columns dropped) |
| shoppingcart | `ShoppingcartRepository.findUser`/`itemsOfUser`, `ShoppingCart`, `ShoppingCartItemConnection`, `ShoppingcartService` | - |
| wishlist | `WishlistRepository` (link rows, transactions, connections), `Wishlist`, `Connections`, `WishlistService` | `V2__wishlist_product_variants.sql`: `WishlistToProductVariantEntity` (data moved, array column dropped) |
| review | `ReviewStore.reviews`, `ReviewService.connection`, `ReviewConnection` | - |
| payment | `PaymentInformationDataLoader` (new), `Payment` (row + DataLoader field), `PaymentService.find/findRowById/delete`, `PaymentStore.findInformations` | - |

## 5. Access changes (for the domain-model extraction and the timing campaign)

Statements per execution, old → new (`BEGIN`/`COMMIT` counted separately; "page" = `SELECT t.* ... LIMIT/OFFSET`,
"probe" = `SELECT 1 ... LIMIT 1 OFFSET first+skip`, only when `first` is given).

### order

| Operation | Old | New |
|---|---|---|
| `Query.order` | 1: orders ⟕ items ⟕ discounts | `orders` row; + per selected field of `orderItems`: page / COUNT / probe on `order_items`; + per item and selected field of `discounts`: page / COUNT / probe on `order_item_discounts` |
| loadgen `orderTracking` (`order { … orderItems { nodes { … } } }`) | 1 | 2: `orders` row, `order_items` page (discounts no longer read) |
| `Query.orderItem` | 1: the whole order of the item (subquery + joins) | 2: `order_items` row, `orders` row (authorization) (+ `discounts` statements if selected) |
| `_entities(Order)` | 1 join per representation | `orders` row per representation (+ selected children) |
| `_entities(OrderItem)` | 1 join per representation | `order_items` row per representation |
| `_entities(User){orders}` | `users` exists, COUNT, page (`first+1` orders ⟕ items ⟕ discounts; skipped if count = 0) | `users` exists + only the selected: page `LIMIT first' OFFSET skip` (orders only), COUNT, probe; + per order its `orderItems` statements if selected |
| `createOrder` | `users` row (with address array), `shipment_methods`, `coupons`, [S20-S23], `product_variants`, `tax_rates`, 1 INSERT (CTE), re-read (1 join) = 7 | `users` exists, `shipment_methods`, `coupons`, `user_addresses` (both addresses), [S20-S23], `product_variants`, `tax_rates`, BEGIN, INSERT `orders`, one INSERT per `order_items` row, one INSERT per `order_item_discounts` row, COMMIT, re-read `orders` row = 10 + n + m (8 + n + m without BEGIN/COMMIT; + children if the answer selects them) |
| S20 (shoppingcart, from `createOrder`) | 1 join | 2 (see shoppingcart) |
| `placeOrder` | 3 joins (authorization, status check, re-read) + UPDATE (+1 join if the compare-and-set fails) | 3 `orders` rows + UPDATE (+1 `orders` row if it fails) + `order_items` of the order (`ORDER BY position`) + `order_item_discounts = ANY` (skipped without items) for the event |
| `on:shipment/shipment/creation-failed` | join, compensated ids (unnest), INSERT = 3 | `orders` row, `order_items` of the order, compensated ids (join `order_compensation_items` ⋈ `order_compensations`), BEGIN, INSERT compensation, one INSERT per item, COMMIT |
| `on:address/user-address/created` | 1 upsert (`array_append`) | BEGIN, INSERT `users` ON CONFLICT DO NOTHING, INSERT `user_addresses` ON CONFLICT DO NOTHING, COMMIT |
| `on:address/user-address/archived` | 1 UPDATE (`array_remove`) | 1 DELETE on `user_addresses` |
| other events (`user/user/created`, coupons, shipment methods, product variants, tax rates) | unchanged | unchanged |

### shoppingcart

| Operation | Old | New |
|---|---|---|
| `_entities(User){shoppingcart{shoppingcartItems{nodes}}}` (order's S20) | 1: `UserEntity` ⟕ items | 2: `UserEntity` row, `ShoppingCartItemEntity` page (+ COUNT if `totalCount`, + probe if `hasNextPage` with `first`) |
| `_entities(User)` without `shoppingcartItems` | 1 join | 1: `UserEntity` row |
| `updateShoppingcart` | writes + re-read join | same writes + `UserEntity` re-read + the selected item statements |
| other mutations, events | unchanged | unchanged |

### wishlist

| Operation | Old | New |
|---|---|---|
| `wishlist(id)` / `_entities(Wishlist)` | 1 row (with the id array) | 1 row; `productVariants` adds page / COUNT / probe on `WishlistToProductVariantEntity` when selected |
| loadgen `wishlistView` (`productVariants(first: 10) { totalCount nodes }`) | 1 | 3: row, link page, COUNT |
| `User.wishlists` | `UserEntity` exists, COUNT, page (`first+1`; skipped if count = 0) | `UserEntity` exists + only the selected: page, COUNT, probe; `productVariants` per wishlist when selected |
| `createWishlist` | checks, 1 INSERT, re-read | checks, BEGIN, INSERT wishlist, one INSERT per variant, COMMIT, re-read |
| `updateWishlist` with variants (loadgen `addToWishlist`) | find, validation, 1 UPDATE, re-read | find, validation, BEGIN, DELETE links, one INSERT per variant, UPDATE `lastUpdatedAt`, COMMIT, re-read |
| `deleteWishlist` | 1 DELETE | BEGIN, DELETE links, DELETE wishlist, COMMIT |

### review

| Operation | Old | New |
|---|---|---|
| `Query.reviews`, `User/Product/ProductVariant.reviews` | COUNT, then page `LIMIT first'+1` if count > 0 | one statement per selected field: page `LIMIT first' OFFSET skip`, COUNT, probe |
| `averageRating`, mutations, events | unchanged | unchanged |

### payment

| Operation | Old | New |
|---|---|---|
| `Query.payments{nodes}`, `PaymentInformation.payments{nodes}` | page + one information SELECT per payment (selected or not) | page; + one batched information SELECT per request only if `paymentInformation` is selected |
| `_entities(Payment)` | payment + information per representation | payment per representation; + one batched information SELECT if selected |
| `deletePayment` | payment, information, DELETE … RETURNING | payment, DELETE … RETURNING; + information SELECT if selected |
| invoice/prepayment overdue cron | page + information per payment | page |
| `/payment/update-payment-status`, events, `User.paymentInformations` | unchanged | unchanged |

### Not changed

invoice, inventory, media, simulation, and the eight Kotlin originals (their SQL is identical; `BaseConnection` only
delegates to `ConnectionQuery`).
