package org.misarch.wishlist.persistence

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
 * A row of `WishlistEntity` (`wishlists` document of the original without `internal_product_variants`, which are rows
 * of `WishlistToProductVariantEntity`).
 */
data class WishlistRow(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val createdAt: OffsetDateTime,
    val lastUpdatedAt: OffsetDateTime,
)

/**
 * SQL access for the former `wishlists` / `users` / `product_variants` collections (see the Flyway migrations).
 * Relational like the Kotlin services (docs/PORTING.md §6): a wishlist is one `WishlistEntity` row; its product
 * variants (`Wishlist.productVariants`) and a user's wishlists (`User.wishlists`) are [ConnectionQuery]s whose
 * page, probe and count statements run only when selected; the variant set is written row by row.
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
        db.sql("SELECT * FROM WishlistEntity WHERE id = :id").bind("id", id).map { row -> row.toWishlist() }
            .awaitOneOrNull()

    /** The wishlist row and one link row per product variant ([productVariantIds] distinct), atomically. */
    @Transactional
    suspend fun insertWishlist(wishlist: WishlistRow, productVariantIds: List<UUID>) {
        db.sql(
            "INSERT INTO WishlistEntity (id, userId, name, createdAt, lastUpdatedAt) " +
                "VALUES (:id, :userId, :name, :createdAt, :lastUpdatedAt)"
        )
            .bind("id", wishlist.id)
            .bind("userId", wishlist.userId)
            .bind("name", wishlist.name)
            .bind("createdAt", wishlist.createdAt)
            .bind("lastUpdatedAt", wishlist.lastUpdatedAt)
            .await()
        insertProductVariantLinks(wishlist.id, productVariantIds)
    }

    /**
     * Replaces the variant set ([productVariantIds] distinct) and sets `lastUpdatedAt`, atomically and last writer wins
     * like the original's one `$set`: the UPDATE of the wishlist row comes first and locks it, so a concurrent update or
     * delete of the same wishlist waits and then sees this one's links; a wishlist deleted meanwhile is left alone.
     */
    @Transactional
    suspend fun updateProductVariants(id: UUID, productVariantIds: List<UUID>, lastUpdatedAt: OffsetDateTime) {
        val updated = db.sql("UPDATE WishlistEntity SET lastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", id)
            .fetch().awaitRowsUpdated()
        if (updated == 0L) {
            return
        }
        db.sql("DELETE FROM WishlistToProductVariantEntity WHERE wishlistId = :id").bind("id", id).await()
        insertProductVariantLinks(id, productVariantIds)
    }

    private suspend fun insertProductVariantLinks(wishlistId: UUID, productVariantIds: List<UUID>) {
        for (productVariantId in productVariantIds) {
            db.sql("INSERT INTO WishlistToProductVariantEntity (wishlistId, productVariantId) VALUES (:wishlistId, :productVariantId)")
                .bind("wishlistId", wishlistId)
                .bind("productVariantId", productVariantId)
                .await()
        }
    }

    /**
     * `Wishlist.productVariants`: the wishlist's variant ids by id (byte order = Rust `Uuid` order), [first] null = all.
     */
    fun productVariantsOfWishlist(wishlistId: UUID, first: Int?, skip: Int, ascending: Boolean): ConnectionQuery<UUID> =
        ConnectionQuery(
            db, "WishlistToProductVariantEntity", Condition.eq("t.wishlistId", wishlistId),
            listOf(if (ascending) "productVariantId ASC" else "productVariantId DESC"), first, skip,
            primaryKey = "productVariantId",
        ) { row, _ -> row.get("productvariantid", UUID::class.java)!! }

    suspend fun updateName(id: UUID, name: String, lastUpdatedAt: OffsetDateTime) {
        db.sql("UPDATE WishlistEntity SET name = :name, lastUpdatedAt = :lastUpdatedAt WHERE id = :id")
            .bind("name", name)
            .bind("lastUpdatedAt", lastUpdatedAt)
            .bind("id", id)
            .await()
    }

    /**
     * The wishlist's link rows, then the wishlist row, atomically (one `delete_one`); the wishlist row is locked first,
     * so a concurrent [updateProductVariants] cannot add links between the two DELETEs.
     */
    @Transactional
    suspend fun deleteWishlist(id: UUID) {
        db.sql("SELECT id FROM WishlistEntity WHERE id = :id FOR UPDATE").bind("id", id).map { _ -> 1 }.awaitOneOrNull()
        db.sql("DELETE FROM WishlistToProductVariantEntity WHERE wishlistId = :id").bind("id", id).await()
        db.sql("DELETE FROM WishlistEntity WHERE id = :id").bind("id", id).await()
    }

    /**
     * `User.wishlists`: `find({"user._id": userId})` with sort, skip and limit, and `count_documents`, as the three
     * statements of a connection.
     *
     * @param orderBy SQL ORDER BY items (fixed column names, see `WishlistOrderField`)
     */
    fun wishlistsOfUser(userId: UUID, orderBy: String, first: Int, skip: Int): ConnectionQuery<WishlistRow> =
        ConnectionQuery(db, "WishlistEntity", Condition.eq("t.userId", userId), listOf(orderBy), first, skip) { row, _ ->
            row.toWishlist()
        }

    private fun Readable.toWishlist() = WishlistRow(
        id = get("id", UUID::class.java)!!,
        userId = get("userid", UUID::class.java)!!,
        name = get("name", String::class.java)!!,
        createdAt = get("createdat", OffsetDateTime::class.java)!!.withOffsetSameInstant(ZoneOffset.UTC),
        lastUpdatedAt = get("lastupdatedat", OffsetDateTime::class.java)!!.withOffsetSameInstant(ZoneOffset.UTC),
    )
}
