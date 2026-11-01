package org.misarch.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V2+ migrations applied to rows written by the V1 schema.
 */
class CatalogMigrationTest {

    @Test
    fun `link tables keep their rows and get the pair as primary key`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "1.6")
            val product = UUID.randomUUID()
            val category = UUID.randomUUID()
            val version = UUID.randomUUID()
            val media = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO CategoryEntity (id, name, description) VALUES ('$category', 'c', 'd');
                INSERT INTO ProductEntity (id, internalName, isPubliclyVisible) VALUES ('$product', 'p', true);
                INSERT INTO ProductVariantEntity (id, isPubliclyVisible, productId) VALUES ('$product', true, '$product');
                INSERT INTO TaxRateEntity (id) VALUES ('$product');
                INSERT INTO ProductVariantVersionEntity (id, name, description, version, retailPrice, createdAt,
                    productVariantId, taxRateId) VALUES ('$version', 'v', 'd', 1, 100, now(), '$product', '$product');
                INSERT INTO MediaEntity (id) VALUES ('$media');
                INSERT INTO ProductToCategoryEntity (id, productId, categoryId) VALUES (gen_random_uuid(), '$product', '$category');
                INSERT INTO ProductVariantVersionToMediaEntity (id, productVariantVersionId, mediaId)
                    VALUES (gen_random_uuid(), '$version', '$media');
                """.trimIndent()
            )
            db.migrate()

            assertThat(db.query("SELECT productId, categoryId FROM ProductToCategoryEntity") {
                it.getObject(1) to it.getObject(2)
            }).containsExactly(product to category)
            assertThat(db.query("SELECT mediaId FROM ProductVariantVersionToMediaEntity") { it.getObject(1) })
                .containsExactly(media)
            assertThat(db.query(PRIMARY_KEY_COLUMNS.format("producttocategoryentity")) { it.getString(1) })
                .containsExactly("productid", "categoryid")
            assertThat(db.query(PRIMARY_KEY_COLUMNS.format("productvariantversiontomediaentity")) { it.getString(1) })
                .containsExactly("productvariantversionid", "mediaid")
            // `id UUID PRIMARY KEY UNIQUE` creates only one index (Postgres drops the redundant UNIQUE)
            assertThat(db.query("SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'productentity'") { it.getInt(1) })
                .containsExactly(1)
            assertThatThrownBy {
                db.execute("INSERT INTO ProductVariantEntity (id, isPubliclyVisible) VALUES (gen_random_uuid(), true)")
            }.hasMessageContaining("productid")
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
