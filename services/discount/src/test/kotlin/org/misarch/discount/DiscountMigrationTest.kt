package org.misarch.discount

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V2+ migrations applied to rows written by the V1 schema.
 */
class DiscountMigrationTest {

    @Test
    fun `link tables keep their rows and get the pair as primary key`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "1.3")
            val user = UUID.randomUUID()
            val product = UUID.randomUUID()
            val variant = UUID.randomUUID()
            val category = UUID.randomUUID()
            val discount = UUID.randomUUID()
            val coupon = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO UserEntity (id) VALUES ('$user');
                INSERT INTO ProductEntity (id) VALUES ('$product');
                INSERT INTO ProductVariantEntity (id, productId) VALUES ('$variant', '$product');
                INSERT INTO CategoryEntity (id) VALUES ('$category');
                INSERT INTO ProductToCategoryEntity (id, productId, categoryId) VALUES (gen_random_uuid(), '$product', '$category');
                INSERT INTO DiscountEntity (id, discount, validFrom, validUntil) VALUES ('$discount', 0.1, now(), now());
                INSERT INTO CouponEntity (id, validFrom, validUntil, code, discountId) VALUES ('$coupon', now(), now(), 'C', '$discount');
                INSERT INTO CouponRedemptionEntity (id, couponId, userId) VALUES (gen_random_uuid(), '$coupon', '$user');
                INSERT INTO DiscountToProductEntity (id, discountId, productId) VALUES (gen_random_uuid(), '$discount', '$product');
                INSERT INTO DiscountToCategoryEntity (id, discountId, categoryId) VALUES (gen_random_uuid(), '$discount', '$category');
                INSERT INTO DiscountToProductVariantEntity (id, discountId, productVariantId) VALUES (gen_random_uuid(), '$discount', '$variant');
                """.trimIndent()
            )
            db.migrate()

            val expected = mapOf(
                "producttocategoryentity" to listOf("productid", "categoryid"),
                "couponredemptionentity" to listOf("couponid", "userid"),
                "discounttoproductentity" to listOf("discountid", "productid"),
                "discounttocategoryentity" to listOf("discountid", "categoryid"),
                "discounttoproductvariantentity" to listOf("discountid", "productvariantid"),
            )
            for ((table, columns) in expected) {
                assertThat(db.query("SELECT COUNT(*) FROM $table") { it.getInt(1) }).describedAs(table).containsExactly(1)
                assertThat(db.query(PRIMARY_KEY_COLUMNS.format(table)) { it.getString(1) }).describedAs(table)
                    .containsExactlyElementsOf(columns)
                assertThat(db.query("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = '$table' AND column_name = 'id'") {
                    it.getInt(1)
                }).describedAs(table).containsExactly(0)
            }
            // the redemption trigger still counts usages
            assertThat(db.query("SELECT usages FROM CouponEntity") { it.getInt(1) }).containsExactly(1)
        }
    }

    companion object {
        const val PRIMARY_KEY_COLUMNS = """
            SELECT a.attname FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
            WHERE i.indrelid = '%s'::regclass AND i.indisprimary
            ORDER BY array_position(i.indkey::int2[], a.attnum)
        """
    }
}
