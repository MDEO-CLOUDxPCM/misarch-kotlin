package org.misarch.simulation.connector

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.Fixture
import java.io.IOException

/** Callback retries (`RETRY_COUNT`, 5 s pause) with virtual time (test plan 6). */
class ConnectorServiceTest {

    private fun TestScope.fixture(properties: ConnectorConfigurationProperties = Fixture.DEFAULT_CONNECTOR) =
        Fixture(backgroundScope, testScheduler, properties)

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `failing callee - 4 attempts at 0, 5, 10, 15 s by default, then gives up`() = runTest {
        val f = fixture()
        f.transport.status = { 500 }
        f.connector.sendUpdateToPayment("p", "SUCCEEDED")
        advance(60_000)
        assertThat(f.transport.calls.map { it.time }).containsExactly(0L, 5000L, 10000L, 15000L)
        assertThat(f.transport.calls).allMatch { it.uri == "http://payment:8080/payment/update-payment-status" }
    }

    @Test
    fun `network errors count as failures, a later 2xx stops`() = runTest {
        val f = fixture()
        f.transport.status = { attempt -> if (attempt == 1) throw IOException("connection refused") else 204 }
        f.connector.sendUpdateToShipment("s", "DELIVERED")
        advance(60_000)
        assertThat(f.transport.calls.map { it.time }).containsExactly(0L, 5000L)
    }

    @Test
    fun `RETRY_COUNT retries after the first attempt`() = runTest {
        mapOf(0 to 1, 2 to 3, -1 to 1).forEach { (retryCount, attempts) ->
            val f = fixture(Fixture.DEFAULT_CONNECTOR.copy(retryCount = retryCount))
            f.transport.status = { 503 }
            f.connector.sendUpdateToPayment("p", "FAILED")
            advance(60_000)
            assertThat(f.transport.calls).describedAs("RETRY_COUNT='$retryCount'").hasSize(attempts)
        }
    }

    @Test
    fun `missing URLs resolve against http localhost`() = runTest {
        val f = fixture(ConnectorConfigurationProperties())
        f.connector.sendUpdateToPayment("p", "SUCCEEDED")
        f.connector.sendUpdateToShipment("s", "FAILED")
        advance(1)
        assertThat(f.transport.calls.map { it.uri }).containsExactly(
            "http://localhost/NOT_SET/payment/update-payment-status",
            "http://localhost/NOT_SET/shipment/s/status",
        )
    }
}
