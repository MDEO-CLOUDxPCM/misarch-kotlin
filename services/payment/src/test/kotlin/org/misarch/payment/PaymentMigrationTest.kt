package org.misarch.payment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import java.sql.DriverManager
import java.util.UUID

/**
 * V3 (UUID ids) applied to rows written with the V2 schema, each case in its own database.
 */
class PaymentMigrationTest {

    private fun database(): String {
        val name = "payment_migration_" + UUID.randomUUID().toString().replace("-", "")
        connection(PostgresTestDatabase.jdbcUrl).use { it.createStatement().execute("CREATE DATABASE $name") }
        return PostgresTestDatabase.jdbcUrl.replace("/misarch", "/$name")
    }

    private fun connection(url: String) = DriverManager.getConnection(url, "postgres", "postgres")

    private fun flyway(url: String, target: String) = Flyway.configure()
        .dataSource(url, "postgres", "postgres")
        .locations("classpath:db/migration")
        .target(target)
        .load()

    @Test
    fun `V3 converts the ids and keeps payments, informations and open orders`() {
        val url = database()
        flyway(url, "2").migrate()
        val user = UUID.randomUUID()
        val info = UUID.randomUUID()
        val payment = UUID.randomUUID()
        val order = UUID.randomUUID()
        connection(url).use {
            it.createStatement().execute(
                """
                INSERT INTO PaymentInformationEntity (id, paymentMethod, publicMethodDetails, userId)
                    VALUES ('$info', 'CREDIT_CARD', '{"cardHolder":"A","cardNumber":"****1111"}', '$user');
                INSERT INTO PaymentEntity (id, totalAmount, status, paymentInformation, numberOfRetries, createdAt, updatedAt)
                    VALUES ('$payment', 1999, 'PENDING', '$info', 2, now(), now());
                INSERT INTO OpenOrderEntity (paymentId, orderId, orderJson) VALUES ('$payment', '$order', '{"id":"$order","b":1,"a":2}');
                """.trimIndent()
            )
        }
        flyway(url, "latest").migrate()
        connection(url).use {
            val payments = it.createStatement().executeQuery(
                "SELECT id, paymentInformationId, numberOfRetries, totalAmount FROM PaymentEntity"
            )
            assertThat(payments.next()).isTrue()
            assertThat(payments.getObject(1)).isEqualTo(payment)
            assertThat(payments.getObject(2)).isEqualTo(info)
            assertThat(payments.getInt(3)).isEqualTo(2)
            assertThat(payments.getDouble(4)).isEqualTo(1999.0)
            val infos = it.createStatement().executeQuery("SELECT userId, publicMethodDetails::text FROM PaymentInformationEntity")
            assertThat(infos.next()).isTrue()
            assertThat(infos.getObject(1)).isEqualTo(user)
            assertThat(infos.getString(2)).isEqualTo("""{"cardHolder":"A","cardNumber":"****1111"}""")
            val orders = it.createStatement().executeQuery("SELECT paymentId, orderId, orderJson::text FROM OpenOrderEntity")
            assertThat(orders.next()).isTrue()
            assertThat(orders.getObject(1)).isEqualTo(payment)
            assertThat(orders.getObject(2)).isEqualTo(order)
            assertThat(orders.getString(3)).isEqualTo("""{"id":"$order","b":1,"a":2}""")
        }
    }

    @Test
    fun `V3 fails on ids that are not UUIDs`() {
        val url = database()
        flyway(url, "2").migrate()
        connection(url).use {
            it.createStatement().execute(
                "INSERT INTO PaymentInformationEntity (id, paymentMethod, userId) VALUES ('info-1', 'INVOICE', '${UUID.randomUUID()}')"
            )
        }
        assertThatThrownBy { flyway(url, "latest").migrate() }
            .hasMessageContaining("PaymentInformationEntity contains ids that are not UUIDs")
    }
}
