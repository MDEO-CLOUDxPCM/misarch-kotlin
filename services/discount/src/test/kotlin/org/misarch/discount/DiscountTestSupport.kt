package org.misarch.discount

import org.assertj.core.api.Assertions.assertThat
import org.misarch.common.testing.MisarchIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Helpers shared by the discount integration tests (replica events, discounts, coupons, raw SQL).
 */
abstract class DiscountTestSupport : MisarchIntegrationTest() {

    @Autowired
    lateinit var databaseClient: DatabaseClient

    val employee: String = authorizedUserHeader(UUID.randomUUID(), "employee")

    fun deliverOk(topic: String, data: String) {
        assertThat(deliverEvent("/subscription/$topic", topic, data)).isEqualTo(200)
    }

    fun registerUser(id: UUID = UUID.randomUUID()): UUID {
        deliverOk("user/user/created", """{"id":"$id"}""")
        return id
    }

    fun registerCategory(id: UUID = UUID.randomUUID()): UUID {
        deliverOk("catalog/category/created", """{"id":"$id","name":"ignored"}""")
        return id
    }

    fun registerProduct(categoryIds: List<UUID> = emptyList(), id: UUID = UUID.randomUUID()): UUID {
        val categories = categoryIds.joinToString(",") { "\"$it\"" }
        deliverOk("catalog/product/created", """{"id":"$id","categoryIds":[$categories],"internalName":"x","isPubliclyVisible":true}""")
        return id
    }

    fun registerVariant(productId: UUID, id: UUID = UUID.randomUUID()): UUID {
        deliverOk("catalog/product-variant/created", """{"id":"$id","productId":"$productId","isPubliclyVisible":true}""")
        return id
    }

    fun ids(ids: List<Any>): String = ids.joinToString(", ", "[", "]") { "\"$it\"" }

    /** Runs `createDiscount` as employee and returns the response document. */
    fun createDiscountResponse(
        discount: Double = 0.1,
        maxUsagesPerUser: Int = 10,
        validFrom: String = PAST,
        validUntil: String = FUTURE,
        minOrderAmount: Int? = null,
        categoryIds: List<UUID> = emptyList(),
        productIds: List<UUID> = emptyList(),
        variantIds: List<UUID> = emptyList(),
        user: String? = employee,
    ): JsonNode = graphql(
        """
        mutation {
          createDiscount(input: {discount: $discount, maxUsagesPerUser: $maxUsagesPerUser, validFrom: "$validFrom",
            validUntil: "$validUntil", minOrderAmount: ${minOrderAmount ?: "null"},
            discountAppliesToCategoryIds: ${ids(categoryIds)}, discountAppliesToProductIds: ${ids(productIds)},
            discountAppliesToProductVariantIds: ${ids(variantIds)}}) {
            id discount maxUsagesPerUser validFrom validUntil minOrderAmount
          }
        }
        """.trimIndent(),
        user = user
    )

    fun createDiscount(
        discount: Double = 0.1,
        maxUsagesPerUser: Int = 10,
        validFrom: String = PAST,
        validUntil: String = FUTURE,
        minOrderAmount: Int? = null,
        categoryIds: List<UUID> = emptyList(),
        productIds: List<UUID> = emptyList(),
        variantIds: List<UUID> = emptyList(),
    ): UUID {
        val response = createDiscountResponse(
            discount, maxUsagesPerUser, validFrom, validUntil, minOrderAmount, categoryIds, productIds, variantIds
        )
        assertThat(response["errors"]).withFailMessage { "errors: ${response["errors"]}" }.isNull()
        return UUID.fromString(response["data"]["createDiscount"]["id"].asString())
    }

    fun createCouponResponse(
        discountId: UUID,
        code: String = "C-" + UUID.randomUUID(),
        maxUsages: Int = 5,
        validFrom: String = PAST,
        validUntil: String = FUTURE,
        user: String? = employee,
    ): JsonNode = graphql(
        """
        mutation {
          createCoupon(input: {maxUsages: $maxUsages, validFrom: "$validFrom", validUntil: "$validUntil", code: "$code",
            discountId: "$discountId"}) { id code usages maxUsages validFrom validUntil discount { id } }
        }
        """.trimIndent(),
        user = user
    )

    fun createCoupon(
        discountId: UUID,
        code: String = "C-" + UUID.randomUUID(),
        maxUsages: Int = 5,
        validFrom: String = PAST,
        validUntil: String = FUTURE,
    ): UUID {
        val response = createCouponResponse(discountId, code, maxUsages, validFrom, validUntil)
        assertThat(response["errors"]).withFailMessage { "errors: ${response["errors"]}" }.isNull()
        return UUID.fromString(response["data"]["createCoupon"]["id"].asString())
    }

    fun registerCoupon(
        code: String, userId: UUID, user: String = authorizedUserHeader(userId, "buyer"), selection: String = "id code"
    ): JsonNode =
        graphql("""mutation { registerCoupon(input: {code: "$code", userId: "$userId"}) { $selection } }""", user = user)

    fun couponCode(couponId: UUID): String =
        sql("SELECT code FROM CouponEntity WHERE id = '$couponId'").single()["code"] as String

    /** Executes raw SQL and returns the rows as maps (column names in lower case). */
    fun sql(statement: String): List<Map<String, Any?>> =
        databaseClient.sql(statement).fetch().all().collectList().block()!!

    fun execute(statement: String) {
        databaseClient.sql(statement).fetch().rowsUpdated().block()
    }


    companion object {
        const val PAST = "2020-01-01T00:00:00Z"
        const val FUTURE = "2099-01-01T00:00:00Z"
    }
}
