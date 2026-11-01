package org.misarch.review

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Constraints and generated columns of the schema, on a freshly migrated database.
 */
class ReviewSchemaTest {

    @Test
    fun `the database generates ids and the number of stars of a rating`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            insertReview(db, UUID.randomUUID(), UUID.randomUUID(), "ONE_STARS")
            insertReview(db, UUID.randomUUID(), UUID.randomUUID(), "FOUR_STARS")

            assertThat(db.query("SELECT rating, stars FROM ReviewEntity ORDER BY stars") {
                it.getString(1) to it.getInt(2)
            }).containsExactly("ONE_STARS" to 1, "FOUR_STARS" to 4)
        }
    }

    @Test
    fun `an unknown rating is rejected`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            assertThatThrownBy { insertReview(db, UUID.randomUUID(), UUID.randomUUID(), "SixStars") }
                .hasMessageContaining("reviewentity_rating_check")
        }
    }

    @Test
    fun `a user reviews a product variant once`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val user = UUID.randomUUID()
            val variant = UUID.randomUUID()
            insertReview(db, user, variant, "TWO_STARS")
            assertThatThrownBy { insertReview(db, user, variant, "FIVE_STARS") }
                .hasMessageContaining("reviewentity_userid_productvariantid_key")
        }
    }

    private fun insertReview(db: MigrationTestDatabase, user: UUID, variant: UUID, rating: String) {
        db.execute(
            "INSERT INTO ReviewEntity (userId, productVariantId, productId, body, rating, createdAt, lastUpdatedAt, " +
                "isVisible) VALUES ('$user', '$variant', '${UUID.randomUUID()}', 'b', '$rating', now(), now(), true)"
        )
    }
}
