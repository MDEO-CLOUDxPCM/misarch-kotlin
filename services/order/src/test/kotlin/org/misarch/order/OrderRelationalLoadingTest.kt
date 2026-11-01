package org.misarch.order

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import org.misarch.common.testing.SqlStatementLog
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.model.OrderItemToDiscountEntity
import org.misarch.order.persistence.model.OrderItemToDiscountId
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.sql.DriverManager
import java.util.UUID

/**
 * The statements of the order queries: a resolver reads its own table, `orderItems` and `discounts` are connections
 * whose statements run only when their fields are selected; and the migrations of data written by earlier versions.
 */
@Import(SqlStatementLog::class)
class OrderRelationalLoadingTest : OrderTestSupport() {

    /** A random UUID whose first byte is [firstByte] (the byte order of Postgres differs from `java.util.UUID` order). */
    private fun uuid(firstByte: Int): UUID {
        val random = UUID.randomUUID()
        return UUID((random.mostSignificantBits and 0x00FFFFFFFFFFFFFFL) or (firstByte.toLong() shl 56), random.leastSignificantBits)
    }

    /** An order of [userId] with items [itemIds]; the first item has the discounts [discountIds]. */
    private fun insert(userId: UUID, itemIds: List<UUID>, discountIds: List<UUID>): UUID = runBlocking {
        val order = orderRepository.save(
            OrderEntity(
                userId = userId, createdAt = now(), orderStatus = OrderStatus.PENDING, placedAt = null, rejectionReason = null,
                shipmentAddressId = UUID.randomUUID(), invoiceAddressId = UUID.randomUUID(), compensatableOrderAmount = 20,
                paymentInformationId = UUID.randomUUID(), vatNumber = null, id = null,
            )
        )
        itemIds.forEachIndexed { position, id ->
            // items with given ids: inserted like a new row (the repository would update an entity with an id)
            sql(
                "INSERT INTO OrderItemEntity (id, orderId, position, createdAt, productVariantId, productVariantIsPubliclyVisible, " +
                    "productVariantVersionId, productVariantVersionPrice, productVariantVersionTaxRateId, taxRateVersionId, " +
                    "taxRateVersionRate, taxRateVersionVersion, shoppingCartItemId, count, compensatableAmount, shipmentMethodId) " +
                    "VALUES ('$id', '${order.id}', $position, now(), gen_random_uuid(), true, gen_random_uuid(), 20, gen_random_uuid(), " +
                    "gen_random_uuid(), 0.19, 1, gen_random_uuid(), 1, 20, gen_random_uuid())"
            )
        }
        orderItemToDiscountRepository.saveAll(discountIds.map {
            OrderItemToDiscountEntity(OrderItemToDiscountId(itemIds.first(), it), 0.1)
        }).toList()
        order.id!!
    }

    private data class Page(val nodes: List<UUID>, val hasNextPage: Boolean, val totalCount: Int)

    /** The expected page of a list of ids ordered by id. */
    private fun expected(ids: List<UUID>, first: Int?, skip: Int?, desc: Boolean): Page {
        val sorted = ids.sortedWith(if (desc) UUID_ORDER.reversed() else UUID_ORDER)
        val definitelySkip = skip ?: 0
        val part = sorted.drop(definitelySkip).let { if (first != null) it.take(first) else it }
        return Page(part, first != null && sorted.size > definitelySkip + first, sorted.size)
    }

    private fun args(first: Int?, skip: Int?, direction: String?): String {
        val parts = listOfNotNull(first?.let { "first: $it" }, skip?.let { "skip: $it" }, direction?.let { "orderBy: {direction: $it}" })
        return if (parts.isEmpty()) "" else parts.joinToString(", ", "(", ")")
    }

    private fun tables(statements: List<String>) = statements.map { sql ->
        Regex("""FROM "?(\w+)"?""", RegexOption.IGNORE_CASE).find(sql)?.groupValues?.get(1)?.lowercase() ?: sql
    }

