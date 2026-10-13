package org.misarch.order.remote

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.common.event.DaprConfiguration
import org.misarch.common.event.DaprProperties
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.order.auth.AuthorizedUserHeader
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.common.rust.RustUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A discount as returned by `findApplicableDiscounts`. */
data class ApplicableDiscount(val id: UUID, val discount: Double)

/** One `FindApplicableDiscountsProductVariantInput`. */
data class DiscountRequestItem(val productVariantId: UUID, val count: Long, val couponIds: Collection<UUID>)

/** One `ProductVariantVersionWithQuantityAndShipmentMethodInput`. */
data class ShipmentFeeRequestItem(val productVariantVersionId: UUID, val quantity: Long, val shipmentMethodId: UUID)

/**
 * The 4 synchronous Dapr service invocations of `createOrder` (S20-S23), reproducing the original's `graphql_client` +
 * `reqwest` calls: `POST` with body `{"variables", "query" (verbatim .graphql file), "operationName"}`, response body
 * parsed whatever the HTTP status, `data` absent/null -> "... is empty", body not matching the expected shape ->
 * `error decoding response body`, transport failure -> `error sending request for url (<url>)`.
 */
@Component
class RemoteServices(
    @Qualifier(DaprConfiguration.DAPR_WEB_CLIENT) private val webClient: WebClient,
    private val daprProperties: DaprProperties,
) {
    private val mapper = JsonMapper.builder().build()

    /**
     * S20: cart lines of the user (`shoppingcart`, path `/`), forwarding the caller's `Authorized-User` header.
     *
     * @return shopping cart item id -> (product variant id, count as u64)
     */
    suspend fun shoppingCartLines(authorizedUser: AuthorizedUser, userId: UUID): Map<UUID, Pair<UUID, Long>> {
        val variables = linkedMapOf("representations" to listOf(representation("User", userId)))
        val response = post(
            "shoppingcart", "", SHOPPING_CART_QUERY, "GetShoppingCartProductVariantIdsAndCounts", variables,
            AuthorizedUserHeader.serialize(authorizedUser)
        )
        val empty = "Response data of `query_counts_by_product_variant_ids` query is empty."
        val data = response.data() ?: throw RustError(empty)
        val entities = data.requiredArray("_entities")
        if (entities.isEmpty) {
            // the original panics here (`Vec::remove(0)` on an empty vector), which drops the connection
            throw RustError(empty)
        }
        val entity = entities[0]
        if (entity == null || entity.isNull) throw RustError(empty)
        val typeName = entity.requiredString("__typename")
        if (typeName != "User") {
            if (typeName !in setOf("ProductVariant", "ShoppingCartItem")) decodeError()
            throw RustError(
                "`ids_and_counts_enum: get_shopping_cart_product_variant_ids_and_counts::GetShoppingCartProductVariantIdsAndCountsEntities` " +
                    "does not contain a `get_shopping_cart_product_variant_ids_and_counts::GetShoppingCartProductVariantIdsAndCountsEntities::User`, " +
                    "but is another entity: `$typeName`"
            )
        }
        val nodes = entity.requiredObject("shoppingcart").requiredObject("shoppingcartItems").requiredArray("nodes")
        val lines = LinkedHashMap<UUID, Pair<UUID, Long>>()
        for (node in nodes.elementList()) {
            val id = node.requiredUuid("id")
            val productVariantId = node.requiredObject("productVariant").requiredUuid("id")
            lines[id] = productVariantId to node.requiredLong("count") // `count as u64`
        }
        return lines
    }

    /** S21: `inventoryCount` per product variant (`inventory`, path `/graphql`). */
    suspend fun inventoryCounts(productVariantIds: List<UUID>): Map<UUID, Long> {
        val variables = linkedMapOf("representations" to productVariantIds.map { representation("ProductVariant", it) })
        val response = post("inventory", "graphql", INVENTORY_QUERY, "GetUnreservedProductItemCounts", variables, null)
        val data = response.data()
            ?: throw RustError("Response data of `check_product_variant_availability` query is empty.")
        val counts = LinkedHashMap<UUID, Long>()
        // the whole response is decoded before it is processed
        val entities = data.requiredArray("_entities").elementList().map { entity ->
            if (entity.isNull) null else {
                when (entity.requiredString("__typename")) {
                    "ProductVariant" -> entity.requiredUuid("id") to entity.requiredLong("inventoryCount")
                    // the original reaches `todo!()` (panic) for a ProductItem entity
                    "ProductItem" -> throw RustError("not yet implemented")
                    else -> decodeError()
                }
            }
        }
        for (entity in entities) {
            val (id, count) = entity ?: throw RustError(
                "Response data of `check_product_variant_availability` query could not be parsed, `maybe_product_variant_enum` is `None`"
            )
            if (count < 0) throw RustError(RustMessages.OUT_OF_RANGE) // u64::try_from(i64)
            counts[id] = count
        }
        return counts
    }

    /** S22: `findApplicableDiscounts` (`discount`, path `/graphql`, no auth header). */
    suspend fun applicableDiscounts(
        userId: UUID,
        items: List<DiscountRequestItem>,
        orderAmount: Long,
    ): List<Pair<UUID, List<ApplicableDiscount>>> {
        val input = linkedMapOf(
            "orderAmount" to orderAmount,
            "productVariants" to items.map {
                linkedMapOf(
                    "count" to it.count,
                    "couponIds" to it.couponIds.map(UUID::toString),
                    "productVariantId" to it.productVariantId.toString(),
                )
            },
            "userId" to userId.toString(),
        )
        val response = post(
            "discount", "graphql", DISCOUNT_QUERY, "GetDiscounts", linkedMapOf("findApplicableDiscountsInput" to input), null
        )
        val data = response.data() ?: throw RustError("Response data of `query_discounts` query is empty.")
        return data.requiredArray("findApplicableDiscounts").elementList().map { entry ->
            if (!entry.isObject) decodeError()
            entry.requiredUuid("productVariantId") to entry.requiredArray("discounts").elementList().map { discount ->
                if (!discount.isObject) decodeError()
                ApplicableDiscount(discount.requiredUuid("id"), discount.requiredDouble("discount"))
            }
        }
    }

    /** S23: `calculateShipmentFees` (`shipment`, path `/graphql`, no auth header); the original discards the result. */
    suspend fun shipmentFees(items: List<ShipmentFeeRequestItem>): Long {
        val input = linkedMapOf(
            "items" to items.map {
                linkedMapOf(
                    "productVariantVersionId" to it.productVariantVersionId.toString(),
                    "quantity" to it.quantity,
                    "shipmentMethodId" to it.shipmentMethodId.toString(),
                )
            }
        )
        val response = post(
            "shipment", "graphql", SHIPMENT_QUERY, "GetShipmentFees", linkedMapOf("calculateShipmentFeesInput" to input), null
        )
        val data = response.data() ?: throw RustError("Response data of `query_shipment_fees` query is empty.")
        val fees = data.requiredLong("calculateShipmentFees")
        if (fees < 0) throw RustError(RustMessages.OUT_OF_RANGE)
        return fees
    }

    // ------------------------------------------------------------------------------------------------------------

    private fun representation(typeName: String, id: UUID) = linkedMapOf("__typename" to typeName, "id" to id.toString())

    /** POSTs a graphql_client `QueryBody` and parses the response as `graphql_client::Response`. */
    private suspend fun post(
        appId: String,
        method: String,
        query: String,
        operationName: String,
        variables: Map<String, Any?>,
        authorizedUserHeader: String?,
    ): JsonNode {
        val url = "${daprProperties.httpEndpoint.trimEnd('/')}/v1.0/invoke/$appId/method/$method"
        val body = mapper.writeValueAsBytes(
            linkedMapOf("variables" to variables, "query" to query, "operationName" to operationName)
        )
        val bytes = try {
            webClient.post()
                .uri(java.net.URI.create(url))
                .contentType(MediaType.APPLICATION_JSON)
                .apply { if (authorizedUserHeader != null) header(AuthorizedUser.HEADER_NAME, authorizedUserHeader) }
                .bodyValue(body)
                .exchangeToMono { response -> response.bodyToMono(ByteArray::class.java) }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            throw RustError(RustMessages.errorSendingRequest(url))
        }
        val node = try {
            mapper.readTree(bytes ?: ByteArray(0))
        } catch (e: Exception) {
            null
        }
        if (node == null || !node.isObject) decodeError()
        return node
    }

    /** `Response.data`: null if absent or null. */
    private fun JsonNode.data(): JsonNode? {
        val data = get("data")
        if (data == null || data.isNull) return null
        if (!data.isObject) decodeError()
        return data
    }

    companion object {
        private fun resource(name: String): String =
            RemoteServices::class.java.getResourceAsStream("/queries/$name")!!.readAllBytes().toString(Charsets.UTF_8)

        /** The verbatim query documents of the original (`order/queries/<name>.graphql`). */
        val SHOPPING_CART_QUERY = resource("get_shopping_cart_product_variant_ids_and_counts.graphql")
        val INVENTORY_QUERY = resource("get_unreserved_product_item_counts.graphql")
        val DISCOUNT_QUERY = resource("get_discounts.graphql")
        val SHIPMENT_QUERY = resource("get_shipment_fees.graphql")
    }
}

