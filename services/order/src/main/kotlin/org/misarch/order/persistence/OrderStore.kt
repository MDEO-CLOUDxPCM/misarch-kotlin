package org.misarch.order.persistence

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.graphql.model.RejectionReason
import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.persistence.model.OrderCompensationRecord
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.persistence.model.ProductVariantRecord
import org.misarch.order.persistence.model.ProductVariantVersionRecord
import org.misarch.order.persistence.model.TaxRateRecord
import org.misarch.order.persistence.model.TaxRateVersionRecord
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.common.persistence.Condition
import org.misarch.order.persistence.model.OrderItemWithDiscounts
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

/**
 * All database access of the order service (SQL over R2DBC). Tables are read relationally, like the Kotlin services
 * read theirs (docs/PORTING.md §6): a resolver reads the rows of its own table, a child collection (`Order.orderItems`,
 * `OrderItem.discounts`, `User.orders`) is a [ConnectionQuery] whose `nodes`/`totalCount`/`hasNextPage` statements run
 * only when the field is selected. An order is written in one transaction, one INSERT per row (like `saveAll`).
 */
@Component
class OrderStore(
    private val client: DatabaseClient,
    transactionManager: ReactiveTransactionManager,
) {

    private val transaction = TransactionalOperator.create(transactionManager)

    // ---------------------------------------------------------------- orders

    /** `orders.find_one({_id})`: the order row. */
    suspend fun findOrder(id: UUID): OrderRecord? =
        client.sql("SELECT * FROM orders WHERE id = :id").bind("id", id).map { r -> order(r) }.awaitOneOrNull()

    /**
     * The order item [itemId] (FIXED, docs/fixes/order.md: the original looked the item up in a never-written
     * collection, so every lookup failed).
     */
    suspend fun findOrderItem(itemId: UUID): OrderItemRecord? =
        client.sql("SELECT * FROM order_items WHERE id = :id").bind("id", itemId).map { r -> orderItem(r) }.awaitOneOrNull()

    /** The items of an order in their order in the original's `internal_order_items` (event payload, compensation). */
    suspend fun findOrderItemsOfOrder(orderId: UUID): List<OrderItemRecord> =
        client.sql("SELECT * FROM order_items WHERE order_id = :orderId ORDER BY position").bind("orderId", orderId)
            .map { r -> orderItem(r) }.flow().toList()

    /** The discounts of the items [itemIds] (by item; ascending discount id = Rust `BTreeSet` order). */
    suspend fun findDiscountsOfOrderItems(itemIds: Collection<UUID>): Map<UUID, List<DiscountRecord>> {
        if (itemIds.isEmpty()) return emptyMap()
        return client.sql("SELECT * FROM order_item_discounts WHERE order_item_id = ANY(:ids) ORDER BY order_item_id, discount_id")
            .bind("ids", itemIds.toTypedArray())
            .map { r -> r.uuid("order_item_id") to discount(r) }.flow().toList()
            .groupBy({ it.first }, { it.second })
    }

    /**
     * `User.orders`: `find({"user._id": userId}).sort(...).skip(skip).limit(first)` and `count_documents` as the three
     * statements of a connection.
     *
     * @param orderBy SQL ORDER BY items (trusted constant from [org.misarch.order.graphql.model.OrderOrderField])
     */
    fun ordersOfUser(userId: UUID, orderBy: String, first: Int, skip: Int): ConnectionQuery<OrderRecord> =
        ConnectionQuery(client, "orders", Condition.eq("t.user_id", userId), listOf(orderBy), first, skip) { r, _ -> order(r) }

    /** `Order.orderItems`: the items of the order by id (byte order = Rust `Uuid` order), [first] null = all. */
    fun orderItemsOfOrder(orderId: UUID, first: Int?, skip: Int, ascending: Boolean): ConnectionQuery<OrderItemRecord> =
        ConnectionQuery(
            client, "order_items", Condition.eq("t.order_id", orderId), listOf(if (ascending) "id ASC" else "id DESC"), first, skip,
        ) { r, _ -> orderItem(r) }

    /** `OrderItem.discounts`: the discounts of the item by discount id, [first] null = all. */
    fun discountsOfOrderItem(itemId: UUID, first: Int?, skip: Int, ascending: Boolean): ConnectionQuery<DiscountRecord> =
        ConnectionQuery(
            client, "order_item_discounts", Condition.eq("t.order_item_id", itemId),
            listOf(if (ascending) "discount_id ASC" else "discount_id DESC"), first, skip, primaryKey = "discount_id",
        ) { r, _ -> discount(r) }

    /**
     * `insert_one(order)`: the order, then its items and their discounts, one INSERT per row in one transaction
     * (atomic like the insert of one document).
     */
    suspend fun insertOrder(order: OrderRecord, items: List<OrderItemWithDiscounts>) {
        transaction.executeAndAwait {
            client.sql(
                "INSERT INTO orders (id, user_id, created_at, order_status, placed_at, rejection_reason, shipment_address_id, " +
                    "invoice_address_id, compensatable_order_amount, payment_information_id, vat_number) VALUES (:id, :userId, " +
                    ":createdAt, :status, :placedAt, :reason, :shipmentAddressId, :invoiceAddressId, :amount, :paymentInformationId, :vatNumber)"
            ).bind("id", order.id).bind("userId", order.userId).bind("createdAt", order.createdAt)
                .bind("status", order.orderStatus.name).bindOrNull("placedAt", order.placedAt, Instant::class.java)
                .bindOrNull("reason", order.rejectionReason?.name, String::class.java)
                .bind("shipmentAddressId", order.shipmentAddressId).bind("invoiceAddressId", order.invoiceAddressId)
                .bind("amount", order.compensatableOrderAmount).bind("paymentInformationId", order.paymentInformationId)
                .bindOrNull("vatNumber", order.vatNumber, String::class.java)
                .fetch().awaitRowsUpdated()
            items.forEachIndexed { position, (item, _) ->
                client.sql(
                    "INSERT INTO order_items (id, order_id, position, created_at, product_variant_id, " +
                        "product_variant_is_publicly_visible, product_variant_version_id, product_variant_version_price, " +
                        "product_variant_version_tax_rate_id, tax_rate_version_id, tax_rate_version_rate, tax_rate_version_version, " +
                        "shopping_cart_item_id, count, compensatable_amount, shipment_method_id) VALUES (:id, :orderId, :position, " +
                        ":createdAt, :productVariantId, :visible, :versionId, :price, :taxRateId, :taxRateVersionId, :rate, :version, " +
                        ":shoppingCartItemId, :count, :amount, :shipmentMethodId)"
                ).bind("id", item.id).bind("orderId", order.id).bind("position", position).bind("createdAt", item.createdAt)
                    .bind("productVariantId", item.productVariantId).bind("visible", item.productVariantIsPubliclyVisible)
                    .bind("versionId", item.productVariantVersion.id).bind("price", item.productVariantVersion.price)
                    .bind("taxRateId", item.productVariantVersion.taxRateId).bind("taxRateVersionId", item.taxRateVersion.id)
                    .bind("rate", item.taxRateVersion.rate).bind("version", item.taxRateVersion.version)
                    .bind("shoppingCartItemId", item.shoppingCartItemId).bind("count", item.count)
                    .bind("amount", item.compensatableAmount).bind("shipmentMethodId", item.shipmentMethodId)
                    .fetch().awaitRowsUpdated()
            }
            items.forEach { (item, discounts) ->
                discounts.forEach { discount ->
                    client.sql("INSERT INTO order_item_discounts (order_item_id, discount_id, discount) VALUES (:itemId, :id, :discount)")
                        .bind("itemId", item.id).bind("id", discount.id).bind("discount", discount.discount)
                        .fetch().awaitRowsUpdated()
                }
            }
        }
    }

    /**
     * `$set: {order_status: PLACED, placed_at}` only while the order is PENDING (FIXED: compare-and-set, the original's
     * read-then-write let two concurrent placements both succeed and publish). Returns whether the order was placed.
     */
    suspend fun setPlacedIfPending(id: UUID, placedAt: Instant): Boolean =
        client.sql("UPDATE orders SET order_status = 'PLACED', placed_at = :placedAt WHERE id = :id AND order_status = 'PENDING'")
            .bind("id", id).bind("placedAt", placedAt).fetch().awaitRowsUpdated() > 0

    /** `$set: {order_status: REJECTED}` only while the order is PENDING (FIXED: a PLACED order is never rejected). */
    suspend fun setRejectedIfPending(id: UUID): Boolean =
        client.sql("UPDATE orders SET order_status = 'REJECTED' WHERE id = :id AND order_status = 'PENDING'")
            .bind("id", id).fetch().awaitRowsUpdated() > 0

    // ---------------------------------------------------------------- replicas

    /** `users.find_one({_id})` != null. */
    suspend fun userExists(id: UUID): Boolean =
        client.sql("SELECT id FROM users WHERE id = :id").bind("id", id)
            .map { r -> r.get("id", UUID::class.java)!! }.awaitOneOrNull() != null

    /** `find({_id: {$in: ids}})` on an id-only collection ([table] is a trusted constant). */
    suspend fun existingIds(table: String, ids: Collection<UUID>): Set<UUID> {
        return client.sql("SELECT id FROM $table WHERE id = ANY(:ids)").bind("ids", ids.toTypedArray())
            .map { r -> r.get("id", UUID::class.java)!! }.flow().toList().toSet()
    }

    /** `product_variants.find({_id: {$in: ids}})`. */
    suspend fun findProductVariants(ids: Collection<UUID>): List<ProductVariantRecord> {
        return client.sql("SELECT * FROM product_variants WHERE id = ANY(:ids)").bind("ids", ids.toTypedArray())
            .map { r -> productVariant(r) }.flow().toList()
    }

    /** `product_variants.find_one({_id})`. */
    suspend fun findProductVariant(id: UUID): ProductVariantRecord? =
        client.sql("SELECT * FROM product_variants WHERE id = :id").bind("id", id)
            .map { r -> productVariant(r) }.awaitOneOrNull()

    /**
     * Sets the current version of a variant, inserting the variant as publicly visible if it is unknown (one atomic
     * upsert; FIXED: the original's select-then-insert failed with a duplicate key under concurrency/redelivery).
     * The visibility of a known variant is unchanged.
     */
    suspend fun upsertProductVariantVersion(id: UUID, version: ProductVariantVersionRecord) {
        client.sql(
            """
            INSERT INTO product_variants (id, current_version_id, current_version_price, current_version_tax_rate_id, is_publicly_visible)
            VALUES (:id, :versionId, :price, :taxRateId, TRUE)
            ON CONFLICT (id) DO UPDATE SET current_version_id = EXCLUDED.current_version_id,
              current_version_price = EXCLUDED.current_version_price,
              current_version_tax_rate_id = EXCLUDED.current_version_tax_rate_id
            """.trimIndent()
        ).bind("id", id).bind("versionId", version.id).bind("price", version.price).bind("taxRateId", version.taxRateId)
            .fetch().awaitRowsUpdated()
    }

    /** `update_one({_id}, {$set: {is_publicly_visible}})` (no upsert; FIXED: a boolean, docs/fixes/order.md). */
    suspend fun setVisibility(id: UUID, isPubliclyVisible: Boolean) {
        client.sql("UPDATE product_variants SET is_publicly_visible = :visible, visibility_raw = NULL WHERE id = :id")
            .bind("id", id).bind("visible", isPubliclyVisible).fetch().awaitRowsUpdated()
    }

    /** `tax_rates.find({_id: {$in: ids}})`. */
    suspend fun findTaxRates(ids: Collection<UUID>): List<TaxRateRecord> {
        return client.sql("SELECT * FROM tax_rates WHERE id = ANY(:ids)").bind("ids", ids.toTypedArray())
            .map { r ->
                TaxRateRecord(
                    r.uuid("id"),
                    TaxRateVersionRecord(r.uuid("current_version_id"), r.double("current_version_rate"), r.long("current_version_version"))
                )
            }.flow().toList()
    }

    /** `update_one({_id}, {$set: {_id, current_version}}, upsert: true)`. */
    suspend fun upsertTaxRate(rate: TaxRateRecord) {
        client.sql(
            """
            INSERT INTO tax_rates (id, current_version_id, current_version_rate, current_version_version)
            VALUES (:id, :versionId, :rate, :version)
            ON CONFLICT (id) DO UPDATE SET current_version_id = EXCLUDED.current_version_id,
              current_version_rate = EXCLUDED.current_version_rate, current_version_version = EXCLUDED.current_version_version
            """.trimIndent()
        ).bind("id", rate.id).bind("versionId", rate.currentVersion.id).bind("rate", rate.currentVersion.rate)
            .bind("version", rate.currentVersion.version).fetch().awaitRowsUpdated()
    }

    /**
     * `insert_one({_id})` into `coupons`, `shipment_methods` or `users` ([table] is a trusted constant); an existing id
     * is kept (FIXED: idempotent, the original answered 500 for a redelivered event forever).
     */
    suspend fun insertId(table: String, id: UUID) {
        client.sql("INSERT INTO $table (id) VALUES (:id) ON CONFLICT (id) DO NOTHING").bind("id", id).fetch().awaitRowsUpdated()
    }

    /**
     * Adds an address to the user (FIXED: idempotent - no duplicate on redelivery - and an upsert of the user, so that
     * an address event processed before the user's creation event is not lost; the address service only creates
     * addresses of existing users). One transaction: the user row, then the `user_addresses` row.
     */
    suspend fun addUserAddress(userId: UUID, addressId: UUID) {
        transaction.executeAndAwait {
            insertId("users", userId)
            client.sql("INSERT INTO user_addresses (user_id, id) VALUES (:userId, :addressId) ON CONFLICT DO NOTHING")
                .bind("userId", userId).bind("addressId", addressId).fetch().awaitRowsUpdated()
        }
    }

    /** `users.update_one({_id: userId}, {$pull: {user_address_ids: id}})`. */
    suspend fun pullUserAddress(userId: UUID, addressId: UUID) {
        client.sql("DELETE FROM user_addresses WHERE user_id = :userId AND id = :addressId")
            .bind("userId", userId).bind("addressId", addressId).fetch().awaitRowsUpdated()
    }

    /** Which of [addressIds] are addresses of the user (`createOrder` validates the shipment and invoice address). */
    suspend fun userAddressIds(userId: UUID, addressIds: Collection<UUID>): Set<UUID> =
        client.sql("SELECT id FROM user_addresses WHERE user_id = :userId AND id = ANY(:ids)")
            .bind("userId", userId).bind("ids", addressIds.toTypedArray())
            .map { r -> r.uuid("id") }.flow().toList().toSet()

    /** All address ids of the user (tests only). */
    suspend fun allUserAddressIds(userId: UUID): Set<UUID> =
        client.sql("SELECT id FROM user_addresses WHERE user_id = :userId").bind("userId", userId)
            .map { r -> r.uuid("id") }.flow().toList().toSet()

    // ---------------------------------------------------------------- compensations

    /** The ids of the items of [orderId] that are already compensated. */
    suspend fun compensatedItemIds(orderId: UUID): Set<UUID> =
        client.sql(
            "SELECT DISTINCT ci.order_item_id FROM order_compensation_items ci " +
                "JOIN order_compensations c ON c.id = ci.order_compensation_id WHERE c.order_id = :orderId"
        ).bind("orderId", orderId).map { r -> r.uuid("order_item_id") }.flow().toList().toSet()

    /** `insert_one(order_compensation)`: the compensation and its items, in one transaction. */
    suspend fun insertCompensation(compensation: OrderCompensationRecord) {
        transaction.executeAndAwait {
            client.sql(
                """
                INSERT INTO order_compensations (id, order_id, triggered_at, amount_to_compensate)
                VALUES (:id, :orderId, :triggeredAt, :amount)
                """.trimIndent()
            ).bind("id", compensation.id).bind("orderId", compensation.orderId).bind("triggeredAt", compensation.triggeredAt)
                .bind("amount", compensation.amountToCompensate).fetch().awaitRowsUpdated()
            compensation.orderItemIds.forEachIndexed { position, itemId ->
                client.sql(
                    "INSERT INTO order_compensation_items (order_compensation_id, order_item_id, position) VALUES (:id, :itemId, :position)"
                ).bind("id", compensation.id).bind("itemId", itemId).bind("position", position).fetch().awaitRowsUpdated()
            }
        }
    }

    /** All compensations of an order (tests only). */
    suspend fun compensationsOfOrder(orderId: UUID): List<OrderCompensationRecord> {
        val compensations = client.sql("SELECT * FROM order_compensations WHERE order_id = :orderId").bind("orderId", orderId)
            .map { r -> Triple(r.uuid("id"), r.get("triggered_at", Instant::class.java)!!, r.long("amount_to_compensate")) }
            .flow().toList()
        return compensations.map { (id, triggeredAt, amount) ->
            val itemIds = client.sql("SELECT order_item_id FROM order_compensation_items WHERE order_compensation_id = :id ORDER BY position")
                .bind("id", id).map { r -> r.uuid("order_item_id") }.flow().toList()
            OrderCompensationRecord(id, orderId, itemIds, triggeredAt, amount)
        }
    }

    // ---------------------------------------------------------------- mapping

    private fun productVariant(r: Readable) = ProductVariantRecord(
        r.uuid("id"),
        ProductVariantVersionRecord(r.uuid("current_version_id"), r.long("current_version_price"), r.uuid("current_version_tax_rate_id")),
        r.get("is_publicly_visible", java.lang.Boolean::class.java)!!.booleanValue(),
        r.get("visibility_raw", String::class.java),
    )

    private fun order(r: Readable) = OrderRecord(
        id = r.uuid("id"), userId = r.uuid("user_id"), createdAt = r.get("created_at", Instant::class.java)!!,
        orderStatus = OrderStatus.valueOf(r.get("order_status", String::class.java)!!),
        placedAt = r.get("placed_at", Instant::class.java),
        rejectionReason = r.get("rejection_reason", String::class.java)?.let { RejectionReason.valueOf(it) },
        shipmentAddressId = r.uuid("shipment_address_id"), invoiceAddressId = r.uuid("invoice_address_id"),
        compensatableOrderAmount = r.long("compensatable_order_amount"),
        paymentInformationId = r.uuid("payment_information_id"), vatNumber = r.get("vat_number", String::class.java),
    )

    private fun orderItem(r: Readable) = OrderItemRecord(
        id = r.uuid("id"),
        orderId = r.uuid("order_id"),
        createdAt = r.get("created_at", Instant::class.java)!!,
        productVariantId = r.uuid("product_variant_id"),
        productVariantIsPubliclyVisible = r.get("product_variant_is_publicly_visible", java.lang.Boolean::class.java)!!.booleanValue(),
        productVariantVersion = ProductVariantVersionRecord(
            r.uuid("product_variant_version_id"), r.long("product_variant_version_price"), r.uuid("product_variant_version_tax_rate_id")
        ),
        taxRateVersion = TaxRateVersionRecord(r.uuid("tax_rate_version_id"), r.double("tax_rate_version_rate"), r.long("tax_rate_version_version")),
        shoppingCartItemId = r.uuid("shopping_cart_item_id"),
        count = r.long("count"),
        compensatableAmount = r.long("compensatable_amount"),
        shipmentMethodId = r.uuid("shipment_method_id"),
    )

    private fun discount(r: Readable) = DiscountRecord(r.uuid("discount_id"), r.double("discount"))
}

private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(name: String, value: T?, type: Class<T>) =
    if (value != null) bind(name, value) else bindNull(name, type)

private fun Readable.uuid(name: String): UUID = get(name, UUID::class.java)!!
private fun Readable.long(name: String): Long = (get(name) as Number).toLong()
private fun Readable.double(name: String): Double = (get(name) as Number).toDouble()