    @Test
    fun `orderItems and discounts page by id`() {
        val userId = UUID.randomUUID()
        val itemIds = listOf(uuid(0xf0), uuid(0x02), uuid(0x01), uuid(0x80))
        val discountIds = listOf(uuid(0x90), uuid(0x10), uuid(0xff))
        val orderId = insert(userId, itemIds, discountIds)
        val cases = buildList {
            for (first in listOf(null, 0, 1, 2, 3, 4, 5)) for (skip in listOf(null, 0, 1, 3, 4, 6)) for (dir in listOf(null, "ASC", "DESC")) {
                add(Triple(first, skip, dir))
            }
        }
        val itemFields = cases.mapIndexed { n, (first, skip, dir) -> "i$n: orderItems${args(first, skip, dir)} { totalCount hasNextPage nodes { id } }" }
        val discountFields = cases.mapIndexed { n, (first, skip, dir) -> "d$n: discounts${args(first, skip, dir)} { totalCount hasNextPage nodes { id } }" }
        val result = graphql(
            """{ order(id: "$orderId") { ${itemFields.joinToString(" ")} }
                 orderItem(id: "${itemIds.first()}") { ${discountFields.joinToString(" ")} } }""",
            user = buyer(userId),
        )
        assertThat(result["errors"]).isNull()
        fun page(node: JsonNode) = Page(node["nodes"].toList().map { UUID.fromString(it["id"].asString()) }, node["hasNextPage"].asBoolean(), node["totalCount"].asInt())
        cases.forEachIndexed { n, (first, skip, dir) ->
            assertThat(page(result["data"]["order"]["i$n"])).describedAs("orderItems $first $skip $dir")
                .isEqualTo(expected(itemIds, first, skip, dir == "DESC"))
            assertThat(page(result["data"]["orderItem"]["d$n"])).describedAs("discounts $first $skip $dir")
                .isEqualTo(expected(discountIds, first, skip, dir == "DESC"))
        }
        val negative = graphql("""{ order(id: "$orderId") { orderItems(first: -1) { nodes { id } } } }""", user = buyer(userId))
        assertThat(errorMessage(negative)).endsWith("Limit must not be negative.")
        val negativeSkip = graphql("""{ orderItem(id: "${itemIds.first()}") { discounts(skip: -1) { nodes { id } } } }""", user = buyer(userId))
        assertThat(errorMessage(negativeSkip)).endsWith("Offset must not be negative.")
    }

    @Test
    fun `an order is read from its own table, items and discounts only when selected`() {
        val userId = UUID.randomUUID()
        val itemIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val orderId = insert(userId, itemIds, listOf(UUID.randomUUID()))

        val orderOnly = SqlStatementLog.record {
            assertThat(graphql("""{ order(id: "$orderId") { id orderStatus compensatableOrderAmount } }""", user = buyer(userId))["errors"]).isNull()
        }
        assertThat(tables(orderOnly)).containsExactly("orderentity")

        val withItems = SqlStatementLog.record {
            val result = graphql("""{ order(id: "$orderId") { id orderItems { nodes { id count } } } }""", user = buyer(userId))
            assertThat(result["data"]["order"]["orderItems"]["nodes"].size()).isEqualTo(2)
        }
        assertThat(tables(withItems)).containsExactly("orderentity", "orderitementity")
        assertThat(withItems[1]).contains("WHERE orderId = ").contains("ORDER BY id ASC").doesNotContain("LIMIT")

        // totalCount and hasNextPage are statements of their own, the discounts one statement per item
        val full = SqlStatementLog.record {
            val result = graphql(
                """{ order(id: "$orderId") { orderItems(first: 1) { totalCount hasNextPage nodes { id discounts { nodes { id } } } } } }""",
                user = buyer(userId),
            )
            assertThat(result["errors"]).isNull()
        }
        assertThat(tables(full)).containsExactlyInAnyOrder(
            "orderentity", "orderitementity", "orderitementity", "orderitementity", "orderitemtodiscountentity"
        )
        assertThat(full).anyMatch { it.startsWith("SELECT COUNT(t.id) FROM \"orderitementity\" t") }
            .anyMatch { it.startsWith("SELECT 1 FROM \"orderitementity\" t") && it.endsWith("LIMIT 1 OFFSET 1") }

        // orderItem: the item, then its order for the authorization; the entity resolver only the item
        val item = SqlStatementLog.record {
            assertThat(graphql("""{ orderItem(id: "${itemIds[0]}") { id } }""", user = buyer(userId))["errors"]).isNull()
        }
        assertThat(tables(item)).containsExactly("orderitementity", "orderentity")
        val entity = SqlStatementLog.record {
            val result = graphql(
                """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on OrderItem { id } } }""",
                mapOf("r" to listOf(mapOf("__typename" to "OrderItem", "id" to itemIds[0].toString()))),
            )
            assertThat(result["errors"]).isNull()
        }
        assertThat(tables(entity)).containsExactly("orderitementity")
    }

