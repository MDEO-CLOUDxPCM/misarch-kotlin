package org.misarch.invoice

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
class InvoiceMigrationTest {

    @Test
    fun `V3 keeps the invoices, their address ids and the replicas`() {
        val schema = "invoice_migration_" + UUID.randomUUID().toString().replace("-", "").take(8)
        fun flyway(target: String) = Flyway.configure()
            .dataSource(PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password)
            .schemas(schema).locations("classpath:db/migration").target(target).load()
        flyway("2").migrate()
        val (invoice, order, userAddress, vendorAddress) = List(4) { UUID.randomUUID() }
        val user = UUID.randomUUID()
        DriverManager.getConnection(
            PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password
        ).use { connection ->
            fun execute(sql: String) = connection.createStatement().use { it.execute("SET search_path TO $schema"); it.execute(sql) }
            fun <T> query(sql: String, read: (ResultSet) -> T): List<T> = connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
            execute(
                "INSERT INTO invoices (id, order_id, issued_at, content, user_address, vendor_address, vat_number) VALUES " +
                    "('$invoice', '$order', now(), 'c', jsonb_build_object('_id', '$userAddress', 'street1', 's'), " +
                    "jsonb_build_object('_id', '$vendorAddress'), 'AB1234')"
            )
            execute("INSERT INTO users (id, first_name, last_name) VALUES ('$user', 'Ada', 'Lovelace')")
            execute(
                "INSERT INTO user_address (id, user_id, street1, street2, city, postal_code, country, archived) " +
                    "VALUES ('$userAddress', '$user', 's1', 's2', 'c', 'p', 'DE', true)"
            )
            execute("INSERT INTO vendor_address (id, postal_code) VALUES ('$vendorAddress', 'p')")
            flyway("latest").migrate()
            assertThat(query("SELECT orderId, userAddressId, vendorAddressId, vatNumber FROM InvoiceEntity WHERE id = '$invoice'") {
                listOf(it.getObject(1), it.getObject(2), it.getObject(3), it.getString(4))
            }).containsExactly(listOf(order, userAddress, vendorAddress, "AB1234"))
            assertThat(query("SELECT firstName, lastName FROM UserEntity WHERE id = '$user'") { it.getString(1) + " " + it.getString(2) })
                .containsExactly("Ada Lovelace")
            assertThat(query("SELECT userId, postalCode, isArchived FROM UserAddressEntity WHERE id = '$userAddress'") {
                listOf(it.getObject(1), it.getString(2), it.getBoolean(3))
            }).containsExactly(listOf(user, "p", true))
            assertThat(query("SELECT postalCode, seq FROM VendorAddressEntity WHERE id = '$vendorAddress'") { it.getString(1) })
                .containsExactly("p")
            execute("DROP SCHEMA $schema CASCADE")
        }
    }

}
