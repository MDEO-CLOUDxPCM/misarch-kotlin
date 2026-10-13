package org.misarch.discount

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The saga step: `inventory/product-item/reservation-succeeded` -> per-user usage check -> `DiscountUsage` upsert ->
 * `discount/order/validation-succeeded` / `-failed`.
 *
 * Ordered: the first tests run against the stock schema (where the original's upsert fails, defect Q1), then the
 * calibration workaround (`misarch-optimization/calibration/workarounds/discount_usage_columns.sql`) is applied for
 * the success path; it is removed again afterwards because the database is shared with the other test classes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DiscountValidationIntegrationTest : DiscountTestSupport() {

    private val route = "/subscription/inventory/product-item/reservation-succeeded"
    private val topic = "inventory/product-item/reservation-succeeded"

    private fun item(variantId: UUID, count: Long, discountIds: List<UUID>) = """
        {"id":"${UUID.randomUUID()}","createdAt":"2026-10-13T10:00:00.123456789Z","productVariantVersionId":"${UUID.randomUUID()}",
         "productVariantId":"$variantId","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}",
         "count":$count,"compensatableAmount":1234,"shipmentMethodId":"${UUID.randomUUID()}",
         "discountIds":[${discountIds.joinToString(",") { "\"$it\"" }}],"unknownItemField":42}
    """.trimIndent()

    private fun order(userId: UUID, items: List<String>, extra: String = "") = """
        {"id":"${UUID.randomUUID()}","userId":"$userId","createdAt":"2026-10-13T10:00:00Z","orderStatus":"PLACED",
         "placedAt":"2026-10-13T10:00:01Z","rejectionReason":null,"orderItems":[${items.joinToString(",")}],
         "shipmentAddressId":"${UUID.randomUUID()}","invoiceAddressId":"${UUID.randomUUID()}","compensatableOrderAmount":2468,
         "paymentInformationId":"${UUID.randomUUID()}","unknownOrderField":"x"$extra}
    """.trimIndent()

    private fun deliver(order: String): Int = deliverEvent(route, topic, """{"order":$order}""")

    private fun usages(discountId: UUID, userId: UUID): Long? =
        sql("SELECT usages FROM DiscountUsageEntity WHERE discountId = '$discountId' AND userId = '$userId'")
            .singleOrNull()?.get("usages") as Long?

    @Test
    @Order(1)
    fun `an order without discounts is validated and re-published without unknown and null fields`() {
        val userId = registerUser()
        val variantId = UUID.randomUUID()
        val order = order(userId, listOf(item(variantId, 2, emptyList())), extra = ""","vatNumber":null,"paymentAuthorization":{"cvc":123}""")
        assertThat(deliver(order)).isEqualTo(200)
        val event = dapr.awaitPublished("discount/order/validation-succeeded").single()
        assertThat(dapr.published("discount/order/validation-failed")).isEmpty()
        val published = event.json["order"]
        val expected = mapper.readTree(order) as tools.jackson.databind.node.ObjectNode
        expected.remove("unknownOrderField")
        expected.remove("rejectionReason")
        expected.remove("vatNumber")
        (expected["orderItems"][0] as tools.jackson.databind.node.ObjectNode).remove("unknownItemField")
        assertThat(published).isEqualTo(expected)
        assertThat(event.json.propertyNames().asSequence().toList()).containsExactly("order")
        // field order of the original DTOs (declaration order)
        assertThat(published.propertyNames().asSequence().toList()).containsExactly(
            "id", "userId", "createdAt", "orderStatus", "placedAt", "orderItems", "shipmentAddressId", "invoiceAddressId",
            "compensatableOrderAmount", "paymentInformationId", "paymentAuthorization"
        )
    }

    @Test
    @Order(2)
    fun `the real order payload (paymentAuthorization cVC) is read as cvc 0 and re-published as cvc 0, like the original`() {
        // order (Rust/serde) publishes {"cVC": 123}; the original discount (Jackson 2) ignores the unknown key and
        // reads the missing primitive as 0 (reference/events/discount_order_validation-succeeded.json: {"cvc":0}).
        val userId = registerUser()
        dapr.reset()
        val order = order(userId, listOf(item(UUID.randomUUID(), 1, emptyList())),
            extra = ""","vatNumber":"AB1234","paymentAuthorization":{"cVC":123}""")
        assertThat(deliver(order)).isEqualTo(200)
        val published = dapr.awaitPublished("discount/order/validation-succeeded").single().json["order"]
        assertThat(published["paymentAuthorization"].toString()).isEqualTo("""{"cvc":0}""")
        assertThat(published["vatNumber"].asString()).isEqualTo("AB1234")
    }

    @Test
    @Order(2)
    fun `exceeding the per-user limit publishes validation failed with the failing discounts and answers 200`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val limited = createDiscount(maxUsagesPerUser = 3, variantIds = listOf(variantId))
        val generous = createDiscount(maxUsagesPerUser = 100, variantIds = listOf(variantId))
        dapr.reset()
        val order = order(userId, listOf(item(variantId, 2, listOf(limited, generous)), item(variantId, 2, listOf(limited))))
        assertThat(deliver(order)).isEqualTo(200)
        val event = dapr.awaitPublished("discount/order/validation-failed").single().json
        assertThat(event["failingDiscountIds"].toList().map { it.asString() }).containsExactly(limited.toString())
        assertThat(event["order"]["id"].asString()).isEqualTo(mapper.readTree(order)["id"].asString())
        assertThat(dapr.published("discount/order/validation-succeeded")).isEmpty()
        assertThat(usages(generous, userId)).isNull()
    }

    @Test
    @Order(3)
    fun `on the stock schema the usage upsert fails - validation failed with an empty list and 500 (Q1)`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(maxUsagesPerUser = 10, variantIds = listOf(variantId))
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 1, listOf(discountId)))))).isEqualTo(500)
        val event = dapr.awaitPublished("discount/order/validation-failed").single().json
        assertThat(event["failingDiscountIds"].size()).isEqualTo(0)
        assertThat(dapr.published("discount/order/validation-succeeded")).isEmpty()
        assertThat(usages(discountId, userId)).isNull()
    }

    @Test
    @Order(4)
    fun `a discount without per-user limit makes the validation fail (Q3)`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(variantIds = listOf(variantId))
        execute("UPDATE DiscountEntity SET maxUsagesPerUser = NULL WHERE id = '$discountId'")
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 1, listOf(discountId)))))).isEqualTo(500)
        assertThat(dapr.awaitPublished("discount/order/validation-failed").single().json["failingDiscountIds"].size()).isEqualTo(0)
    }

    @Test
    @Order(5)
    fun `with the calibration workaround usages are upserted and validation succeeds`() {
        applyWorkaround()
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(maxUsagesPerUser = 10, variantIds = listOf(variantId))
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 2, listOf(discountId)), item(variantId, 1, listOf(discountId)))))).isEqualTo(200)
        assertThat(dapr.awaitPublished("discount/order/validation-succeeded")).hasSize(1)
        assertThat(usages(discountId, userId)).isEqualTo(3)
        assertThat(deliver(order(userId, listOf(item(variantId, 4, listOf(discountId)))))).isEqualTo(200)
        assertThat(usages(discountId, userId)).isEqualTo(7)
        // 7 used, 3 remaining: 4 more fail
        assertThat(deliver(order(userId, listOf(item(variantId, 4, listOf(discountId)))))).isEqualTo(200)
        assertThat(dapr.awaitPublished("discount/order/validation-failed").single().json["failingDiscountIds"][0].asString())
            .isEqualTo(discountId.toString())
        assertThat(usages(discountId, userId)).isEqualTo(7)
    }

    @Test
    @Order(6)
    fun `a failing upsert rolls back the usages of the same order`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(maxUsagesPerUser = 10, variantIds = listOf(variantId))
        val unknownDiscount = UUID.randomUUID() // not in the DB: no limit, but the upsert violates the foreign key
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 1, listOf(discountId, unknownDiscount)))))).isEqualTo(500)
        assertThat(dapr.awaitPublished("discount/order/validation-failed").single().json["failingDiscountIds"].size()).isEqualTo(0)
        assertThat(usages(discountId, userId)).isNull()
    }

    private fun applyWorkaround() {
        WORKAROUND.forEach { execute(it) }
    }

    @AfterAll
    fun removeWorkaround() {
        execute("DROP TRIGGER IF EXISTS a_sync_discount_usage_columns_trigger ON DiscountUsageEntity")
        execute("DROP FUNCTION IF EXISTS a_sync_discount_usage_columns()")
        execute("DROP INDEX IF EXISTS discountusage_snake_uk")
        execute("ALTER TABLE DiscountUsageEntity DROP COLUMN IF EXISTS discount_id")
        execute("ALTER TABLE DiscountUsageEntity DROP COLUMN IF EXISTS user_id")
    }

    companion object {
        /** `misarch-optimization/calibration/workarounds/discount_usage_columns.sql`, statement by statement. */
        private val WORKAROUND = listOf(
            "ALTER TABLE DiscountUsageEntity ADD COLUMN IF NOT EXISTS discount_id UUID",
            "ALTER TABLE DiscountUsageEntity ADD COLUMN IF NOT EXISTS user_id UUID",
            "CREATE UNIQUE INDEX IF NOT EXISTS discountusage_snake_uk ON DiscountUsageEntity (discount_id, user_id)",
            """
            CREATE OR REPLACE FUNCTION a_sync_discount_usage_columns() RETURNS TRIGGER AS ${'$'}${'$'}
            BEGIN
              NEW.discountid := COALESCE(NEW.discountid, NEW.discount_id);
              NEW.userid := COALESCE(NEW.userid, NEW.user_id);
              NEW.discount_id := COALESCE(NEW.discount_id, NEW.discountid);
              NEW.user_id := COALESCE(NEW.user_id, NEW.userid);
              RETURN NEW;
            END; ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
            "DROP TRIGGER IF EXISTS a_sync_discount_usage_columns_trigger ON DiscountUsageEntity",
            "CREATE TRIGGER a_sync_discount_usage_columns_trigger BEFORE INSERT ON DiscountUsageEntity " +
                "FOR EACH ROW EXECUTE FUNCTION a_sync_discount_usage_columns()",
        )
    }
}
