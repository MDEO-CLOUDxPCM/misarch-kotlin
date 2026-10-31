package org.misarch.shoppingcart.persistence

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A row of `ShoppingCartItemEntity` (embedded `ShoppingCartItem` document of the original).
 */
data class ShoppingCartItemRow(
    val id: UUID,
    val userId: UUID,
    val count: Long,
    val addedAt: OffsetDateTime,
    val productVariantId: UUID,
)

/**
 * A row of `UserEntity`: the user with the flattened fields of its embedded shopping cart (`users` document of the
 * original without the items, which are rows of `ShoppingCartItemEntity`).
 */
data class UserRow(
    val id: UUID,
    val shoppingcartLastUpdatedAt: OffsetDateTime,
)

/**
 * SQL access for the former `users` / `product_variants` collections (see the Flyway migration). Relational like the
 * Kotlin services (docs/PORTING.md §6): a user (with its cart's `lastUpdatedAt`) is one `UserEntity` row; the cart's
 * items are read by `ShoppingCart.shoppingcartItems` as a [ConnectionQuery] (page, probe, count - each only when
 * selected).
 */
@Repository
class ShoppingcartRepository(entityTemplate: R2dbcEntityTemplate) {

    private val db = entityTemplate.databaseClient

    /**
     * `user/user/created`: new user with an empty cart. A duplicate id changes nothing (fix C3, docs/fixes/shoppingcart.md;
     * the original failed with a duplicate key error, HTTP 500 and endless redelivery).
     */
    suspend fun insertUser(id: UUID, lastUpdatedAt: OffsetDateTime) {
        db.sql("INSERT INTO UserEntity (id, shoppingcartLastUpdatedAt) VALUES (:id, :lastUpdatedAt) ON CONFLICT (id) DO NOTHING")
            .bind("id", id)
            .bind("lastUpdatedAt", lastUpdatedAt)
            .await()
    }

    /** `catalog/product-variant/created`. A duplicate id changes nothing (fix C3). */
    suspend fun insertProductVariant(id: UUID) {
        db.sql("INSERT INTO ProductVariantEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING").bind("id", id).await()
    }

