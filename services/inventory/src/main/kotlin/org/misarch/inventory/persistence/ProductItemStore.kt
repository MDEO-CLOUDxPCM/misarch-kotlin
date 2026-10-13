package org.misarch.inventory.persistence

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * A product item document of the original (`productitems` collection) as a row of `ProductItemEntity`.
 * [productVariant] is null only for the "phantom" items inserted by the release upsert.
 */
data class ProductItemRow(
    val id: String,
    val productVariant: String?,
    val inventoryStatus: String,
    val orderId: String?,
)

/** A filter value of the original `countDocuments(filter)`: absent (no condition), `null` or a value. */
sealed interface FilterValue {
    data object Absent : FilterValue
    data object Null : FilterValue
    data class Value(val value: String) : FilterValue
}

/**
 * SQL access to `ProductItemEntity` and `ProductVariantPartialEntity`, mirroring the Mongo operations of the
 * original one to one (see docs/specs/inventory.md §6).
 */
@Repository
class ProductItemStore(private val db: DatabaseClient) {

    private fun map(row: Readable) = ProductItemRow(
        id = row.get("id", String::class.java)!!,
        productVariant = row.get("productvariant", String::class.java),
        inventoryStatus = row.get("inventorystatus", String::class.java)!!,
        orderId = row.get("orderid", String::class.java),
    )

    /**
     * `find(filter).sort({_id: dir}).limit(first).skip(skip)` with the same filter semantics as [count].
     * FIXED (docs/fixes/inventory.md): the original mapped the filter fields to non-existent Mongo fields, so any set
     * filter field returned no nodes.
     */
    suspend fun findAll(
        ascending: Boolean,
        first: Long,
        skip: Long,
        productVariant: FilterValue = FilterValue.Absent,
        inventoryStatus: FilterValue = FilterValue.Absent,
    ): List<ProductItemRow> {
        val (where, binds) = where(productVariant, inventoryStatus)
        var spec = db.sql("SELECT * FROM ProductItemEntity$where ORDER BY id ${if (ascending) "ASC" else "DESC"} LIMIT :first OFFSET :skip")
            .bind("first", first).bind("skip", skip)
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map(::map).all().collectList().awaitSingle()
    }

    /** `countDocuments(filter)` with Mongo equality semantics (`null` matches null and missing). */
    suspend fun count(productVariant: FilterValue, inventoryStatus: FilterValue): Long {
        val (where, binds) = where(productVariant, inventoryStatus)
        var spec = db.sql("SELECT COUNT(*) AS n FROM ProductItemEntity$where")
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map { row -> (row.get("n") as Number).toLong() }.one().awaitSingle()
    }

    private fun where(productVariant: FilterValue, inventoryStatus: FilterValue): Pair<String, Map<String, String>> {
        val conditions = mutableListOf<String>()
        val binds = mutableMapOf<String, String>()
        fun add(column: String, value: FilterValue) {
            when (value) {
                FilterValue.Absent -> {}
                FilterValue.Null -> conditions += "$column IS NULL"
                is FilterValue.Value -> {
                    conditions += "$column = :$column"
                    binds[column] = value.value
                }
            }
        }
        add("productVariant", productVariant)
        add("inventoryStatus", inventoryStatus)
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        return where to binds
    }

    suspend fun findById(id: String): ProductItemRow? =
        db.sql("SELECT * FROM ProductItemEntity WHERE id = :id").bind("id", id)
            .map(::map).one().awaitFirstOrNull()

    suspend fun insert(productVariant: String, count: Int): List<ProductItemRow> {
        val rows = (0 until count).map {
            ProductItemRow(UUID.randomUUID().toString(), productVariant, "IN_STORAGE", null)
        }
        for (row in rows) {
            db.sql("INSERT INTO ProductItemEntity (id, productVariant, inventoryStatus) VALUES (:id, :pv, :status)")
                .bind("id", row.id).bind("pv", productVariant).bind("status", row.inventoryStatus)
                .fetch().awaitRowsUpdated()
        }
        return rows
    }

    /** `findOneAndUpdate({_id}, {$set: {productVariant, inventoryStatus}}, {new: true})` */
    suspend fun update(id: String, productVariant: String, inventoryStatus: String): ProductItemRow? =
        db.sql("UPDATE ProductItemEntity SET productVariant = :pv, inventoryStatus = :status WHERE id = :id RETURNING *")
            .bind("id", id).bind("pv", productVariant).bind("status", inventoryStatus)
            .map(::map).one().awaitFirstOrNull()

