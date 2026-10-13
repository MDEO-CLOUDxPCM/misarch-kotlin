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
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Base class for data loaders which load entities by their id in batches (one `SELECT ... WHERE id IN (...)` per
 * batch). The data loader is registered under its simple class name ([dataLoaderName]).
 * A missing id fails the whole batch with a NullPointerException (like the original services), unless the subclass
 * opts in to [failMissingKeysIndividually]: then only the missing key fails, with `NoSuchElementException()` (no
 * message, i.e. `Exception while fetching data (/x) : null`), and the other keys of the batch resolve normally.
 *
 * @param T type of the GraphQL value
 * @param D type of the entity
 */
abstract class BaseIdDataLoader<T : Any, D : Any> : KotlinDataLoader<UUID, T> {

    override val dataLoaderName: String
        get() = this::class.simpleName!!

    /**
     * `false` (default): a missing id fails the whole batch with a NullPointerException (the originals' defect).
     * `true`: a missing id fails only its own key with `NoSuchElementException()` (opt-in fix, docs/fixes).
     */
    protected open val failMissingKeysIndividually: Boolean = false

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, T> {
        if (failMissingKeysIndividually) {
            return DataLoaderFactory.newDataLoaderWithTry<UUID, T>({ ids, batchLoaderEnvironment ->
                val contextScope = batchLoaderEnvironment.getContext<GraphQLContext>()?.get<CoroutineScope>()
                val coroutineScope = contextScope ?: CoroutineScope(EmptyCoroutineContext)
                coroutineScope.future {
                    val entities = loadAll(ids).associateBy { key(it) }
                    ids.map { id ->
                        val entity = entities[id]
                        if (entity == null) Try.failed<T>(NoSuchElementException()) else Try.succeeded(toDTO(entity))
                    }
                }
            }, DataLoaderOptions.newOptions().setBatchLoaderContextProvider { graphQLContext }.build())
        }
        return DataLoaderFactory.newDataLoader<UUID, T>({ ids, batchLoaderEnvironment ->
            val contextScope = batchLoaderEnvironment.getContext<GraphQLContext>()?.get<CoroutineScope>()
            val coroutineScope = contextScope ?: CoroutineScope(EmptyCoroutineContext)
            coroutineScope.future {
                val entities = loadAll(ids).associateBy { key(it) }
                // `!!`: a missing id fails the batch with a NullPointerException, exactly like the original services
                ids.map { toDTO(entities[it]!!) }
            }
        }, DataLoaderOptions.newOptions().setBatchLoaderContextProvider { graphQLContext }.build())
    }

    /** Loads all entities with the given ids (missing ids are simply absent from the result). */
    abstract suspend fun loadAll(ids: List<UUID>): List<D>

    /** Converts the entity to its GraphQL value. */
    abstract fun toDTO(entity: D): T

    /** The id of the entity. */
    abstract fun key(entity: D): UUID
}
