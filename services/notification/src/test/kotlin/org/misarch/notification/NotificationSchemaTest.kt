package org.misarch.notification

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class NotificationSchemaTest {

    @Test
    fun `the database generates ids and a notification needs a known user`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val user = UUID.randomUUID()
            db.execute("INSERT INTO UserEntity (id) VALUES ('$user')")
            db.execute("INSERT INTO NotificationEntity (dateSent, title, body, userId) VALUES (now(), 't', 'b', '$user')")
            assertThat(db.query("SELECT COUNT(*) FROM NotificationEntity WHERE userId = '$user'") { it.getInt(1) }).containsExactly(1)
            assertThatThrownBy {
                db.execute("INSERT INTO NotificationEntity (dateSent, title, body, userId) VALUES (now(), 't', 'b', '${UUID.randomUUID()}')")
            }.hasMessageContaining("notificationentity_userid_fkey")
        }
    }
}