    /** `findByIdAndDelete` */
    suspend fun delete(id: String): ProductItemRow? =
        db.sql("DELETE FROM ProductItemEntity WHERE id = :id RETURNING *").bind("id", id)
            .map(::map).one().awaitFirstOrNull()

    /**
     * Reserves up to [number] IN_STORAGE items of the variant (natural order) for [orderId] in one statement and returns
     * them. FIXED (docs/fixes/inventory.md): `FOR UPDATE SKIP LOCKED` - concurrent reservations of the same variant take
     * different items instead of failing with a write conflict (the original's Mongo snapshot transaction aborted the
     * later writer, so one of several concurrent orders of a popular variant failed although stock was available).
     */
    suspend fun reserveAvailable(productVariant: String, number: Long, orderId: String): List<ProductItemRow> =
        db.sql(
            "UPDATE ProductItemEntity SET inventoryStatus = 'RESERVED', orderId = :orderId WHERE id IN (" +
                "SELECT id FROM ProductItemEntity WHERE productVariant = :pv AND inventoryStatus = 'IN_STORAGE' " +
                "ORDER BY seq LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING *"
        ).bind("orderId", orderId).bind("pv", productVariant).bind("limit", number)
            .map(::map).all().collectList().awaitSingle().sortedBy { it.id }

    /** The number of items of the variant assigned to [orderId] (any status). */
    suspend fun countOfOrder(productVariant: String, orderId: String): Long =
        db.sql("SELECT COUNT(*) AS n FROM ProductItemEntity WHERE orderId = :orderId AND productVariant = :pv")
            .bind("orderId", orderId).bind("pv", productVariant)
            .map { row -> (row.get("n") as Number).toLong() }.one().awaitSingle()

    /** `find({orderId})` in natural order (`orderId` null/undefined matches unassigned items, like Mongo). */
    suspend fun findIdsByOrder(orderId: String?): List<String> {
        val spec = if (orderId == null) db.sql("SELECT id FROM ProductItemEntity WHERE orderId IS NULL ORDER BY seq")
        else db.sql("SELECT id FROM ProductItemEntity WHERE orderId = :orderId ORDER BY seq").bind("orderId", orderId)
        return spec.map { row -> row.get("id", String::class.java)!! }.all().collectList().awaitSingle()
    }

    /**
     * `updateMany({_id: {$in: ids}}, {$set: {inventoryStatus: IN_STORAGE, orderId: null}})`.
     * FIXED (docs/fixes/inventory.md): the original used `upsert: true`, so a release with nothing assigned inserted a
     * "phantom" IN_STORAGE item without product variant; nothing is inserted now.
     */
    suspend fun release(ids: List<String>) {
        if (ids.isEmpty()) return
        db.sql("UPDATE ProductItemEntity SET inventoryStatus = 'IN_STORAGE', orderId = NULL WHERE id = ANY(:ids)")
            .bind("ids", ids.toTypedArray())
            .fetch().awaitRowsUpdated()
    }

    suspend fun findByIds(ids: List<String>): List<ProductItemRow> {
        if (ids.isEmpty()) return emptyList()
        return db.sql("SELECT * FROM ProductItemEntity WHERE id = ANY(:ids) ORDER BY seq")
            .bind("ids", ids.toTypedArray())
            .map(::map).all().collectList().awaitSingle()
    }

    /** `updateMany({orderId}, {$set: {inventoryStatus: status}})` */
    suspend fun updateStatusByOrder(orderId: String?, status: String) {
        val spec = if (orderId == null) {
            db.sql("UPDATE ProductItemEntity SET inventoryStatus = :status WHERE orderId IS NULL")
        } else {
            db.sql("UPDATE ProductItemEntity SET inventoryStatus = :status WHERE orderId = :orderId").bind("orderId", orderId)
        }
        spec.bind("status", status).fetch().awaitRowsUpdated()
    }

    // ---- product variant partials ----

    /** `create({_id})`; FIXED: an existing id is kept (the original's duplicate-key error terminated the process). */
    suspend fun insertPartial(id: String) {
        db.sql("INSERT INTO ProductVariantPartialEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING").bind("id", id)
            .fetch().awaitRowsUpdated()
    }

    suspend fun partialExists(id: String): Boolean =
        db.sql("SELECT 1 FROM ProductVariantPartialEntity WHERE id = :id").bind("id", id)
            .map { _ -> true }.one().awaitFirstOrNull() ?: false
}
