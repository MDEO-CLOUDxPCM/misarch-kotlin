package org.misarch.common

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.common.persistence.MisarchNamingStrategy
import org.misarch.common.testing.PostgresTestDatabase
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.PostgresDialect
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext
import org.springframework.data.relational.core.mapping.Table
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

@Table
class ConditionTestEntity(val name: String, val tag: String?, @Id override val id: UUID?) : BaseEntity<String> {
    override fun toDTO(): String = name
}

@Table
class ConditionTestLinkEntity(val testId: UUID, val other: Int, @Id override val id: UUID?) : BaseEntity<Int> {
    override fun toDTO(): Int = other
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionQueriesTest {

    private lateinit var template: R2dbcEntityTemplate
    private lateinit var queries: ConditionQueries
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        val container = PostgresTestDatabase.container
        val connectionFactory = PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(container.host).port(container.getMappedPort(5432)).database(container.databaseName)
                .username(container.username).password(container.password).build()
        )
        val conversions = R2dbcCustomConversions.of(PostgresDialect.INSTANCE)
        val mappingContext = R2dbcMappingContext(MisarchNamingStrategy)
        mappingContext.setSimpleTypeHolder(conversions.simpleTypeHolder)
        val converter = MappingR2dbcConverter(mappingContext, conversions)
        val client = DatabaseClient.create(connectionFactory)
        template = R2dbcEntityTemplate(client, PostgresDialect.INSTANCE, converter)
        queries = ConditionQueries(template)
        for (sql in listOf(
            "DROP TABLE IF EXISTS ConditionTestLinkEntity",
            "DROP TABLE IF EXISTS ConditionTestEntity",
            "CREATE TABLE ConditionTestEntity (id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL, tag VARCHAR(255))",
            "CREATE TABLE ConditionTestLinkEntity (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), testId UUID NOT NULL, other INT NOT NULL)",
        )) {
            client.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
        queries.insert(ConditionTestEntity("alpha", "x", a))
        queries.insert(ConditionTestEntity("beta", "x", b))
        queries.insert(ConditionTestEntity("gamma", null, c))
        for ((id, other) in listOf(a to 1, a to 2, b to 3)) {
            template.insert(ConditionTestLinkEntity(id, other, null)).awaitSingle()
        }
    }

    @Test
    fun `findAll with condition, order, limit and offset`(): Unit = runBlocking {
        val all = queries.findAll<ConditionTestEntity>(null, orderBy = listOf("name DESC"))
        assertThat(all.map { it.name }).containsExactly("gamma", "beta", "alpha")
        val page = queries.findAll<ConditionTestEntity>(
            Condition.eq("tag", "x"), orderBy = listOf("name ASC"), limit = 1, offset = 1
        )
        assertThat(page.map { it.id }).containsExactly(b)
    }

    @Test
    fun `findAll with join and distinct`(): Unit = runBlocking {
        val linked = queries.findAll<ConditionTestEntity>(
            Condition.gt("j.other", 0),
            join = "INNER JOIN ConditionTestLinkEntity j ON j.testId = t.id",
            orderBy = listOf("t.name ASC"),
            distinct = true,
        )
        assertThat(linked.map { it.name }).containsExactly("alpha", "beta")
    }

    @Test
    fun `inArray binds one array parameter`(): Unit = runBlocking {
        val found = queries.findAll<ConditionTestEntity>(Condition.inArray("id", listOf(a, c)), orderBy = listOf("id"))
        assertThat(found.map { it.id }).containsExactly(a, c)
        assertThat(queries.findAll<ConditionTestEntity>(Condition.inArray("id", emptyList<UUID>()))).isEmpty()
    }

    @Test
    fun `count, exists, findFirst`(): Unit = runBlocking {
        assertThat(queries.count<ConditionTestEntity>(Condition.isNull("tag"))).isEqualTo(1)
        assertThat(queries.count<ConditionTestEntity>(null)).isEqualTo(3)
        assertThat(queries.exists<ConditionTestEntity>(Condition.eq("name", "beta"))).isTrue()
        assertThat(queries.exists<ConditionTestEntity>(Condition.eq("name", "delta"))).isFalse()
        assertThat(queries.findFirst<ConditionTestEntity>(Condition.eq("tag", "x"), orderBy = listOf("name DESC"))?.id)
            .isEqualTo(b)
    }

    @Test
    fun `delete by condition`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        queries.insert(ConditionTestEntity("delete-me", "d", id))
        assertThat(queries.delete<ConditionTestEntity>(Condition.eq("t.tag", "d"))).isEqualTo(1)
        assertThat(queries.exists<ConditionTestEntity>(Condition.eq("id", id))).isFalse()
    }

    private enum class OtherField(override vararg val columns: String) : BaseOrderField { OTHER("other") }

    private class OtherOrder(direction: OrderDirection?) : BaseOrder<OtherField>(direction, null)

    @Test
    fun `connection query over distinct values`(): Unit = runBlocking {
        val query = ConnectionQuery(
            template.databaseClient, "ConditionTestEntity", Condition.isNotNull("tag"),
            OtherOrder(OrderDirection.DESC).toOrderBy(OtherField.OTHER).map { it.replace("other", "tag") },
            first = 1, skip = 0, selection = "tag", distinct = true,
        ) { row, _ -> row.get(0, String::class.java)!! }
        assertThat(query.nodes()).containsExactly("x")
        assertThat(query.totalCount()).isEqualTo(1)
        assertThat(query.hasNextPage()).isFalse()
    }

    @Test
    fun `order with tie-break`() {
        assertThat(OtherOrder(OrderDirection.ASC).toOrderBy(OtherField.OTHER, tieBreak = listOf("id DESC")))
            .containsExactly("other ASC", "id DESC")
        assertThat(OtherOrder(null).toOrderBy(OtherField.OTHER, tieBreak = listOf("other DESC")))
            .containsExactly("other ASC")
    }
}
