package org.misarch.simulation

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.config.SimulationConfigurationProperties
import java.io.IOException
import java.net.URI

/** Callback retries (`RETRY_COUNT`, 5 s pause) with virtual time (test plan 6). */
class CallbackServiceTest {

    private fun TestScope.fixture(properties: SimulationConfigurationProperties = Fixture.DEFAULT_PROPERTIES) =
        Fixture(backgroundScope, testScheduler, properties)

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `failing callee - 4 attempts at 0, 5, 10, 15 s by default, then gives up`() = runTest {
        val f = fixture()
        f.callbacks.status = { 500 }
        f.callbackService.sendPaymentStatus("p", "SUCCEEDED")
        advance(60_000)
        assertThat(f.callbacks.calls.map { it.time }).containsExactly(0L, 5000L, 10000L, 15000L)
        assertThat(f.callbacks.calls).allMatch {
            it.uri == "http://payment:8080/payment/update-payment-status" && it.contentType == "application/json" &&
                it.body == """{"paymentId":"p","status":"SUCCEEDED"}"""
        }
    }

    @Test
    fun `network errors and non-2xx answers count as failures, a later 2xx stops`() = runTest {
        val f = fixture()
        f.callbacks.status = { attempt ->
            when (attempt) {
                1 -> throw IOException("connection refused")
                2 -> 302
                else -> 204
            }
        }
        f.callbackService.sendShipmentStatus("s", "DELIVERED")
        advance(60_000)
        assertThat(f.callbacks.calls.map { it.time }).containsExactly(0L, 5000L, 10000L)
        assertThat(f.callbacks.calls.map { it.uri to it.body }).allMatch {
            it == ("http://shipment:8080/shipment/s/status" to """{"status":"DELIVERED"}""")
        }
    }

    @Test
    fun `RETRY_COUNT retries after the first attempt`() = runTest {
        mapOf(0 to 1, 2 to 3, -1 to 1).forEach { (retryCount, attempts) ->
            val f = fixture(Fixture.DEFAULT_PROPERTIES.copy(retryCount = retryCount))
            f.callbacks.status = { 503 }
            f.callbackService.sendPaymentStatus("p", "FAILED")
            advance(60_000)
            assertThat(f.callbacks.calls).describedAs("RETRY_COUNT='$retryCount'").hasSize(attempts)
        }
    }

    @Test
    fun `base URLs may have a path, ids are encoded`() = runTest {
        val f = fixture(
            Fixture.DEFAULT_PROPERTIES.copy(paymentUrl = URI("http://gw/pay"), shipmentUrl = URI("http://gw/ship"))
        )
        f.callbackService.sendPaymentStatus("p", "SUCCEEDED")
        f.callbackService.sendShipmentStatus("a b", "FAILED")
        advance(1)
        assertThat(f.callbacks.calls.map { it.uri }).containsExactly(
            "http://gw/pay/payment/update-payment-status",
            "http://gw/ship/shipment/a%20b/status",
        )
    }
}
