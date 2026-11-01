package org.misarch.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class CatalogSchemaTest {

    /** A product in a category with one characteristic, a variant and a version; returns (version, characteristic). */
    private fun catalog(db: MigrationTestDatabase): Pair<UUID, UUID> {
        val product = UUID.randomUUID()
        val category = UUID.randomUUID()
        val characteristic = UUID.randomUUID()
        val version = UUID.randomUUID()
        db.execute(
            """
            INSERT INTO CategoryEntity (id, name, description) VALUES ('$category', 'c', 'd');
            INSERT INTO CategoryCharacteristicEntity (id, discriminator, name, description, categoryId)
                VALUES ('$characteristic', 'TextCategoryCharacteristic', 'n', 'd', '$category');
            INSERT INTO ProductEntity (id, internalName, isPubliclyVisible) VALUES ('$product', 'p', true);
            INSERT INTO ProductToCategoryEntity (productId, categoryId) VALUES ('$product', '$category');
            INSERT INTO ProductVariantEntity (id, isPubliclyVisible, productId) VALUES ('$product', true, '$product');
            INSERT INTO TaxRateEntity (id) VALUES ('$product');
            INSERT INTO ProductVariantVersionEntity (id, name, description, version, retailPrice, createdAt,
                productVariantId, taxRateId) VALUES ('$version', 'v', 'd', 1, 100, now(), '$product', '$product');
            """.trimIndent()
        )
        return version to characteristic
    }

    @Test
    fun `a characteristic value belongs to a category of the version's product, once`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val (version, characteristic) = catalog(db)
            val insert = "INSERT INTO CategoryCharacteristicValueEntity (discriminator, stringValue, categoryCharacteristicId, " +
                "productVariantVersionId) VALUES ('TextCategoryCharacteristicValue', 'x', '%s', '$version')"
            db.execute(insert.format(characteristic))
            assertThatThrownBy { db.execute(insert.format(characteristic)) }.hasMessageContaining("_key")
            val (_, otherCharacteristic) = catalog(db)
            assertThatThrownBy { db.execute(insert.format(otherCharacteristic)) }
                .hasMessageContaining("Invalid association between productVariantVersionId and categoryId")
        }
    }

    @Test
    fun `link tables have the pair as key, foreign keys are required`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val (version, _) = catalog(db)
            val media = UUID.randomUUID()
            db.execute("INSERT INTO MediaEntity (id) VALUES ('$media')")
            val link = "INSERT INTO ProductVariantVersionToMediaEntity (productVariantVersionId, mediaId) VALUES ('$version', '$media')"
            db.execute(link)
            assertThatThrownBy { db.execute(link) }.hasMessageContaining("productvariantversiontomediaentity_pkey")
            assertThatThrownBy {
                db.execute("INSERT INTO ProductVariantEntity (isPubliclyVisible) VALUES (true)")
            }.hasMessageContaining("productid")
            assertThatThrownBy {
                db.execute("UPDATE ProductEntity SET defaultVariantId = '${UUID.randomUUID()}'")
            }.hasMessageContaining("productentity_defaultvariantid_fkey")
        }
    }
}
