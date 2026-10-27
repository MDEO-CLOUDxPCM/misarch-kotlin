package org.misarch.common.timing

import graphql.GraphQLContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.common.persistence.BaseEntity
import org.misarch.common.testing.PostgresTestDatabase
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID
import java.util.function.Supplier

@Table("timing_item")
data class TimingItemEntity(@Id override val id: UUID?, val name: String) : BaseEntity<String> {
    override fun toDTO(): String = name
}

interface TimingItemRepository : CoroutineCrudRepository<TimingItemEntity, UUID> {

    @Modifying
    @Query("UPDATE timing_item SET name = :name WHERE id = :id")
    suspend fun rename(id: UUID, name: String): Int

    @Modifying
    @Query("UPDATE timing_item SET name = name WHERE id = :id")
    suspend fun touch(id: UUID)

    @Query("SELECT * FROM timing_item WHERE name = :name")
    fun findByName(name: String): Flow<TimingItemEntity>

    @Query("SELECT * FROM no_such_table")
    suspend fun broken(): TimingItemEntity?
}

@Configuration(proxyBeanMethods = false)
@EnableR2dbcRepositories(basePackageClasses = [TimingItemRepository::class])
class TimingRepositoryTestConfiguration

/**
 * Repository proxy spans, `connection` spans and the repository span inside a data loader span, against Postgres
 * (Testcontainers): the same calls with the property off (no spans) and on (spans), same results.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimingRepositorySpansTest {

    private val tracing = TestTracing()

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                R2dbcAutoConfiguration::class.java, DataR2dbcAutoConfiguration::class.java,
                R2dbcTransactionManagerAutoConfiguration::class.java,
            )
        )
        .withUserConfiguration(TimingRepositoryTestConfiguration::class.java, TimingSpansConfiguration::class.java)
        .withBean(OpenTelemetry::class.java, Supplier { tracing.openTelemetry })
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

    @AfterAll
    fun tearDown() = tracing.close()

    private class Connection(first: Int?, skip: Int?, name: String, template: R2dbcEntityTemplate) :
        BaseConnection<String, TimingItemEntity>(
            first, skip, null, org.misarch.common.persistence.Condition.eq("name", name), listOf("id"), template,
            TimingItemEntity::class, null as AuthorizedUser?,
        )

    private class Loader(repository: TimingItemRepository) : IdDataLoader<String, TimingItemEntity>(repository)

    /** The calls of the test; returns their results (rendered, [name] replaced): equal with the spans off and on. */
    private fun calls(context: ApplicationContext): String = runBlocking {
        val name = "item-" + UUID.randomUUID()
        val repository = context.getBean(TimingItemRepository::class.java)
        val thread = Thread.currentThread()
        val saved = repository.save(TimingItemEntity(null, name))
        val sameThreadAfterResume = Thread.currentThread() == thread
        val id = saved.id!!
        val found = repository.findById(id)
        val missing = repository.findById(UUID.randomUUID())
        val renamed = repository.rename(id, "$name-2")
        repository.touch(id)
        repository.save(TimingItemEntity(null, "$name-2"))
        val byName = repository.findByName("$name-2").toList().map { it.name }
        val first = repository.findAll().first().id != null
        val broken = runCatching { repository.broken() }.exceptionOrNull()?.javaClass?.name
        val connection = Connection(1, 0, "$name-2", context.getBean(R2dbcEntityTemplate::class.java))
        val page = listOf(connection.nodes(), connection.totalCount(), connection.hasNextPage())
        val loader = Loader(repository).getDataLoader(GraphQLContext.newContext().build())
        val loaded = loader.load(id)
        loader.dispatch()
        // the transaction (Reactor context) must reach the repository: the insert is rolled back
        val transaction = TransactionalOperator.create(context.getBean(ReactiveTransactionManager::class.java))
        runCatching { transaction.executeAndAwait { repository.save(TimingItemEntity(null, "$name-tx")); error("rollback") } }
        val rolledBack = repository.findByName("$name-tx").toList().isEmpty()
        listOf(sameThreadAfterResume, found?.name, missing, renamed, byName, first, broken, page, loaded.get(), rolledBack)
            .toString().replace(name, "N")
    }

    @Test
    fun `off - no spans, on - repo, connection and dataloader spans with the same results`() {
        tracing.exporter.reset()
        var off = ""
        runner.run { off = calls(it) }
        assertTrue(tracing.spans.isEmpty())
        var on = ""
        runner.withPropertyValues("${TimingSpans.PROPERTY}=true").run { on = calls(it) }
        assertEquals("[true, N, null, 1, [N-2, N-2], true, org.springframework.r2dbc.BadSqlGrammarException, [[N-2], 2, true], N-2, true]", off)
        assertEquals(off, on)

        val spans = tracing.timingSpans()
        fun repo(method: String) = spans.filter { it.name == "repo TimingItemEntity.$method" }
        repo("save").forEach {
            assertEquals(TimingSpans.WRITE, it.attributes.get(TimingSpans.ACCESS))
            assertEquals(1L, it.attributes.get(TimingSpans.ROWS))
            assertEquals("TimingItemEntity", it.attributes.get(TimingSpans.ENTITY))
        }
        assertEquals(3, repo("save").size)
        assertEquals(listOf(1L, 0L), repo("findById").map { it.attributes.get(TimingSpans.ROWS) })
        assertEquals(TimingSpans.READ, repo("findById").first().attributes.get(TimingSpans.ACCESS))
        assertEquals(TimingSpans.WRITE, repo("rename").single().attributes.get(TimingSpans.ACCESS))
        assertEquals(1L, repo("rename").single().attributes.get(TimingSpans.ROWS))
        // a write without update count: rows unknown
        assertEquals(TimingSpans.WRITE, repo("touch").single().attributes.get(TimingSpans.ACCESS))
        assertEquals(null, repo("touch").single().attributes.get(TimingSpans.ROWS))
        assertEquals(listOf(2L, 0L), repo("findByName").map { it.attributes.get(TimingSpans.ROWS) })
        assertEquals(true, repo("findAll").single().attributes.get(TimingSpans.CANCELLED)) // first() cancels the flow
        assertEquals(StatusCode.ERROR, repo("broken").single().status.statusCode)

        val connection = spans.filter { it.name == "connection TimingItemEntity" }
        assertEquals(listOf("nodes", "count", "hasNextPage"), connection.map { it.attributes.get(TimingSpans.QUERY) })
        assertEquals(listOf(1L, 1L, 1L), connection.map { it.attributes.get(TimingSpans.ROWS) })

        val dataLoader = tracing.span("dataloader TimingItemEntity")
        assertEquals(1L, dataLoader.attributes.get(TimingSpans.BATCH_SIZE))
        assertEquals(1L, dataLoader.attributes.get(TimingSpans.ROWS))
        assertEquals(dataLoader.spanId, repo("findAllById").single().parentSpanId)
    }

    @Test
    fun `on - the repository span is a child of the caller's span, the caller's context is restored after resume`() {
        runner.withPropertyValues("${TimingSpans.PROPERTY}=true").run { context ->
            tracing.exporter.reset()
            val repository = context.getBean(TimingItemRepository::class.java)
            val parent = tracing.tracer.spanBuilder("caller").startSpan()
            runBlocking(Context.current().with(parent).asContextElement()) {
                repository.count()
                assertSame(parent, Span.current())
                withContext(Context.root().asContextElement()) { assertNotNull(repository.count()) }
            }
            parent.end()
            val counts = tracing.timingSpans().filter { it.name == "repo TimingItemEntity.count" }
            assertEquals(parent.spanContext.spanId, counts[0].parentSpanId)
            assertEquals(1L, counts[0].attributes.get(TimingSpans.ROWS))
            assertTrue(!counts[1].parentSpanContext.isValid)
        }
    }
}
