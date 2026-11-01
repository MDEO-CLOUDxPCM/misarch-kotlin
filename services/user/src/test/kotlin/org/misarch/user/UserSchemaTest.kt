package org.misarch.user

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class UserSchemaTest {

    @Test
    fun `the database generates ids and a user needs a name`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val id = db.query(
                "INSERT INTO UserEntity (username, firstName, lastName, dateJoined) VALUES ('ada', 'Ada', 'Lovelace', now()) RETURNING id"
            ) { it.getObject(1) }.single()
            assertThat(id).isInstanceOf(UUID::class.java)
            assertThatThrownBy {
                db.execute("INSERT INTO UserEntity (username, firstName, dateJoined) VALUES ('ada', 'Ada', now())")
            }.hasMessageContaining("lastname")
        }
    }
}
