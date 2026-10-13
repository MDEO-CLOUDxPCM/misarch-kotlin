package org.misarch.review.persistence

import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A row of `reviews` (the Mongo `reviews` document): [rating] is the stored token (see Rating), which may be
 * undeserializable (`FourStarst`).
 */
data class ReviewRow(
    val id: UUID,
    val userId: UUID,
    val productVariantId: UUID,
    val productVariantProductId: UUID,
    val body: String,
    val rating: String,
    val createdAt: Instant,
    val lastUpdatedAt: Instant,
    val isVisible: Boolean,
)

/** SQL access to the tables of docs/specs/review.md §7 (the former Mongo collections). */
@Component
class ReviewStore(private val db: DatabaseClient) {

    // --- replicas ---

    suspend fun insertUser(id: UUID) {
        db.sql("INSERT INTO users (id) VALUES (:id)").bind("id", id).fetch().awaitRowsUpdated()
    }

    suspend fun insertProduct(id: UUID) {
        db.sql("INSERT INTO products (id) VALUES (:id)").bind("id", id).fetch().awaitRowsUpdated()
    }

    suspend fun insertProductVariant(id: UUID, productId: UUID) {
        db.sql("INSERT INTO product_variants (id, product_id) VALUES (:id, :productId)")
            .bind("id", id).bind("productId", productId).fetch().awaitRowsUpdated()
    }

    suspend fun userExists(id: UUID): Boolean = exists("users", id)

    suspend fun productExists(id: UUID): Boolean = exists("products", id)

    suspend fun productVariantExists(id: UUID): Boolean = exists("product_variants", id)

    /** The product id of a replicated product variant, or null. */
    suspend fun findProductVariantProductId(id: UUID): UUID? =
        db.sql("SELECT product_id FROM product_variants WHERE id = :id").bind("id", id)
            .map { row -> row.get("product_id", UUID::class.java)!! }.awaitOneOrNull()

    private suspend fun exists(table: String, id: UUID): Boolean =
        db.sql("SELECT 1 FROM $table WHERE id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    // --- reviews ---

    suspend fun findReview(id: UUID): ReviewRow? =
        db.sql("SELECT * FROM reviews WHERE id = :id").bind("id", id).map(::toRow).awaitOneOrNull()

    /** First review of the user for the product variant (Mongo `find_one`), or null. */
    suspend fun findReviewOfUser(userId: UUID, productVariantId: UUID): ReviewRow? =
        db.sql("SELECT * FROM reviews WHERE product_variant_id = :pv AND user_id = :user LIMIT 1")
            .bind("pv", productVariantId).bind("user", userId).map(::toRow).awaitOneOrNull()

    suspend fun insertReview(row: ReviewRow) {
        db.sql(
            "INSERT INTO reviews (id, user_id, product_variant_id, product_variant_product_id, body, rating, " +
                "created_at, last_updated_at, is_visible) VALUES (:id, :userId, :pvId, :pvProductId, :body, :rating, " +
                ":createdAt, :lastUpdatedAt, :isVisible)"
        )
            .bind("id", row.id).bind("userId", row.userId).bind("pvId", row.productVariantId)
            .bind("pvProductId", row.productVariantProductId).bind("body", row.body).bind("rating", row.rating)
            .bind("createdAt", row.createdAt.atOffset(ZoneOffset.UTC))
            .bind("lastUpdatedAt", row.lastUpdatedAt.atOffset(ZoneOffset.UTC))
            .bind("isVisible", row.isVisible)
            .fetch().awaitRowsUpdated()
    }

    /** `$set: {<column>: value, last_updated_at: now}` (one Mongo `update_one`). */
    suspend fun updateReviewColumn(id: UUID, column: String, value: Any, now: Instant) {
        require(column in setOf("body", "rating", "is_visible"))
        db.sql("UPDATE reviews SET $column = :value, last_updated_at = :now WHERE id = :id")
            .bind("value", value).bind("now", now.atOffset(ZoneOffset.UTC)).bind("id", id)
            .fetch().awaitRowsUpdated()
    }

    suspend fun deleteReview(id: UUID) {
        db.sql("DELETE FROM reviews WHERE id = :id").bind("id", id).fetch().awaitRowsUpdated()
    }

    /** `count_documents(filter)`; [where] is a fixed SQL predicate on `:filter` (or null = all). */
    suspend fun countReviews(where: String?, filter: UUID?): Long {
        val spec = db.sql("SELECT COUNT(*) AS c FROM reviews" + (where?.let { " WHERE $it" } ?: ""))
        return (if (filter != null) spec.bind("filter", filter) else spec)
            .map { row -> row.get("c", java.lang.Long::class.java)!!.toLong() }.awaitOne()
    }

    /** `find(filter, sort, skip, limit)`; [orderBy] comes from ReviewOrderField (fixed strings). */
    suspend fun findReviews(where: String?, filter: UUID?, orderBy: String, offset: Long?, limit: Long): List<ReviewRow> {
        val sql = "SELECT * FROM reviews" + (where?.let { " WHERE $it" } ?: "") + " ORDER BY $orderBy LIMIT :limit" +
            (if (offset != null) " OFFSET :offset" else "")
        var spec = db.sql(sql).bind("limit", limit)
        if (filter != null) spec = spec.bind("filter", filter)
        if (offset != null) spec = spec.bind("offset", offset)
        return spec.map(::toRow).flow().toList()
    }

    private fun toRow(row: Readable): ReviewRow = ReviewRow(
        id = row.get("id", UUID::class.java)!!,
        userId = row.get("user_id", UUID::class.java)!!,
        productVariantId = row.get("product_variant_id", UUID::class.java)!!,
        productVariantProductId = row.get("product_variant_product_id", UUID::class.java)!!,
        body = row.get("body", String::class.java)!!,
        rating = row.get("rating", String::class.java)!!,
        createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
        lastUpdatedAt = row.get("last_updated_at", OffsetDateTime::class.java)!!.toInstant(),
        isVisible = row.get("is_visible", java.lang.Boolean::class.java)!!.booleanValue(),
    )
}