    /** The subset of [ids] that are known product variants (one `$in` lookup). */
    suspend fun existingProductVariants(ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) {
            return emptySet()
        }
        return db.sql("SELECT id FROM ProductVariantEntity WHERE id = ANY(:ids)").bind("ids", ids.toTypedArray())
            .map { row -> row.uuid("id") }.flow().toList().toSet()
    }

    suspend fun userExists(id: UUID): Boolean =
        db.sql("SELECT 1 FROM UserEntity WHERE id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    suspend fun productVariantExists(id: UUID): Boolean =
        db.sql("SELECT 1 FROM ProductVariantEntity WHERE id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    /** The user's row (with the cart's `lastUpdatedAt`), or null. */
    suspend fun findUser(id: UUID): UserRow? =
        db.sql("SELECT id, shoppingcartLastUpdatedAt AS last_updated_at FROM UserEntity WHERE id = :id").bind("id", id)
            .map { row -> UserRow(row.uuid("id"), row.timestamp("last_updated_at")) }.awaitOneOrNull()

    /**
     * `ShoppingCart.shoppingcartItems`: the items of the user's cart by id (byte order = Rust `Uuid` order),
     * [first] null = all.
     */
    fun itemsOfUser(userId: UUID, first: Int?, skip: Int, ascending: Boolean): ConnectionQuery<ShoppingCartItemRow> =
        ConnectionQuery(
            db, "ShoppingCartItemEntity", Condition.eq("t.userId", userId), listOf(if (ascending) "id ASC" else "id DESC"),
            first, skip,
        ) { row, _ ->
            ShoppingCartItemRow(
                id = row.uuid("id"),
                userId = row.uuid("userid"),
                count = (row.get("count") as Number).toLong(),
                addedAt = row.timestamp("addedat"),
                productVariantId = row.uuid("productvariantid"),
            )
        }

    /** The cart item [id] (in any cart), or null. */
    suspend fun findItem(id: UUID): ShoppingCartItemRow? =
        db.sql(
            "SELECT id, userId AS user_id, count, addedAt AS added_at, productVariantId AS product_variant_id " +
                "FROM ShoppingCartItemEntity WHERE id = :id"
        ).bind("id", id).map { row -> row.toItem() }.awaitOneOrNull()

    /**
     * `createShoppingcartItem` (fix C1): returns the user's existing item for the product variant (the first one
     * added), or inserts [item] and sets the cart's `lastUpdatedAt` to its `addedAt` (fix C4). The user row is locked,
     * so concurrent creations for one user cannot both insert.
     */
    @Transactional
    suspend fun findOrInsertItem(item: ShoppingCartItemRow): ShoppingCartItemRow {
        db.sql("SELECT id FROM UserEntity WHERE id = :id FOR UPDATE").bind("id", item.userId).map { _ -> 1 }.awaitOneOrNull()
        val existing = db.sql(
            "SELECT id, userId AS user_id, count, addedAt AS added_at, productVariantId AS product_variant_id " +
                "FROM ShoppingCartItemEntity WHERE userId = :userId AND productVariantId = :productVariantId " +
                "ORDER BY addedAt, id LIMIT 1"
        ).bind("userId", item.userId).bind("productVariantId", item.productVariantId)
            .map { row -> row.toItem() }.awaitOneOrNull()
        if (existing != null) {
            return existing
        }
        insertItem(item)
        touchUser(item.userId, item.addedAt)
        return item
    }

    /** Sets the cart's `lastUpdatedAt` (fix C4). */
    private suspend fun touchUser(userId: UUID, lastUpdatedAt: OffsetDateTime) {
        db.sql("UPDATE UserEntity SET shoppingcartLastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", userId)
            .await()
    }

    /** Sets `lastUpdatedAt` of the cart that contains item [itemId] (fix C4). */
    private suspend fun touchUserOfItem(itemId: UUID, lastUpdatedAt: OffsetDateTime) {
        db.sql(
            "UPDATE UserEntity SET shoppingcartLastUpdatedAt = :lastUpdatedAt " +
                "WHERE id = (SELECT userId FROM ShoppingCartItemEntity WHERE id = :itemId)"
        )
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("itemId", itemId)
            .await()
    }

    suspend fun insertItem(item: ShoppingCartItemRow) {
        db.sql(
            "INSERT INTO ShoppingCartItemEntity (id, userId, count, addedAt, productVariantId) " +
                "VALUES (:id, :userId, :count, :addedAt, :productVariantId)"
        )
            .bind("id", item.id)
            .bind("userId", item.userId)
            .bind("count", item.count)
            .bind("addedAt", item.addedAt)
            .bind("productVariantId", item.productVariantId)
            .await()
    }

    /**
     * Replaces all items of the cart and sets `lastUpdatedAt` (atomic and last writer wins, like the original's
     * single-document update): the UPDATE of the user row comes first and locks it, so a concurrent replacement waits
     * and then deletes this one's items instead of leaving the union of both carts.
     */
    @Transactional
    suspend fun replaceItems(userId: UUID, items: List<ShoppingCartItemRow>, lastUpdatedAt: OffsetDateTime) {
        db.sql("UPDATE UserEntity SET shoppingcartLastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", userId)
            .await()
        db.sql("DELETE FROM ShoppingCartItemEntity WHERE userId = :userId").bind("userId", userId).await()
        for (item in items) {
            insertItem(item)
        }
    }

    /** Sets the item's count and the cart's `lastUpdatedAt` (fix C4). */
    @Transactional
    suspend fun updateItemCount(id: UUID, count: Long, lastUpdatedAt: OffsetDateTime) {
        db.sql("UPDATE ShoppingCartItemEntity SET count = :count WHERE id = :id").bind("count", count).bind("id", id)
            .await()
        touchUserOfItem(id, lastUpdatedAt)
    }

    /** Removes the item and sets the cart's `lastUpdatedAt` (fix C4). */
    @Transactional
    suspend fun deleteItem(id: UUID, lastUpdatedAt: OffsetDateTime) {
        touchUserOfItem(id, lastUpdatedAt)
        db.sql("DELETE FROM ShoppingCartItemEntity WHERE id = :id").bind("id", id).await()
    }

    /**
     * `$pull` of the ordered items from the cart of [userId] (unknown user or ids: no-op); if items were removed, the
     * cart's `lastUpdatedAt` is set (fix C4).
     */
    @Transactional
    suspend fun deleteItemsOfUser(userId: UUID, ids: List<UUID>, lastUpdatedAt: OffsetDateTime): Long {
        if (ids.isEmpty()) {
            return 0
        }
        val deleted = db.sql("DELETE FROM ShoppingCartItemEntity WHERE userId = :userId AND id = ANY(:ids)")
            .bind("userId", userId)
            .bind("ids", ids.toTypedArray())
            .fetch()
            .awaitRowsUpdated()
        if (deleted > 0) {
            touchUser(userId, lastUpdatedAt)
        }
        return deleted
    }

    private fun Readable.toItem() = ShoppingCartItemRow(
        id = uuid("id"),
        userId = uuid("user_id"),
        count = (get("count") as Number).toLong(),
        addedAt = timestamp("added_at"),
        productVariantId = uuid("product_variant_id"),
    )

    private fun Readable.uuid(name: String): UUID = get(name, UUID::class.java)!!

    private fun Readable.timestamp(name: String): OffsetDateTime =
        get(name, OffsetDateTime::class.java)!!.withOffsetSameInstant(ZoneOffset.UTC)
}
