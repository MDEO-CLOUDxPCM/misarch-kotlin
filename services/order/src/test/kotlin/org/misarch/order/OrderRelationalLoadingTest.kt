package org.misarch.order

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.rust.RustDateTime
import org.misarch.common.rust.RustUuid
import org.misarch.common.testing.PostgresTestDatabase
import org.misarch.common.testing.SqlStatementLog
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.persistence.model.OrderItemWithDiscounts
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.persistence.model.ProductVariantVersionRecord
import org.misarch.order.persistence.model.TaxRateVersionRecord
import org.springframework.context.annotation.Import
import java.sql.DriverManager
import java.util.UUID

/**
 * The relational loading of orders (docs/specs/order.md §5.4, §9): a resolver reads its own table, `orderItems` and
 * `discounts` are connections read only when selected, with the paging semantics of the original's in-memory lists.
 */
@Import(SqlStatementLog::class)
class OrderRelationalLoadingTest : OrderIntegrationTestBase() {

    /** A random UUID whose first byte is [firstByte] (unsigned byte order differs from `java.util.UUID` order). */
    private fun uuid(firstByte: Int): UUID {
        val random = UUID.randomUUID()
        return UUID((random.mostSignificantBits and 0x00FFFFFFFFFFFFFFL) or (firstByte.toLong() shl 56), random.leastSignificantBits)
    }

    private fun item(orderId: UUID, id: UUID) = OrderItemRecord(
        id = id, orderId = orderId, createdAt = RustDateTime.now(), productVariantId = UUID.randomUUID(),
        productVariantIsPubliclyVisible = true, productVariantVersion = ProductVariantVersionRecord(UUID.randomUUID(), 20, UUID.randomUUID()),
        taxRateVersion = TaxRateVersionRecord(UUID.randomUUID(), 0.19, 1), shoppingCartItemId = UUID.randomUUID(),
        count = 1, compensatableAmount = 20, shipmentMethodId = UUID.randomUUID(),
    )

    /** An order of [userId] with items [itemIds] (insertion order); the first item has the discounts [discountIds]. */
    private fun insert(userId: UUID, itemIds: List<UUID>, discountIds: List<UUID>): UUID {
        val orderId = UUID.randomUUID()
        val order = OrderRecord(
            id = orderId, userId = userId, createdAt = RustDateTime.now(), orderStatus = OrderStatus.PENDING, placedAt = null,
            rejectionReason = null, shipmentAddressId = UUID.randomUUID(), invoiceAddressId = UUID.randomUUID(),
            compensatableOrderAmount = 20, paymentInformationId = UUID.randomUUID(), vatNumber = null,
        )
        val items = itemIds.mapIndexed { n, id ->
            OrderItemWithDiscounts(item(orderId, id), if (n == 0) discountIds.map { DiscountRecord(it, 0.1) } else emptyList())
        }
        runBlocking { store.insertOrder(order, items) }
        return orderId
    }

    /** The original's in-memory pagination (`order.rs`/`order_item.rs`), the reference of the SQL connections. */
    private data class Page(val nodes: List<UUID>, val hasNextPage: Boolean, val totalCount: Int)

    private fun inMemory(ids: List<UUID>, first: Int?, skip: Int?, desc: Boolean): Page {
        val sorted = ids.sortedWith(if (desc) RustUuid.ORDER.reversed() else RustUuid.ORDER)
        val definitelySkip = skip ?: 0
        val part = sorted.drop(definitelySkip).let { if (first != null) it.take(first) else it }
        return Page(part, sorted.size.toLong() > part.size.toLong() + definitelySkip, sorted.size)
    }

    private fun args(first: Int?, skip: Int?, direction: String?): String {
        val parts = listOfNotNull(first?.let { "first: $it" }, skip?.let { "skip: $it" }, direction?.let { "orderBy: {direction: $it}" })
        return if (parts.isEmpty()) "" else parts.joinToString(", ", "(", ")")
    }

