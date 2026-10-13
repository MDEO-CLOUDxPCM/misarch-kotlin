package org.misarch.shoppingcart.persistence

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
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
 * A user with the embedded shopping cart (`users` document of the original).
 */
data class UserWithCartRow(
    val id: UUID,
    val shoppingcartLastUpdatedAt: OffsetDateTime,
    val items: List<ShoppingCartItemRow>,
)

/**
 * SQL access for the former `users` / `product_variants` collections (see the Flyway migration).
 * Every read of a cart loads the whole cart in one round trip, like the original's `find_one` on the user document.
 */
@Repository
class ShoppingcartRepository(entityTemplate: R2dbcEntityTemplate) {

    private val db = entityTemplate.databaseClient

    /** `user/user/created`: new user with an empty cart. Duplicate id -> exception (primary key). */
    suspend fun insertUser(id: UUID, lastUpdatedAt: OffsetDateTime) {
        db.sql("INSERT INTO UserEntity (id, shoppingcartLastUpdatedAt) VALUES (:id, :lastUpdatedAt)")
            .bind("id", id)
            .bind("lastUpdatedAt", lastUpdatedAt)
            .await()
    }

    /** `catalog/product-variant/created`. Duplicate id -> exception (primary key). */
    suspend fun insertProductVariant(id: UUID) {
        db.sql("INSERT INTO ProductVariantEntity (id) VALUES (:id)").bind("id", id).await()
    }

    suspend fun userExists(id: UUID): Boolean =
        db.sql("SELECT 1 FROM UserEntity WHERE id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    suspend fun productVariantExists(id: UUID): Boolean =
        db.sql("SELECT 1 FROM ProductVariantEntity WHERE id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    /** The user with all cart items, or null. */
    suspend fun findUserWithCart(id: UUID): UserWithCartRow? {
        val rows = db.sql(
            """
            SELECT u.id AS user_id, u.shoppingcartLastUpdatedAt AS last_updated_at, i.id AS item_id, i.count AS count,
                   i.addedAt AS added_at, i.productVariantId AS product_variant_id
            FROM UserEntity u LEFT JOIN ShoppingCartItemEntity i ON i.userId = u.id
            WHERE u.id = :id
            """.trimIndent()
        ).bind("id", id).map { row ->
            val item = if (row.get("item_id") != null) {
                ShoppingCartItemRow(
                    id = row.uuid("item_id"),
                    userId = id,
                    count = (row.get("count") as Number).toLong(),
                    addedAt = row.timestamp("added_at"),
                    productVariantId = row.uuid("product_variant_id"),
                )
            } else {
                null
            }
            row.timestamp("last_updated_at") to item
        }.flow().toList()
        if (rows.isEmpty()) {
            return null
        }
        val lastUpdatedAt = rows.first().first
        val items = rows.mapNotNull { it.second }
        return UserWithCartRow(id, lastUpdatedAt, items)
    }

    /** The cart item [id] (in any cart), or null. */
    suspend fun findItem(id: UUID): ShoppingCartItemRow? =
        db.sql(
            "SELECT id, userId AS user_id, count, addedAt AS added_at, productVariantId AS product_variant_id " +
                "FROM ShoppingCartItemEntity WHERE id = :id"
        ).bind("id", id).map { row -> row.toItem() }.awaitOneOrNull()

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

    /** Replaces all items of the cart and sets `lastUpdatedAt` (atomic, like the original's single-document update). */
    @Transactional
    suspend fun replaceItems(userId: UUID, items: List<ShoppingCartItemRow>, lastUpdatedAt: OffsetDateTime) {
        db.sql("DELETE FROM ShoppingCartItemEntity WHERE userId = :userId").bind("userId", userId).await()
        for (item in items) {
            insertItem(item)
        }
        db.sql("UPDATE UserEntity SET shoppingcartLastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", userId)
            .await()
    }

    suspend fun updateItemCount(id: UUID, count: Long) {
        db.sql("UPDATE ShoppingCartItemEntity SET count = :count WHERE id = :id").bind("count", count).bind("id", id)
            .await()
    }

    suspend fun deleteItem(id: UUID) {
        db.sql("DELETE FROM ShoppingCartItemEntity WHERE id = :id").bind("id", id).await()
    }

    /** `$pull` of the ordered items from the cart of [userId] (unknown user or ids: no-op). */
    suspend fun deleteItemsOfUser(userId: UUID, ids: List<UUID>): Long {
        if (ids.isEmpty()) {
            return 0
        }
        return db.sql("DELETE FROM ShoppingCartItemEntity WHERE userId = :userId AND id = ANY(:ids)")
            .bind("userId", userId)
            .bind("ids", ids.toTypedArray())
            .fetch()
            .awaitRowsUpdated()
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
