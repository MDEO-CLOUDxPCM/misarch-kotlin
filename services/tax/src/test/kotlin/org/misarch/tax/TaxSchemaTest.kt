package org.misarch.tax

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class TaxSchemaTest {

    @Test
    fun `a tax rate points to one of its versions`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val rate = db.query("INSERT INTO TaxRateEntity (name, description) VALUES ('VAT', 'd') RETURNING id") { it.getObject(1) }.single()
            val version = db.query(
                "INSERT INTO TaxRateVersionEntity (rate, createdAt, version, taxRateId) VALUES (0.19, now(), 1, '$rate') RETURNING id"
            ) { it.getObject(1) }.single()
            db.execute("UPDATE TaxRateEntity SET currentVersionId = '$version' WHERE id = '$rate'")
            assertThatThrownBy {
                db.execute("UPDATE TaxRateEntity SET currentVersionId = '${UUID.randomUUID()}' WHERE id = '$rate'")
            }.hasMessageContaining("taxrateentity_currentversionid_fkey")
            assertThat(db.query("SELECT currentVersionId FROM TaxRateEntity") { it.getObject(1) }).containsExactly(version)
        }
    }
}
