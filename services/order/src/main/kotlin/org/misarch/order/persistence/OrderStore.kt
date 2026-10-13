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
import org.misarch.common.rust.RustUuid
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * All database access of the order service (SQL over R2DBC). Each function corresponds to one MongoDB operation of
 * the original and - to keep the cost profile of the document store - costs one round trip: an order is read with its
 * items and their discounts in one query (one `find`), and written with one statement (one `insert_one`, atomic).
 */
@Component
class OrderStore(
    private val client: DatabaseClient,
) {

    // ---------------------------------------------------------------- orders

    /** `orders.find_one({_id})`. */
    suspend fun findOrder(id: UUID): OrderRecord? =
        selectOrders("orders o", "WHERE o.id = :id", "i.position") { it.bind("id", id) }.singleOrNull()

    /**
     * The order that contains the order item [itemId] (FIXED, docs/fixes/order.md: the original looked the item up in
     * a never-written collection, so every lookup failed).
     */
    suspend fun findOrderOfItem(itemId: UUID): OrderRecord? =
        selectOrders("orders o", "WHERE o.id = (SELECT order_id FROM order_items WHERE id = :itemId)", "i.position") {
            it.bind("itemId", itemId)
        }.singleOrNull()

    /** `count_documents({"user._id": userId})`. */
    suspend fun countOrdersOfUser(userId: UUID): Long =
        client.sql("SELECT COUNT(*) AS c FROM orders WHERE user_id = :userId").bind("userId", userId)
            .map { r -> (r.get("c") as Number).toLong() }.awaitSingle()

    /**
     * `find({"user._id": userId}).sort(...).skip(offset).limit(limit)` (one query: the page of orders with their items).
     *
     * @param orderBy SQL ORDER BY clause (trusted constant from [org.misarch.order.graphql.model.OrderOrderField])
     */
    suspend fun findOrdersOfUser(userId: UUID, orderBy: String, limit: Long, offset: Long?): List<OrderRecord> {
        val page = "(SELECT *, row_number() OVER (ORDER BY $orderBy) AS rn FROM orders WHERE user_id = :userId " +
            "ORDER BY $orderBy LIMIT :limit${if (offset != null) " OFFSET :offset" else ""}) o"
        return selectOrders(page, "", "o.rn, i.position") { spec ->
            val bound = spec.bind("userId", userId).bind("limit", limit)
            if (offset != null) bound.bind("offset", offset) else bound
        }
    }

    /**
     * `insert_one(order)`: the order, its items and their discounts in ONE statement (data-modifying CTEs), atomic like
     * the insert of one document.
     */
    suspend fun insertOrder(order: OrderRecord) {
        val statements = mutableListOf<String>()
        val values = LinkedHashMap<String, Pair<Any?, Class<*>>>()
        fun param(name: String, value: Any?, type: Class<*>): String {
            values[name] = value to type
            return ":$name"
        }
        statements += "INSERT INTO orders (id, user_id, created_at, order_status, placed_at, rejection_reason, " +
            "shipment_address_id, invoice_address_id, compensatable_order_amount, payment_information_id, vat_number) VALUES (" +
            listOf(
                param("o_id", order.id, UUID::class.java), param("o_user", order.userId, UUID::class.java),
                param("o_created", order.createdAt, Instant::class.java), param("o_status", order.orderStatus.name, String::class.java),
                param("o_placed", order.placedAt, Instant::class.java), param("o_reason", order.rejectionReason?.name, String::class.java),
                param("o_ship", order.shipmentAddressId, UUID::class.java), param("o_inv", order.invoiceAddressId, UUID::class.java),
                param("o_amount", order.compensatableOrderAmount, java.lang.Long::class.java),
                param("o_pay", order.paymentInformationId, UUID::class.java), param("o_vat", order.vatNumber, String::class.java),
            ).joinToString(", ") + ")"
        if (order.items.isNotEmpty()) {
            statements += "INSERT INTO order_items (id, order_id, position, created_at, product_variant_id, " +
                "product_variant_is_publicly_visible, product_variant_version_id, product_variant_version_price, " +
                "product_variant_version_tax_rate_id, tax_rate_version_id, tax_rate_version_rate, tax_rate_version_version, " +
                "shopping_cart_item_id, count, compensatable_amount, shipment_method_id) VALUES " +
                order.items.mapIndexed { n, item ->
                    "(" + listOf(
                        param("i${n}_id", item.id, UUID::class.java), ":o_id", param("i${n}_pos", n, Integer::class.java),
                        param("i${n}_created", item.createdAt, Instant::class.java),
                        param("i${n}_pv", item.productVariantId, UUID::class.java),
                        param("i${n}_vis", item.productVariantIsPubliclyVisible, java.lang.Boolean::class.java),
                        param("i${n}_pvv", item.productVariantVersion.id, UUID::class.java),
                        param("i${n}_price", item.productVariantVersion.price, java.lang.Long::class.java),
                        param("i${n}_tax", item.productVariantVersion.taxRateId, UUID::class.java),
                        param("i${n}_trv", item.taxRateVersion.id, UUID::class.java),
                        param("i${n}_rate", item.taxRateVersion.rate, java.lang.Double::class.java),
                        param("i${n}_ver", item.taxRateVersion.version, java.lang.Long::class.java),
                        param("i${n}_cart", item.shoppingCartItemId, UUID::class.java),
                        param("i${n}_count", item.count, java.lang.Long::class.java),
                        param("i${n}_amount", item.compensatableAmount, java.lang.Long::class.java),
                        param("i${n}_sm", item.shipmentMethodId, UUID::class.java),
                    ).joinToString(", ") + ")"
                }.joinToString(", ")
        }
        val discounts = order.items.flatMapIndexed { n, item -> item.discounts.map { n to it } }
        if (discounts.isNotEmpty()) {
            statements += "INSERT INTO order_item_discounts (order_item_id, discount_id, discount) VALUES " +
                discounts.mapIndexed { k, (n, discount) ->
                    "(:i${n}_id, " + param("d${k}_id", discount.id, UUID::class.java) + ", " +
                        param("d${k}_value", discount.discount, java.lang.Double::class.java) + ")"
                }.joinToString(", ")
        }
        val sql = if (statements.size == 1) statements[0] else
            "WITH " + statements.dropLast(1).mapIndexed { n, statement -> "s$n AS ($statement)" }.joinToString(", ") +
                " " + statements.last()
        var spec = client.sql(sql)
        values.forEach { (name, value) ->
            spec = if (value.first != null) spec.bind(name, value.first!!) else spec.bindNull(name, value.second)
        }
        spec.fetch().awaitRowsUpdated()
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
     * Adds an address id to the user (FIXED: idempotent - no duplicate on redelivery - and an upsert, so that an
     * address event processed before the user's creation event is not lost; the address service only creates
     * addresses of existing users).
     */
    suspend fun addUserAddress(userId: UUID, addressId: UUID) {
        client.sql(
            """
            INSERT INTO users (id, user_address_ids) VALUES (:userId, ARRAY[:addressId]::uuid[])
            ON CONFLICT (id) DO UPDATE SET user_address_ids = CASE
              WHEN :addressId = ANY(users.user_address_ids) THEN users.user_address_ids
              ELSE array_append(users.user_address_ids, :addressId) END
            """.trimIndent()
        ).bind("userId", userId).bind("addressId", addressId).fetch().awaitRowsUpdated()
    }

    /** `users.update_one({_id: userId}, {$pull: {user_address_ids: id}})` (removes all occurrences). */
    suspend fun pullUserAddress(userId: UUID, addressId: UUID) {
        client.sql("UPDATE users SET user_address_ids = array_remove(user_address_ids, :addressId) WHERE id = :userId")
            .bind("userId", userId).bind("addressId", addressId).fetch().awaitRowsUpdated()
    }

    /** The address ids of a user, null if the user is unknown (used by `createOrder` to validate the addresses). */
    suspend fun userAddressIds(userId: UUID): List<UUID>? =
        client.sql("SELECT user_address_ids FROM users WHERE id = :id").bind("id", userId)
            .map { r -> r.get("user_address_ids", Array<UUID>::class.java)!!.toList() }.awaitOneOrNull()

    // ---------------------------------------------------------------- compensations

    /** The ids of the items of [orderId] that are already compensated. */
    suspend fun compensatedItemIds(orderId: UUID): Set<UUID> =
        client.sql("SELECT DISTINCT unnest(order_item_ids) AS item_id FROM order_compensations WHERE order_id = :orderId")
            .bind("orderId", orderId).map { r -> r.get("item_id", UUID::class.java)!! }.flow().toList().toSet()

    /** `insert_one(order_compensation)`. */
    suspend fun insertCompensation(compensation: OrderCompensationRecord) {
        client.sql(
            """
            INSERT INTO order_compensations (id, order_id, order_item_ids, triggered_at, amount_to_compensate)
            VALUES (:id, :orderId, :itemIds, :triggeredAt, :amount)
            """.trimIndent()
        ).bind("id", compensation.id).bind("orderId", compensation.orderId)
            .bind("itemIds", compensation.orderItemIds.toTypedArray()).bind("triggeredAt", compensation.triggeredAt)
            .bind("amount", compensation.amountToCompensate).fetch().awaitRowsUpdated()
    }

    /** All compensations of an order (tests only). */
    suspend fun compensationsOfOrder(orderId: UUID): List<OrderCompensationRecord> =
        client.sql("SELECT * FROM order_compensations WHERE order_id = :orderId").bind("orderId", orderId)
            .map { r ->
                OrderCompensationRecord(
                    r.uuid("id"), r.uuid("order_id"), r.get("order_item_ids", Array<UUID>::class.java)!!.toList(),
                    r.get("triggered_at", Instant::class.java)!!, r.long("amount_to_compensate")
                )
            }.flow().toList()

    // ---------------------------------------------------------------- mapping

    private fun productVariant(r: Readable) = ProductVariantRecord(
        r.uuid("id"),
        ProductVariantVersionRecord(r.uuid("current_version_id"), r.long("current_version_price"), r.uuid("current_version_tax_rate_id")),
        r.get("is_publicly_visible", java.lang.Boolean::class.java)!!.booleanValue(),
        r.get("visibility_raw", String::class.java),
    )

    /**
     * Reads orders with their items and discounts in one query ([source] must alias the orders as `o`), in the order
     * [orderBy] (over `o`/`i` columns); discounts are sorted by id (Rust `BTreeSet`).
     */
    private suspend fun selectOrders(
        source: String,
        where: String,
        orderBy: String,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): List<OrderRecord> {
        val sql = """
            SELECT o.id AS o_id, o.user_id, o.created_at AS o_created_at, o.order_status, o.placed_at, o.rejection_reason,
              o.shipment_address_id, o.invoice_address_id, o.compensatable_order_amount, o.payment_information_id,
              o.vat_number, i.id AS i_id, i.created_at AS i_created_at, i.product_variant_id,
              i.product_variant_is_publicly_visible, i.product_variant_version_id, i.product_variant_version_price,
              i.product_variant_version_tax_rate_id, i.tax_rate_version_id, i.tax_rate_version_rate,
              i.tax_rate_version_version, i.shopping_cart_item_id, i.count, i.compensatable_amount, i.shipment_method_id,
              d.discount_id, d.discount
            FROM $source
            LEFT JOIN order_items i ON i.order_id = o.id
            LEFT JOIN order_item_discounts d ON d.order_item_id = i.id
            $where
            ORDER BY $orderBy
        """.trimIndent()
        val rows = bind(client.sql(sql)).map { r -> JoinedRow.of(r) }.flow().toList()
        val orders = LinkedHashMap<UUID, Pair<OrderRecord, LinkedHashMap<UUID, OrderItemRecord>>>()
        for (row in rows) {
            val (_, items) = orders.getOrPut(row.order.id) { row.order to LinkedHashMap() }
            val item = row.item ?: continue
            val current = items.getOrPut(item.id) { item }
            if (row.discount != null) items[item.id] = current.copy(discounts = current.discounts + row.discount)
        }
        return orders.values.map { (order, items) ->
            order.copy(items = items.values.map { item ->
                item.copy(discounts = item.discounts.sortedWith { a, b -> RustUuid.ORDER.compare(a.id, b.id) })
            })
        }
    }

    /** One row of [selectOrders]: the order (without items), an item (or null) and a discount (or null). */
    private class JoinedRow(val order: OrderRecord, val item: OrderItemRecord?, val discount: DiscountRecord?) {
        companion object {
            fun of(r: Readable): JoinedRow {
                val order = OrderRecord(
                    id = r.uuid("o_id"), userId = r.uuid("user_id"), createdAt = r.get("o_created_at", Instant::class.java)!!,
                    orderStatus = OrderStatus.valueOf(r.get("order_status", String::class.java)!!),
                    placedAt = r.get("placed_at", Instant::class.java),
                    rejectionReason = r.get("rejection_reason", String::class.java)?.let { RejectionReason.valueOf(it) },
                    items = emptyList(), shipmentAddressId = r.uuid("shipment_address_id"),
                    invoiceAddressId = r.uuid("invoice_address_id"), compensatableOrderAmount = r.long("compensatable_order_amount"),
                    paymentInformationId = r.uuid("payment_information_id"), vatNumber = r.get("vat_number", String::class.java),
                )
                val itemId = r.get("i_id", UUID::class.java)
                val item = itemId?.let {
                    OrderItemRecord(
                        id = it,
                        createdAt = r.get("i_created_at", Instant::class.java)!!,
                        productVariantId = r.uuid("product_variant_id"),
                        productVariantIsPubliclyVisible = r.get("product_variant_is_publicly_visible", java.lang.Boolean::class.java)!!.booleanValue(),
                        productVariantVersion = ProductVariantVersionRecord(
                            r.uuid("product_variant_version_id"), r.long("product_variant_version_price"),
                            r.uuid("product_variant_version_tax_rate_id")
                        ),
                        taxRateVersion = TaxRateVersionRecord(
                            r.uuid("tax_rate_version_id"), r.double("tax_rate_version_rate"), r.long("tax_rate_version_version")
                        ),
                        shoppingCartItemId = r.uuid("shopping_cart_item_id"),
                        count = r.long("count"),
                        compensatableAmount = r.long("compensatable_amount"),
                        shipmentMethodId = r.uuid("shipment_method_id"),
                        discounts = emptyList(),
                    )
                }
                val discount = r.get("discount_id", UUID::class.java)?.let { DiscountRecord(it, r.double("discount")) }
                return JoinedRow(order, item, discount)
            }
        }
    }
}

private fun Readable.uuid(name: String): UUID = get(name, UUID::class.java)!!
private fun Readable.long(name: String): Long = (get(name) as Number).toLong()
private fun Readable.double(name: String): Double = (get(name) as Number).toDouble()
