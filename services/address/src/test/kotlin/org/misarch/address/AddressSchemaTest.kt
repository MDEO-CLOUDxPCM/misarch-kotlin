package org.misarch.address

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class AddressSchemaTest {

    private fun insert(db: MigrationTestDatabase, names: String) = db.query(
        "INSERT INTO AddressEntity (street1, city, postalCode, country, firstName, lastName) " +
            "VALUES ('s', 'c', 'p', 'DE', $names) RETURNING version"
    ) { it.getLong(1) }.single()

    @Test
    fun `the database generates ids and ascending versions`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val first = insert(db, "NULL, NULL")
            assertThat(insert(db, "'Ada', 'Lovelace'")).isGreaterThan(first)
        }
    }

    @Test
    fun `first and last name are both set or both missing`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            assertThatThrownBy { insert(db, "'Ada', NULL") }.hasMessageContaining("addressentity_firstname_lastname_check")
            assertThatThrownBy { insert(db, "NULL, 'Lovelace'") }.hasMessageContaining("addressentity_firstname_lastname_check")
        }
    }

    @Test
    fun `a user address needs a known user`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            assertThatThrownBy {
                db.execute(
                    "INSERT INTO AddressEntity (street1, city, postalCode, country, userId) " +
                        "VALUES ('s', 'c', 'p', 'DE', '${UUID.randomUUID()}')"
                )
            }.hasMessageContaining("addressentity_userid_fkey")
        }
    }
}
