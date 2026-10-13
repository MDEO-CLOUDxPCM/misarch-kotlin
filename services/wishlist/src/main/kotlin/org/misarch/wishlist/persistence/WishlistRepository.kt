package org.misarch.wishlist.persistence

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A row of `WishlistEntity` (`wishlists` document of the original).
 */
data class WishlistRow(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val createdAt: OffsetDateTime,
    val lastUpdatedAt: OffsetDateTime,
    val productVariantIds: List<UUID>,
)

/**
 * SQL access for the former `wishlists` / `users` / `product_variants` collections (see the Flyway migration).
 */
@Repository
class WishlistRepository(entityTemplate: R2dbcEntityTemplate) {

    private val db = entityTemplate.databaseClient

    /** `user/user/created`. Idempotent (FIXED: a duplicate id answered 500 forever). */
    suspend fun insertUser(id: UUID) {
        db.sql("INSERT INTO UserEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING").bind("id", id).await()
    }

    /** `catalog/product-variant/created`. Idempotent (FIXED: a duplicate id answered 500 forever). */
    suspend fun insertProductVariant(id: UUID) {
        db.sql("INSERT INTO ProductVariantEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING").bind("id", id).await()
    }

    suspend fun userExists(id: UUID): Boolean =
        db.sql("SELECT 1 FROM UserEntity WHERE id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    /** The subset of [ids] present in the product-variant replica (one query). */
    suspend fun existingProductVariants(ids: List<UUID>): Set<UUID> =
        db.sql("SELECT id FROM ProductVariantEntity WHERE id = ANY(:ids)").bind("ids", ids.toTypedArray())
            .map { row -> row.get("id", UUID::class.java)!! }.flow().toList().toSet()

    suspend fun findWishlist(id: UUID): WishlistRow? =
        db.sql("SELECT $COLUMNS FROM WishlistEntity WHERE id = :id").bind("id", id).map { row -> row.toWishlist() }
            .awaitOneOrNull()

    suspend fun insertWishlist(wishlist: WishlistRow) {
        db.sql(
            "INSERT INTO WishlistEntity (id, userId, name, createdAt, lastUpdatedAt, productVariantIds) " +
                "VALUES (:id, :userId, :name, :createdAt, :lastUpdatedAt, :productVariantIds)"
        )
            .bind("id", wishlist.id)
            .bind("userId", wishlist.userId)
            .bind("name", wishlist.name)
            .bind("createdAt", wishlist.createdAt)
            .bind("lastUpdatedAt", wishlist.lastUpdatedAt)
            .bind("productVariantIds", wishlist.productVariantIds.toTypedArray())
            .await()
    }

    suspend fun updateProductVariants(id: UUID, productVariantIds: List<UUID>, lastUpdatedAt: OffsetDateTime) {
        db.sql("UPDATE WishlistEntity SET productVariantIds = :productVariantIds, lastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("productVariantIds", productVariantIds.toTypedArray())
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", id)
            .await()
    }

    suspend fun updateName(id: UUID, name: String, lastUpdatedAt: OffsetDateTime) {
        db.sql("UPDATE WishlistEntity SET name = :name, lastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("name", name)
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", id)
            .await()
    }

    suspend fun deleteWishlist(id: UUID) {
        db.sql("DELETE FROM WishlistEntity WHERE id = :id").bind("id", id).await()
    }

    /** `count_documents({"user._id": userId})`. */
    suspend fun countByUser(userId: UUID): Long =
        db.sql("SELECT COUNT(*) AS n FROM WishlistEntity WHERE userId = :userId").bind("userId", userId)
            .map { row -> (row.get("n") as Number).toLong() }.awaitOne()

    /**
     * `find({"user._id": userId})` with sort, limit and skip.
     *
     * @param orderBy SQL ORDER BY items (fixed column names, see `WishlistOrderField`)
     */
    suspend fun findByUser(userId: UUID, orderBy: String, limit: Long, offset: Long): List<WishlistRow> =
        db.sql("SELECT $COLUMNS FROM WishlistEntity WHERE userId = :userId ORDER BY $orderBy LIMIT :limit OFFSET :offset")
            .bind("userId", userId)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row -> row.toWishlist() }
            .flow()
            .toList()

    private fun Readable.toWishlist() = WishlistRow(
        id = get("id", UUID::class.java)!!,
        userId = get("user_id", UUID::class.java)!!,
        name = get("name", String::class.java)!!,
        createdAt = get("created_at", OffsetDateTime::class.java)!!.withOffsetSameInstant(ZoneOffset.UTC),
        lastUpdatedAt = get("last_updated_at", OffsetDateTime::class.java)!!.withOffsetSameInstant(ZoneOffset.UTC),
        productVariantIds = get("product_variant_ids", Array<UUID>::class.java)!!.toList(),
    )

    companion object {
        private const val COLUMNS = "id, userId AS user_id, name, createdAt AS created_at, " +
            "lastUpdatedAt AS last_updated_at, productVariantIds AS product_variant_ids"
    }
}
