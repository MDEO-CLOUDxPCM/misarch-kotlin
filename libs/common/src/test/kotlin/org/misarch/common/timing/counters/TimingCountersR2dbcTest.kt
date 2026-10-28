package org.misarch.common.timing.counters

import graphql.GraphQLContext
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.common.persistence.Condition
import org.misarch.common.testing.PostgresTestDatabase
import org.misarch.common.timing.TimingItemEntity
import org.misarch.common.timing.TimingItemRepository
import org.misarch.common.timing.TimingRepositoryTestConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID

/** Statement counters against Postgres (Testcontainers): same results off and on, keys, modes, entities, rows, CPU. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimingCountersR2dbcTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                R2dbcAutoConfiguration::class.java, DataR2dbcAutoConfiguration::class.java,
                R2dbcTransactionManagerAutoConfiguration::class.java,
            )
        )
        .withUserConfiguration(TimingRepositoryTestConfiguration::class.java, TimingCountersConfiguration::class.java)
        .withPropertyValues(
            "spring.r2dbc.url=${PostgresTestDatabase.r2dbcUrl}",
            "spring.r2dbc.username=postgres",
            "spring.r2dbc.password=postgres",
        )

    @BeforeAll
    fun createTable() {
        runner.run { context ->
            runBlocking {
                context.getBean(R2dbcEntityTemplate::class.java).databaseClient
                    .sql("CREATE TABLE IF NOT EXISTS timing_item (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), name TEXT NOT NULL)")
                    .then().awaitSingleOrNull()
            }
        }
    }

    @AfterEach
    fun off() = CpuCounters.disable()

    private class Connection(name: String, template: R2dbcEntityTemplate) :
        BaseConnection<String, TimingItemEntity>(
            5, 0, null, Condition.eq("name", name), listOf("id"), template, TimingItemEntity::class, null as AuthorizedUser?,
        )

    private class Loader(repository: TimingItemRepository) : IdDataLoader<String, TimingItemEntity>(repository)

    private fun calls(context: ApplicationContext): String = runBlocking {
        val name = "c-" + UUID.randomUUID()
        val repository = context.getBean(TimingItemRepository::class.java)
        val template = context.getBean(R2dbcEntityTemplate::class.java)
        val id = repository.save(TimingItemEntity(null, name)).id!!
        repository.save(TimingItemEntity(null, name))
        val found = repository.findById(id)?.name
        val missing = repository.findById(UUID.randomUUID())
        val renamed = repository.rename(id, "$name-2")
        // a store-style query with its own row mapping (DatabaseClient)
        val manual = template.databaseClient.sql("SELECT name FROM timing_item WHERE name = :n").bind("n", name)
            .map { row -> row.get("name", String::class.java)!! }.all().collectList().awaitSingle()
        val page = Connection(name, template).let { listOf(it.nodes(), it.totalCount()) }
        val loader = Loader(repository).getDataLoader(GraphQLContext.newContext().build())
        val loaded = loader.load(id)
        loader.dispatch()
        val transaction = TransactionalOperator.create(context.getBean(ReactiveTransactionManager::class.java))
        runCatching { transaction.executeAndAwait { repository.save(TimingItemEntity(null, "$name-tx")); error("rollback") } }
        val rolledBack = repository.findByName("$name-tx").toList().isEmpty()
        listOf(found, missing, renamed, manual, page, loaded.get(), rolledBack).toString().replace(name, "N")
    }

    @Suppress("UNCHECKED_CAST")
    private fun statements(): List<Map<String, Any>> = CpuCounters.snapshot()["statements"] as List<Map<String, Any>>

    @Test
    fun `off - no wrapping, on - same results and per-statement counters`() {
        var off = ""
        runner.withPropertyValues("$COUNTERS_PROPERTY=false").run {
            assertFalse(it.getBean(ConnectionFactory::class.java) is CountingConnectionFactory)
            off = calls(it)
        }
        assertEquals("[N, null, 1, [N], [[N], 1], N-2, true]", off)
        CpuCounters.reset()
        var on = ""
        runner.withPropertyValues("$COUNTERS_PROPERTY=true").run {
            assertTrue(it.getBean(ConnectionFactory::class.java) is CountingConnectionFactory)
            assertTrue(CpuCounters.enabled)
            on = calls(it)
        }
        assertEquals(off, on)

        val st = statements()
        fun one(predicate: (String) -> Boolean) = st.single { predicate(it["sql"] as String) }
        val insert = one { it.startsWith("INSERT INTO \"timing_item\"") }
        assertEquals("write", insert["mode"]); assertEquals("TimingItemEntity", insert["entity"])
        assertEquals(3L, insert["calls"])                         // two saves + the rolled-back one
        assertTrue((insert["mappingCpuNs"] as Long) > 0)          // entity -> parameters (converter)
        assertTrue((insert["driverCpuNs"] as Long) > 0)
        val byId = one { it.startsWith("SELECT") && it.contains("timing_item") && it.contains("LIMIT") && !it.contains("ORDER") }
        assertEquals("read", byId["mode"]); assertEquals(2L, byId["calls"]); assertEquals(1L, byId["rows"])
        assertEquals("TimingItemEntity", byId["entity"])
        assertTrue((byId["mappingCpuNs"] as Long) > 0)
        val rename = one { it.startsWith("UPDATE timing_item SET name = ?") }
        assertEquals("write", rename["mode"]); assertEquals(1L, rename["rows"]); assertEquals("timing_item", rename["entity"])
        val manual = one { it == "SELECT name FROM timing_item WHERE name = ?" }
        assertEquals(1L, manual["rows"]); assertEquals("timing_item", manual["entity"]) // String rows: the table
        val count = one { it.startsWith("SELECT COUNT(") }
        assertEquals("timing_item", count["entity"]); assertEquals(1L, count["rows"])
        assertTrue(st.all { (it["key"] as String).startsWith("${it["mode"]} ${it["entity"]} ") })

        @Suppress("UNCHECKED_CAST")
        val ops = CpuCounters.snapshot()["operations"] as List<Map<String, Any>>
        assertEquals(1L, ops.single { it["key"] == "Loader" }["calls"])
        assertEquals("dataloader", ops.single { it["key"] == "Loader" }["kind"])
    }
}