    @Test
    fun `orderItems and discounts page in SQL exactly like the original's in-memory lists`() {
        val userId = UUID.randomUUID()
        val itemIds = listOf(uuid(0xf0), uuid(0x02), uuid(0x01), uuid(0x80))
        val discountIds = listOf(uuid(0x90), uuid(0x10), uuid(0xff))
        val orderId = insert(userId, itemIds, discountIds)
        val cases = buildList {
            for (first in listOf(null, 0, 1, 2, 3, 4, 5)) for (skip in listOf(null, 0, 1, 3, 4, 6)) for (dir in listOf(null, "ASC", "DESC")) {
                add(Triple(first, skip, dir))
            }
        }
        val fields = cases.mapIndexed { n, (first, skip, dir) ->
            "i$n: orderItems${args(first, skip, dir)} { totalCount hasNextPage nodes { id } }"
        }
        val firstItem = itemIds.first()
        val discountFields = cases.mapIndexed { n, (first, skip, dir) ->
            "d$n: discounts${args(first, skip, dir)} { totalCount hasNextPage nodes { id } }"
        }
        val result = graphql(
            """{ order(id: "$orderId") { ${fields.joinToString(" ")} }
                 orderItem(id: "$firstItem") { ${discountFields.joinToString(" ")} } }""",
            user = buyer(userId),
        )
        assertThat(result["errors"]).isNull()
        fun page(node: tools.jackson.databind.JsonNode) = Page(
            (0 until node["nodes"].size()).map { UUID.fromString(node["nodes"][it]["id"].asString()) },
            node["hasNextPage"].asBoolean(), node["totalCount"].asInt(),
        )
        cases.forEachIndexed { n, (first, skip, dir) ->
            assertThat(page(result["data"]["order"]["i$n"])).describedAs("orderItems $first $skip $dir")
                .isEqualTo(inMemory(itemIds, first, skip, dir == "DESC"))
            assertThat(page(result["data"]["orderItem"]["d$n"])).describedAs("discounts $first $skip $dir")
                .isEqualTo(inMemory(discountIds, first, skip, dir == "DESC"))
        }
        // usize arguments are validated by the connection field itself
        val negative = graphql("""{ order(id: "$orderId") { orderItems(first: -1) { totalCount } } }""", user = buyer(userId))
        assertThat(negative["errors"][0]["message"].asString()).isEqualTo("Failed to parse \"Int\": Invalid number")
        val negativeSkip = graphql("""{ orderItem(id: "$firstItem") { discounts(skip: -1) { totalCount } } }""", user = buyer(userId))
        assertThat(negativeSkip["errors"][0]["message"].asString()).isEqualTo("Failed to parse \"Int\": Invalid number")
    }

    @Test
    fun `an order is read from its own table, items and discounts only when selected`() {
        val userId = UUID.randomUUID()
        val itemIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val orderId = insert(userId, itemIds, listOf(UUID.randomUUID()))
        fun tables(statements: List<String>) = statements.map { sql ->
            Regex("""FROM (\w+)""").find(sql)?.groupValues?.get(1) ?: sql
        }

        val orderOnly = SqlStatementLog.record {
            assertThat(graphql("""{ order(id: "$orderId") { id orderStatus compensatableOrderAmount } }""", user = buyer(userId))["errors"]).isNull()
        }
        assertThat(tables(orderOnly)).containsExactly("orders")

        val withItems = SqlStatementLog.record {
            val result = graphql("""{ order(id: "$orderId") { id orderItems { nodes { id count } } } }""", user = buyer(userId))
            assertThat(result["data"]["order"]["orderItems"]["nodes"].size()).isEqualTo(2)
        }
        assertThat(tables(withItems)).containsExactly("orders", "order_items")
        assertThat(withItems[1]).contains("WHERE t.order_id = ").contains("ORDER BY id ASC").doesNotContain("LIMIT")

        // totalCount and hasNextPage are statements of their own (COUNT, probe), the discounts one page per item
        val full = SqlStatementLog.record {
            val result = graphql(
                """{ order(id: "$orderId") { orderItems(first: 1) { totalCount hasNextPage nodes { id discounts { nodes { id } } } } } }""",
                user = buyer(userId),
            )
            assertThat(result["errors"]).isNull()
        }
        assertThat(tables(full)).containsExactlyInAnyOrder("orders", "order_items", "order_items", "order_items", "order_item_discounts")
        assertThat(full.filter { "order_items" in it }).anyMatch { it.startsWith("SELECT COUNT(t.id)") }
            .anyMatch { it.startsWith("SELECT 1 FROM order_items") && it.endsWith("LIMIT 1 OFFSET 1") }

        // orderItem: the item's row, then its order's row for the authorization; the entity resolver only the item
        val item = SqlStatementLog.record {
            assertThat(graphql("""{ orderItem(id: "${itemIds[0]}") { id } }""", user = buyer(userId))["errors"]).isNull()
        }
        assertThat(tables(item)).containsExactly("order_items", "orders")
        val entity = SqlStatementLog.record {
            val result = graphql(
                """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on OrderItem { id } } }""",
                mapOf("r" to listOf(mapOf("__typename" to "OrderItem", "id" to itemIds[0].toString()))),
            )
            assertThat(result["errors"]).isNull()
        }
        assertThat(tables(entity)).containsExactly("order_items")
    }

