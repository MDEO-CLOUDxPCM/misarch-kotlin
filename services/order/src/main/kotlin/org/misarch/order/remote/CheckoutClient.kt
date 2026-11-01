package org.misarch.order.remote

import org.misarch.common.event.DaprServiceInvoker
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.order.remote.model.CalculateShipmentFeesInput
import org.misarch.order.remote.model.DiscountsForProductVariantDTO
import org.misarch.order.remote.model.FindApplicableDiscountsInput
import org.misarch.order.remote.model.ProductVariantInventoryDTO
import org.misarch.order.remote.model.ShoppingCartItemDTO
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.convertValue
import java.util.UUID

/**
 * The service invocations `createOrder` makes (through the Dapr sidecar), with the query documents in
 * `resources/queries`: the shopping cart of the user (shoppingcart), the inventory counts (inventory), the applicable
 * discounts (discount) and the shipment fees (shipment).
 */
@Component
class CheckoutClient(
    private val serviceInvoker: DaprServiceInvoker,
    private val objectMapper: ObjectMapper
) {

    /**
     * The items of the shopping cart of a user, requested on behalf of [authorizedUser]
     *
     * @param userId the id of the user
     * @param authorizedUser the user creating the order (forwarded to the shoppingcart service)
     */
    suspend fun shoppingCartItems(userId: UUID, authorizedUser: AuthorizedUser): List<ShoppingCartItemDTO> {
        val data = serviceInvoker.graphql(
            "shoppingcart",
            SHOPPING_CART_QUERY,
            mapOf("representations" to listOf(representation("User", userId))),
            authorizedUser,
            method = ""
        ).data("shoppingcart")
        val user = data["_entities"]?.get(0)?.takeUnless { it.isNull }
            ?: throw IllegalStateException("The shopping cart of user $userId was not found")
        return objectMapper.convertValue(user["shoppingcart"]["shoppingcartItems"]["nodes"])
    }

    /**
     * The inventory counts of product variants; a product variant unknown to the inventory is missing in the result
     *
     * @param productVariantIds the ids of the product variants
     */
    suspend fun inventoryCounts(productVariantIds: List<UUID>): Map<UUID, Long> {
        val data = serviceInvoker.graphql(
            "inventory",
            INVENTORY_QUERY,
            mapOf("representations" to productVariantIds.map { representation("ProductVariant", it) })
        ).data("inventory")
        return data["_entities"].filterNot { it.isNull }
            .map { objectMapper.convertValue<ProductVariantInventoryDTO>(it) }
            .associate { it.id to it.inventoryCount }
    }

    /**
     * The discounts applicable to the product variants of an order
     *
     * @param input the user, the product variants (with counts and coupons) and the order amount
     */
    suspend fun applicableDiscounts(input: FindApplicableDiscountsInput): List<DiscountsForProductVariantDTO> {
        val data = serviceInvoker.graphql(
            "discount",
            DISCOUNT_QUERY,
            mapOf("findApplicableDiscountsInput" to input)
        ).data("discount")
        return objectMapper.convertValue(data["findApplicableDiscounts"])
    }

    /**
     * The shipment fees of the items of an order
     *
     * @param input the product variant versions with their quantity and shipment method
     */
    suspend fun shipmentFees(input: CalculateShipmentFeesInput): Long {
        val data = serviceInvoker.graphql(
            "shipment",
            SHIPMENT_QUERY,
            mapOf("calculateShipmentFeesInput" to input)
        ).data("shipment")
        return data["calculateShipmentFees"].longValue()
    }

    private fun representation(typeName: String, id: UUID) = mapOf("__typename" to typeName, "id" to id)

    /** The `data` of a GraphQL response; fails with the response's errors if there is none. */
    private fun JsonNode.data(appId: String): JsonNode {
        val data = get("data")
        if (data == null || data.isNull) {
            throw IllegalStateException("The $appId service answered without data: ${get("errors")}")
        }
        return data
    }

    companion object {
        private fun query(name: String): String =
            CheckoutClient::class.java.getResourceAsStream("/queries/$name.graphql")!!.readAllBytes().toString(Charsets.UTF_8)

        val SHOPPING_CART_QUERY = query("get_shopping_cart_product_variant_ids_and_counts")
        val INVENTORY_QUERY = query("get_unreserved_product_item_counts")
        val DISCOUNT_QUERY = query("get_discounts")
        val SHIPMENT_QUERY = query("get_shipment_fees")
    }

}