    @Test
    fun `User orders runs only the statements of the selected connection fields`() {
        val userId = registerUser()
        repeat(3) { insertOrder(userId, items = 2) }
        fun orders(selection: String) = SqlStatementLog.record {
            val result = graphql(
                """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { orders(first: 2) { $selection } } } }""",
                mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))), buyer(userId),
            )
            assertThat(result["errors"]).isNull()
        }
        val nodes = orders("nodes { id }")
        assertThat(nodes).singleElement().satisfies({
            assertThat(it).startsWith("SELECT t.* FROM \"orderentity\" t WHERE userId = ").endsWith("ORDER BY id ASC LIMIT 2 OFFSET 0")
        })
        val counts = orders("totalCount hasNextPage")
        assertThat(counts).hasSize(2).noneMatch { it.startsWith("SELECT t.*") }
            .anyMatch { it.startsWith("SELECT COUNT(t.id) FROM \"orderentity\" t") }
            .anyMatch { it.startsWith("SELECT 1 FROM \"orderentity\" t") && it.endsWith("LIMIT 1 OFFSET 2") }
        val withItems = orders("nodes { orderItems { totalCount } }")
        assertThat(withItems.count { it.startsWith("SELECT COUNT(t.id) FROM \"orderitementity\" t") }).isEqualTo(2)
    }

    @Test
    fun `createOrder reads the replicas with one statement each and binds id lists as arrays`() {
        val userId = registerUser()
        val shipmentMethodId = registerShipmentMethod()
        val (taxRateId, _) = registerTaxRate()
        val productVariants = List(3) { UUID.randomUUID() }
        productVariants.forEach { registerProductVariantVersion(it, 10, taxRateId) }
        val addressId = registerUserAddress(userId)
        val cart = productVariants.associateBy { UUID.randomUUID() }
        CheckoutStubs(cart.mapValues { it.value to 1L }, productVariants.associateWith { 10L }, productVariants.associateWith { emptyList() })
            .install(dapr)
        val input = mapOf(
            "userId" to userId.toString(), "shipmentAddressId" to addressId.toString(), "invoiceAddressId" to addressId.toString(),
            "paymentInformationId" to UUID.randomUUID().toString(),
            "orderItemInputs" to cart.keys.map { mapOf("shoppingCartItemId" to it.toString(), "shipmentMethodId" to shipmentMethodId.toString(), "couponIds" to emptyList<String>()) },
        )
        val statements = SqlStatementLog.record {
            val result = graphql("""mutation(${'$'}input: CreateOrderInput!) { createOrder(input: ${'$'}input) { id } }""", mapOf("input" to input), buyer(userId))
            assertThat(result["errors"]).isNull()
        }
        val reads = statements.filter { it.startsWith("SELECT") }
        assertThat(tables(reads)).containsExactly(
            "userentity", "shipmentmethodentity", "useraddressentity", "productvariantentity", "taxrateentity"
        )
        assertThat(reads.drop(1)).allMatch { "= ANY(" in it }
        assertThat(statements.count { it.startsWith("INSERT INTO") && "orderitementity" in it }).isEqualTo(3)
        assertThat(statements).contains("BEGIN").contains("COMMIT")
    }

    /** V2 turns the id arrays of V1 into rows, V4 renames the tables and columns (data of earlier versions). */
    @Test
    fun `migrations keep the data of earlier versions`() {
        val schema = "order_migration_" + UUID.randomUUID().toString().replace("-", "").take(8)
        fun flyway(target: String) = Flyway.configure()
            .dataSource(PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password)
            .schemas(schema).locations("classpath:db/migration").target(target).load()
        flyway("1").migrate()
        val (user, a1, a2, compensation, i1, i2) = List(6) { UUID.randomUUID() }
        val order = UUID.randomUUID()
        DriverManager.getConnection(
            PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password
        ).use { connection ->
            fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> = connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
            connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                s.execute("INSERT INTO users (id, user_address_ids) VALUES ('$user', ARRAY['$a1', '$a2', '$a1']::uuid[])")
                s.execute(
                    "INSERT INTO orders (id, user_id, created_at, order_status, shipment_address_id, invoice_address_id, " +
                        "compensatable_order_amount, payment_information_id) VALUES ('$order', '$user', now(), 'PENDING', '$a1', '$a1', 7, '$a2')"
                )
                s.execute(
                    "INSERT INTO order_compensations (id, order_id, order_item_ids, triggered_at, amount_to_compensate) " +
                        "VALUES ('$compensation', '$order', ARRAY['$i2', '$i1']::uuid[], now(), 5)"
                )
            }
            flyway("latest").migrate()
            assertThat(query("SELECT id FROM UserAddressEntity WHERE userId = '$user'") { it.getObject(1, UUID::class.java) })
                .containsExactlyInAnyOrder(a1, a2)
            assertThat(
                query("SELECT orderItemId FROM OrderCompensationToOrderItemEntity WHERE orderCompensationId = '$compensation' ORDER BY position") {
                    it.getObject(1, UUID::class.java)
                }
            ).containsExactly(i2, i1)
            assertThat(query("SELECT userId, compensatableOrderAmount, orderStatus FROM OrderEntity WHERE id = '$order'") {
                listOf(it.getObject(1, UUID::class.java), it.getLong(2), it.getString(3))
            }).containsExactly(listOf(user, 7L, "PENDING"))
            connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

}

private operator fun <T> List<T>.component6(): T = this[5]
