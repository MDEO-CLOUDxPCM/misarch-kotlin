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
 * Ordered: the first tests run against the stock schema, then the calibration workaround
 * (`architecture-optimization/cases/misarch/timing/calibration/workarounds/discount_usage_columns.sql`) is applied to
 * show that the upsert also works with it; it is removed again afterwards because the database is shared with the
 * other test classes.
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
        // field order of the event contract (declaration order of the DTOs)
        assertThat(published.propertyNames().asSequence().toList()).containsExactly(
            "id", "userId", "createdAt", "orderStatus", "placedAt", "orderItems", "shipmentAddressId", "invoiceAddressId",
            "compensatableOrderAmount", "paymentInformationId", "paymentAuthorization"
        )
    }

    @Test
    @Order(2)
    fun `the cvc is kept - both the key cvc and the alias cVC are read`() {
        for (auth in listOf("""{"cvc":123}""", """{"cVC":123}""")) {
            val userId = registerUser()
            dapr.reset()
            val order = order(userId, listOf(item(UUID.randomUUID(), 1, emptyList())),
                extra = ""","vatNumber":"AB1234","paymentAuthorization":$auth""")
            assertThat(deliver(order)).isEqualTo(200)
            val published = dapr.awaitPublished("discount/order/validation-succeeded").single().json["order"]
            assertThat(published["paymentAuthorization"].toString()).isEqualTo("""{"cvc":123}""")
            assertThat(published["vatNumber"].asString()).isEqualTo("AB1234")
        }
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
    fun `on the stock schema the usages are upserted and validation succeeds`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(maxUsagesPerUser = 10, variantIds = listOf(variantId))
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 2, listOf(discountId)), item(variantId, 1, listOf(discountId)))))).isEqualTo(200)
        assertThat(dapr.awaitPublished("discount/order/validation-succeeded")).hasSize(1)
        assertThat(dapr.published("discount/order/validation-failed")).isEmpty()
        assertThat(usages(discountId, userId)).isEqualTo(3)
        // second order of the same user: ON CONFLICT adds to the existing row
        assertThat(deliver(order(userId, listOf(item(variantId, 4, listOf(discountId)))))).isEqualTo(200)
        assertThat(usages(discountId, userId)).isEqualTo(7)
        // 7 used, 3 remaining: 4 more fail with the failing discount, nothing is added
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 4, listOf(discountId)))))).isEqualTo(200)
        assertThat(dapr.awaitPublished("discount/order/validation-failed").single().json["failingDiscountIds"][0].asString())
            .isEqualTo(discountId.toString())
        assertThat(usages(discountId, userId)).isEqualTo(7)
    }

    @Test
    @Order(3)
    fun `a usage rejected by the database trigger publishes validation failed once and acknowledges the event`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(maxUsagesPerUser = 3, variantIds = listOf(variantId))
        execute("INSERT INTO DiscountUsageEntity (discountId, userId, usages) VALUES ('$discountId', '$userId', 2)")
        // remaining = 3 - 2 = 1 >= 1 passes the service check; lower the limit only for the trigger
        execute("CREATE OR REPLACE FUNCTION test_lower_limit() RETURNS TRIGGER AS ${'$'}${'$'} BEGIN " +
            "UPDATE DiscountEntity SET maxUsagesPerUser = 2 WHERE id = NEW.discountId; RETURN NEW; END; ${'$'}${'$'} LANGUAGE plpgsql")
        execute("CREATE TRIGGER a_test_lower_limit BEFORE UPDATE OF usages ON DiscountUsageEntity FOR EACH ROW EXECUTE FUNCTION test_lower_limit()")
        try {
            dapr.reset()
            assertThat(deliver(order(userId, listOf(item(variantId, 1, listOf(discountId)))))).isEqualTo(200)
            assertThat(dapr.awaitPublished("discount/order/validation-failed").single().json["failingDiscountIds"].size()).isEqualTo(0)
            assertThat(dapr.published("discount/order/validation-succeeded")).isEmpty()
            assertThat(usages(discountId, userId)).isEqualTo(2)
        } finally {
            execute("DROP TRIGGER IF EXISTS a_test_lower_limit ON DiscountUsageEntity")
            execute("DROP FUNCTION IF EXISTS test_lower_limit()")
        }
    }

    @Test
    @Order(4)
    fun `a discount without per-user limit is unlimited`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(variantIds = listOf(variantId))
        execute("UPDATE DiscountEntity SET maxUsagesPerUser = NULL WHERE id = '$discountId'")
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 1000, listOf(discountId)))))).isEqualTo(200)
        assertThat(dapr.awaitPublished("discount/order/validation-succeeded")).hasSize(1)
        assertThat(usages(discountId, userId)).isEqualTo(1000)
    }

    @Test
    @Order(5)
    fun `the upsert also works with the calibration workaround applied`() {
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
    fun `a failing upsert rolls back the usages of the same order and acknowledges the event`() {
        val userId = registerUser()
        val variantId = registerVariant(registerProduct())
        val discountId = createDiscount(maxUsagesPerUser = 10, variantIds = listOf(variantId))
        val unknownDiscount = UUID.randomUUID() // not in the DB: no limit, but the upsert violates the foreign key
        dapr.reset()
        assertThat(deliver(order(userId, listOf(item(variantId, 1, listOf(discountId, unknownDiscount)))))).isEqualTo(200)
        assertThat(dapr.awaitPublished("discount/order/validation-failed").single().json["failingDiscountIds"].size()).isEqualTo(0)
        assertThat(dapr.published("discount/order/validation-succeeded")).isEmpty()
        assertThat(usages(discountId, userId)).isNull()
    }

    @Test
    @Order(7)
    fun `if even the failure event cannot be published the event is redelivered`() {
        val userId = registerUser()
        dapr.reset()
        dapr.publishStatus = 500
        try {
            assertThat(deliver(order(userId, listOf(item(UUID.randomUUID(), 1, emptyList()))))).isEqualTo(500)
        } finally {
            dapr.publishStatus = 204
        }
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
        /** `architecture-optimization/cases/misarch/timing/calibration/workarounds/discount_usage_columns.sql`, statement by statement. */
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
