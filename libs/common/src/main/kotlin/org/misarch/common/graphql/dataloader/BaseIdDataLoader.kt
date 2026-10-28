package org.misarch.common.graphql.dataloader

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.extensions.get
import graphql.GraphQLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.dataloader.Try
import org.misarch.common.timing.TimingSpans
import org.misarch.common.timing.counters.CpuCounters
import org.springframework.core.ResolvableType
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Base class for data loaders which load entities by their id in batches (one `SELECT ... WHERE id IN (...)` per
 * batch). The data loader is registered under its simple class name ([dataLoaderName]).
 * A missing id fails only its own key, with `NoSuchElementException()` (no message, i.e.
 * `Exception while fetching data (/x) : null`); the other keys of the batch resolve normally.
 *
 * @param T type of the GraphQL value
 * @param D type of the entity
 */
abstract class BaseIdDataLoader<T : Any, D : Any> : KotlinDataLoader<UUID, T> {

    override val dataLoaderName: String
        get() = this::class.simpleName!!

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, T> =
        DataLoaderFactory.newDataLoaderWithTry<UUID, T>({ ids, batchLoaderEnvironment ->
            val contextScope = batchLoaderEnvironment.getContext<GraphQLContext>()?.get<CoroutineScope>()
            val coroutineScope = contextScope ?: CoroutineScope(EmptyCoroutineContext)
            launch(coroutineScope) {
                val entities = timedLoadAll(ids).associateBy { key(it) }
                ids.map { id ->
                    val entity = entities[id]
                    if (entity == null) Try.failed<T>(NoSuchElementException()) else Try.succeeded(toDTO(entity))
                }
            }
        }, DataLoaderOptions.newOptions().setBatchLoaderContextProvider { graphQLContext }.build())

    /**
     * `scope.future(block)`; with the timing counters on ([CpuCounters]) the batch is the operation
     * `dataloader <dataLoaderName>` (its coroutine is bound to it, see `RequestCpuElement`).
     */
    private fun <R> launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> R): CompletableFuture<R> {
        if (!CpuCounters.enabled) return scope.future(block = block)
        val op = CpuCounters.operation("dataloader", dataLoaderName)
        op.calls.increment()
        return CpuCounters.explicit(op) { scope.future(block = block) }
    }

    /** [loadAll] in a timing span `dataloader <Entity>` ([TimingSpans]; while off just [loadAll]). */
    private suspend fun timedLoadAll(ids: List<UUID>): List<D> = TimingSpans.span(
        { "dataloader $entityName" },
        {
            it.setAttribute(TimingSpans.ENTITY, entityName)
                .setAttribute(TimingSpans.ACCESS, TimingSpans.READ)
                .setAttribute(TimingSpans.BATCH_SIZE, ids.size.toLong())
        },
        { entities -> entities.size.toLong() },
    ) { loadAll(ids) }

    /** Simple name of the entity type `D` (for the timing spans). */
    private val entityName: String by lazy {
        ResolvableType.forClass(this::class.java).`as`(BaseIdDataLoader::class.java).getGeneric(1).resolve()?.simpleName
            ?: "unknown"
    }

    /** Loads all entities with the given ids (missing ids are simply absent from the result). */
    abstract suspend fun loadAll(ids: List<UUID>): List<D>

    /** Converts the entity to its GraphQL value. */
    abstract fun toDTO(entity: D): T

    /** The id of the entity. */
    abstract fun key(entity: D): UUID
}
