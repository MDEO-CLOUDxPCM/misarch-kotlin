package org.misarch.discount

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class DiscountSchemaTest {

    @Test
    fun `a redemption counts the coupon's usages up to maxUsages`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val discount = UUID.randomUUID()
            val coupon = UUID.randomUUID()
            val users = List(2) { UUID.randomUUID() }
            db.execute(
                """
                INSERT INTO UserEntity (id) VALUES ('${users[0]}'), ('${users[1]}');
                INSERT INTO DiscountEntity (id, discount, validFrom, validUntil) VALUES ('$discount', 0.1, now(), now());
                INSERT INTO CouponEntity (id, maxUsages, validFrom, validUntil, code, discountId)
                    VALUES ('$coupon', 1, now(), now(), 'C', '$discount');
                INSERT INTO CouponRedemptionEntity (couponId, userId) VALUES ('$coupon', '${users[0]}');
                """.trimIndent()
            )
            assertThat(db.query("SELECT usages FROM CouponEntity") { it.getInt(1) }).containsExactly(1)
            assertThatThrownBy {
                db.execute("INSERT INTO CouponRedemptionEntity (couponId, userId) VALUES ('$coupon', '${users[1]}')")
            }.hasMessageContaining("Coupon has been used too often")
            assertThatThrownBy {
                db.execute("INSERT INTO CouponEntity (validFrom, validUntil, code, discountId) VALUES (now(), now(), 'C', '$discount')")
            }.hasMessageContaining("couponentity_code_key")
        }
    }

    @Test
    fun `a user's usages of a discount stay within maxUsagesPerUser`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val discount = UUID.randomUUID()
            val user = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO UserEntity (id) VALUES ('$user');
                INSERT INTO DiscountEntity (id, discount, maxUsagesPerUser, validFrom, validUntil) VALUES ('$discount', 0.1, 2, now(), now());
                INSERT INTO DiscountUsageEntity (discountId, userId, usages) VALUES ('$discount', '$user', 2);
                """.trimIndent()
            )
            assertThatThrownBy { db.execute("UPDATE DiscountUsageEntity SET usages = 3") }
                .hasMessageContaining("The user has applied the discount too often")
            assertThatThrownBy {
                db.execute("INSERT INTO DiscountUsageEntity (discountId, userId) VALUES ('$discount', '$user')")
            }.hasMessageContaining("discountusageentity_discountid_userid_key")
        }
    }
}
