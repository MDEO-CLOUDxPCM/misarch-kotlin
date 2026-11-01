package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * The migrations keep rows written by earlier schema versions (in a schema of its own, migrated step by step).
 */
class OrderMigrationTest {

    /** V2 turns the id arrays of V1 into rows, V4 renames the tables and columns (data of earlier versions). */
    @Test
    fun `migrations keep the data of earlier versions`() {
        val schema = "order_migration_" + UUID.randomUUID().toString().replace("-", "").take(8)
        fun flyway(target: String) = Flyway.configure()
            .dataSource(PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password)
            .schemas(schema).locations("classpath:db/migration").target(target).load()
        flyway("1").migrate()
        val (user, a1, a2, compensation, i1, i2) = List(6) { UUID.randomUUID() }
        val order = UUID.randomUUID()
        DriverManager.getConnection(
            PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password
        ).use { connection ->
            fun <T> query(sql: String, read: (ResultSet) -> T): List<T> = connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
            connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                s.execute("INSERT INTO users (id, user_address_ids) VALUES ('$user', ARRAY['$a1', '$a2', '$a1']::uuid[])")
                s.execute(
                    "INSERT INTO orders (id, user_id, created_at, order_status, shipment_address_id, invoice_address_id, " +
                        "compensatable_order_amount, payment_information_id) VALUES ('$order', '$user', now(), 'PENDING', '$a1', '$a1', 7, '$a2')"
                )
                s.execute(
                    "INSERT INTO order_compensations (id, order_id, order_item_ids, triggered_at, amount_to_compensate) " +
                        "VALUES ('$compensation', '$order', ARRAY['$i2', '$i1']::uuid[], now(), 5)"
                )
            }
            flyway("latest").migrate()
            assertThat(query("SELECT id FROM UserAddressEntity WHERE userId = '$user'") { it.getObject(1, UUID::class.java) })
                .containsExactlyInAnyOrder(a1, a2)
            assertThat(
                query("SELECT orderItemId FROM OrderCompensationToOrderItemEntity WHERE orderCompensationId = '$compensation' ORDER BY position") {
                    it.getObject(1, UUID::class.java)
                }
            ).containsExactly(i2, i1)
            assertThat(query("SELECT userId, compensatableOrderAmount, orderStatus FROM OrderEntity WHERE id = '$order'") {
                listOf(it.getObject(1, UUID::class.java), it.getLong(2), it.getString(3))
            }).containsExactly(listOf(user, 7L, "PENDING"))
            connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

}

private operator fun <T> List<T>.component6(): T = this[5]
