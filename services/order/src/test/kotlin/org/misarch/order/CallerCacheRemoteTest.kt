package org.misarch.order

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.event.DaprProperties
import org.misarch.common.event.DaprServiceInvoker
import org.misarch.common.mechanisms.CallerCache
import org.misarch.order.client.CheckoutClient
import org.misarch.order.client.model.CalculateShipmentFeesInput
import org.misarch.order.client.model.FindApplicableDiscountsInput
import org.misarch.order.client.model.ProductVariantVersionWithQuantityAndShipmentMethodInput
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The caller-side cache in front of createOrder's invocations (docs/MECHANISMS.md), in `DaprServiceInvoker`. */
class CallerCacheRemoteTest {

    private val invoked = CopyOnWriteArrayList<String>()

    private fun client(cache: CallerCache?, answer: (String) -> String): CheckoutClient {
        val webClient = WebClient.builder().exchangeFunction { request ->
            invoked += request.url().path
            Mono.just(
                ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(answer(request.url().path)).build()
            )
        }.build()
        val mapper = JsonMapper.builder().build()
        return CheckoutClient(DaprServiceInvoker(webClient, DaprProperties(httpEndpoint = "http://sidecar"), mapper, cache), mapper)
    }

    private fun fees(quantity: Long) = CalculateShipmentFeesInput(
        listOf(ProductVariantVersionWithQuantityAndShipmentMethodInput(UUID(0, 1), quantity, UUID(0, 2)))
    )

    @Test
    fun `a hit skips the invocation, only covered targets and answers with data are cached`(): Unit = runBlocking {
        val cache = CallerCache(setOf("shipment"), 60_000, 100)
        var fees = 100
        val client = client(cache) { path ->
            if (path.contains("discount")) """{"data":{"findApplicableDiscounts":[]}}"""
            else """{"data":{"calculateShipmentFees":${fees++}}}"""
        }
        assertThat(client.shipmentFees(fees(1))).isEqualTo(100)
        assertThat(client.shipmentFees(fees(1))).isEqualTo(100)   // hit
        assertThat(client.shipmentFees(fees(2))).isEqualTo(101)   // other body
        repeat(2) { client.applicableDiscounts(FindApplicableDiscountsInput(UUID(0, 3), emptyList(), 10)) } // not covered
        assertThat(invoked).containsExactly(
            "/v1.0/invoke/shipment/method/graphql", "/v1.0/invoke/shipment/method/graphql",
            "/v1.0/invoke/discount/method/graphql", "/v1.0/invoke/discount/method/graphql",
        )
        assertThat(cache.counts()["hits"]).isEqualTo(1L)
        assertThat(cache.counts()["misses"]).isEqualTo(2L)
    }

    @Test
    fun `answers with errors or without data are not cached, and the error stays the uncached one`(): Unit = runBlocking {
        val cache = CallerCache(setOf("shipment"), 60_000, 100)
        val client = client(cache) { """{"data":null,"errors":[{"message":"x"}]}""" }
        repeat(2) {
            val e = runCatching { client.shipmentFees(fees(1)) }.exceptionOrNull()
            assertThat(e).isInstanceOf(IllegalStateException::class.java)
                .hasMessage("The shipment service answered without data: [{\"message\":\"x\"}]")
        }
        assertThat(invoked).hasSize(2)
    }

    @Test
    fun `without the cache every call is invoked`(): Unit = runBlocking {
        val client = client(null) { """{"data":{"calculateShipmentFees":1}}""" }
        repeat(2) { client.shipmentFees(fees(1)) }
        assertThat(invoked).hasSize(2)
    }
}
