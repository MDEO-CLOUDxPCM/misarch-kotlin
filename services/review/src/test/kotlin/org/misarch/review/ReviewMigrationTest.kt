package org.misarch.review

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V3 migration applied to rows written by the V2 schema.
 */
class ReviewMigrationTest {

    @Test
    fun `tables are renamed and the ratings become enum names with their stars`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "2")
            val one = UUID.randomUUID()
            val four = UUID.randomUUID()
            insertReview(db, one, "OneStars")
            insertReview(db, four, "FourStars")
            db.execute("INSERT INTO product_variants VALUES ('$one', '$four')")
            db.migrate()

            assertThat(db.query("SELECT id, rating, stars, isVisible FROM ReviewEntity ORDER BY stars") {
                listOf(it.getObject(1), it.getString(2), it.getInt(3), it.getBoolean(4))
            }).containsExactly(listOf(one, "ONE_STARS", 1, true), listOf(four, "FOUR_STARS", 4, true))
            assertThat(db.query("SELECT productId FROM ProductVariantEntity WHERE id = '$one'") { it.getObject(1) })
                .containsExactly(four)
        }
    }

    @Test
    fun `an unknown rating stops the migration`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "2")
            insertReview(db, UUID.randomUUID(), "SixStars")
            assertThatThrownBy { db.migrate() }.hasMessageContaining("reviewentity_rating_check")
        }
    }

    private fun insertReview(db: MigrationTestDatabase, id: UUID, rating: String) {
        db.execute(
            "INSERT INTO reviews VALUES ('$id', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', " +
                "'b', '$rating', now(), now(), true)"
        )
    }
}
