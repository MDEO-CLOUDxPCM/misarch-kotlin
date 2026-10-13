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

    /** `find({}).sort({_id: dir}).limit(first).skip(skip)` */
    suspend fun findAll(ascending: Boolean, first: Long, skip: Long): List<ProductItemRow> =
        db.sql("SELECT * FROM ProductItemEntity ORDER BY id ${if (ascending) "ASC" else "DESC"} LIMIT :first OFFSET :skip")
            .bind("first", first).bind("skip", skip)
            .map(::map).all().collectList().awaitSingle()

    /** `countDocuments(filter)` with Mongo equality semantics (`null` matches null and missing). */
    suspend fun count(productVariant: FilterValue, inventoryStatus: FilterValue): Long {
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
        var spec = db.sql("SELECT COUNT(*) AS n FROM ProductItemEntity$where")
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map { row -> (row.get("n") as Number).toLong() }.one().awaitSingle()
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
     * `find({productVariant, inventoryStatus: IN_STORAGE}).limit(limit)` in natural order; [limit] null = no limit.
     */
    suspend fun findAvailable(productVariant: String, limit: Long?): List<String> {
        val sql = "SELECT id FROM ProductItemEntity WHERE productVariant = :pv AND inventoryStatus = 'IN_STORAGE' ORDER BY seq" +
            (if (limit != null) " LIMIT :limit" else "")
        var spec = db.sql(sql).bind("pv", productVariant)
        if (limit != null) spec = spec.bind("limit", limit)
        return spec.map { row -> row.get("id", String::class.java)!! }.all().collectList().awaitSingle()
    }

    suspend fun reserve(ids: List<String>, orderId: String) {
        if (ids.isEmpty()) return
        db.sql("UPDATE ProductItemEntity SET inventoryStatus = 'RESERVED', orderId = :orderId WHERE id = ANY(:ids)")
            .bind("orderId", orderId).bind("ids", ids.toTypedArray())
            .fetch().awaitRowsUpdated()
    }

    /** `find({orderId})` in natural order (`orderId` null/undefined matches unassigned items, like Mongo). */
    suspend fun findIdsByOrder(orderId: String?): List<String> {
        val spec = if (orderId == null) db.sql("SELECT id FROM ProductItemEntity WHERE orderId IS NULL ORDER BY seq")
        else db.sql("SELECT id FROM ProductItemEntity WHERE orderId = :orderId ORDER BY seq").bind("orderId", orderId)
        return spec.map { row -> row.get("id", String::class.java)!! }.all().collectList().awaitSingle()
    }

    /** `updateMany({_id: {$in: ids}}, {$set: {inventoryStatus: IN_STORAGE, orderId: null}}, {upsert: true})` */
    suspend fun releaseOrInsertPhantom(ids: List<String>) {
        if (ids.isEmpty()) {
            db.sql("INSERT INTO ProductItemEntity (id, inventoryStatus, orderId) VALUES (:id, 'IN_STORAGE', NULL)")
                .bind("id", UUID.randomUUID().toString())
                .fetch().awaitRowsUpdated()
        } else {
            db.sql("UPDATE ProductItemEntity SET inventoryStatus = 'IN_STORAGE', orderId = NULL WHERE id = ANY(:ids)")
                .bind("ids", ids.toTypedArray())
                .fetch().awaitRowsUpdated()
        }
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

    /** `create({_id})`: a duplicate id fails (duplicate key), like Mongo's E11000. */
    suspend fun insertPartial(id: String) {
        db.sql("INSERT INTO ProductVariantPartialEntity (id) VALUES (:id)").bind("id", id)
            .fetch().awaitRowsUpdated()
    }

    suspend fun partialExists(id: String): Boolean =
        db.sql("SELECT 1 FROM ProductVariantPartialEntity WHERE id = :id").bind("id", id)
            .map { _ -> true }.one().awaitFirstOrNull() ?: false
}