    @Test
    fun `User orders reads only the statements of the selected connection fields`() {
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
        assertThat(nodes).hasSize(2)
        assertThat(nodes[0]).startsWith("SELECT id FROM users")
        assertThat(nodes[1]).startsWith("SELECT t.* FROM orders t WHERE t.user_id = ").endsWith("ORDER BY id ASC LIMIT 2 OFFSET 0")
        val counts = orders("totalCount hasNextPage")
        assertThat(counts.drop(1)).hasSize(2).noneMatch { it.startsWith("SELECT t.*") }
            .anyMatch { it.startsWith("SELECT COUNT(t.id) FROM orders t") }
            .anyMatch { it.startsWith("SELECT 1 FROM orders t") && it.endsWith("LIMIT 1 OFFSET 2") }
        val withItems = orders("nodes { orderItems { totalCount } }")
        assertThat(withItems.count { it.startsWith("SELECT COUNT(t.id) FROM order_items t") }).isEqualTo(2)
    }

    /** V2 turns the id arrays of V1 into rows (data written by an earlier version of the port). */
    @Test
    fun `migration V2 moves the id arrays into child tables`() {
        val schema = "order_migration_" + UUID.randomUUID().toString().replace("-", "").take(8)
        fun flyway(target: String) = Flyway.configure()
            .dataSource(PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password)
            .schemas(schema).locations("classpath:db/migration").target(target).load()
        flyway("1").migrate()
        val (user, a1, a2, compensation, i1, i2) = List(6) { UUID.randomUUID() }
        DriverManager.getConnection(
            PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.container.username, PostgresTestDatabase.container.password
        ).use { connection ->
            connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                s.execute("INSERT INTO users (id, user_address_ids) VALUES ('$user', ARRAY['$a1', '$a2', '$a1']::uuid[])")
                s.execute(
                    "INSERT INTO order_compensations (id, order_id, order_item_ids, triggered_at, amount_to_compensate) " +
                        "VALUES ('$compensation', '${UUID.randomUUID()}', ARRAY['$i2', '$i1']::uuid[], now(), 5)"
                )
            }
            flyway("latest").migrate()
            connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                val addresses = s.executeQuery("SELECT id FROM user_addresses WHERE user_id = '$user'").use { rs ->
                    buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) }
                }
                assertThat(addresses).containsExactlyInAnyOrder(a1, a2)
                val items = s.executeQuery(
                    "SELECT order_item_id FROM order_compensation_items WHERE order_compensation_id = '$compensation' ORDER BY position"
                ).use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                assertThat(items).containsExactly(i2, i1)
                s.execute("DROP SCHEMA $schema CASCADE")
            }
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