/** The elements of an array node. */
internal fun JsonNode.elementList(): List<JsonNode> = (0 until size()).map { get(it) }

private fun decodeError(): Nothing = throw RustError(RustMessages.ERROR_DECODING_RESPONSE_BODY)

private fun JsonNode.requiredNode(name: String): JsonNode {
    if (!isObject) decodeError()
    val node = get(name)
    if (node == null || node.isNull) decodeError()
    return node
}

private fun JsonNode.requiredObject(name: String): JsonNode = requiredNode(name).also { if (!it.isObject) decodeError() }

private fun JsonNode.requiredArray(name: String): JsonNode = requiredNode(name).also { if (!it.isArray) decodeError() }

private fun JsonNode.requiredString(name: String): String =
    requiredNode(name).let { if (it.isString) it.asString() else decodeError() }

private fun JsonNode.requiredUuid(name: String): UUID = RustUuid.tryParse(requiredString(name)) ?: decodeError()

/** serde `i64` from JSON (integers only). */
private fun JsonNode.requiredLong(name: String): Long =
    requiredNode(name).let { if (it.isIntegralNumber && it.canConvertToLong()) it.longValue() else decodeError() }

/** serde `f64` from JSON (any number). */
private fun JsonNode.requiredDouble(name: String): Double =
    requiredNode(name).let { if (it.isNumber) it.doubleValue() else decodeError() }
