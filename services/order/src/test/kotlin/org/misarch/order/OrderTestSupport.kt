package org.misarch.order

import org.misarch.common.testing.FakeDaprSidecar
import org.misarch.common.testing.FakeDaprSidecar.StubResponse
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Stubs of the four services `createOrder` invokes, answering like the originals (shoppingcart `_entities(User)`,
 * inventory `_entities(ProductVariant)`, discount `findApplicableDiscounts`, shipment `calculateShipmentFees`).
 *
 * @property cart shopping cart item id -> (product variant id, count)
 * @property stock product variant id -> inventoryCount (variants missing here are answered with a null entity)
 * @property discounts product variant id -> (discount id, discount); variants missing here are omitted from the answer
 */
class CheckoutStubs(
    val cart: Map<UUID, Pair<UUID, Long>>,
    val stock: Map<UUID, Long>,
    val discounts: Map<UUID, List<Pair<UUID, Double>>>,
    val fees: Long = 5,
) {
    private val mapper = JsonMapper.builder().build()

    fun install(dapr: FakeDaprSidecar) {
        dapr.stubInvocation("shoppingcart", "") {
            val nodes = cart.entries.joinToString(",") { (id, line) ->
                """{"id":"$id","productVariant":{"id":"${line.first}"},"count":${line.second}}"""
            }
            StubResponse(body = """{"data":{"_entities":[{"__typename":"User","shoppingcart":{"shoppingcartItems":{"nodes":[$nodes]}}}]}}""")
        }
        dapr.stubInvocation("inventory", "graphql") { invocation ->
            val representations = mapper.readTree(invocation.body)["variables"]["representations"]
            val entities = (0 until representations.size()).joinToString(",") { i ->
                val id = UUID.fromString(representations[i]["id"].asString())
                stock[id]?.let { """{"__typename":"ProductVariant","id":"$id","inventoryCount":$it}""" } ?: "null"
            }
            StubResponse(body = """{"data":{"_entities":[$entities]}}""")
        }
        dapr.stubInvocation("discount", "graphql") { invocation ->
            val variants = mapper.readTree(invocation.body)["variables"]["findApplicableDiscountsInput"]["productVariants"]
            val entries = (0 until variants.size()).mapNotNull { i ->
                val id = UUID.fromString(variants[i]["productVariantId"].asString())
                discounts[id]?.let { list ->
                    val ds = list.joinToString(",") { (discountId, value) -> """{"id":"$discountId","discount":$value}""" }
                    """{"productVariantId":"$id","discounts":[$ds]}"""
                }
            }.joinToString(",")
            StubResponse(body = """{"data":{"findApplicableDiscounts":[$entries]}}""")
        }
        dapr.stubInvocation("shipment", "graphql") {
            StubResponse(body = """{"data":{"calculateShipmentFees":$fees}}""")
        }
    }
}
