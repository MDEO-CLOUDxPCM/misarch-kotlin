package org.misarch.common.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.schema.DataFetchingEnvironment
import org.dataloader.DataLoader
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass

/**
 * The authorized user of the request or null (anonymous request).
 */
val DataFetchingEnvironment.authorizedUserOrNull: AuthorizedUser?
    get() = this.graphQlContext.getOrEmpty<AuthorizedUser>(AuthorizedUser::class).orElse(null)

/**
 * The authorized user of the request.
 *
 * @throws IllegalStateException `Unauthorized access` if the request has no `Authorized-User` header
 */
val DataFetchingEnvironment.authorizedUser: AuthorizedUser
    get() = this.authorizedUserOrNull ?: throw IllegalStateException("Unauthorized access")

/**
 * The data loader registered under [name] (by convention the simple class name of the
 * [com.expediagroup.graphql.dataloader.KotlinDataLoader]).
 */
fun <K : Any, V> DataFetchingEnvironment.dataLoader(name: String): DataLoader<K, V> =
    getDataLoader<K, V>(name) ?: throw IllegalStateException("No data loader registered with name $name")

/**
 * The data loader [loader] (registered under its simple class name, the convention of
 * [org.misarch.common.graphql.dataloader.BaseIdDataLoader]).
 */
fun <K : Any, V : Any> DataFetchingEnvironment.dataLoader(loader: KClass<out KotlinDataLoader<K, V>>): DataLoader<K, V> =
    dataLoader(loader.simpleName!!)

/**
 * Loads [key] with the data loader [loader]: `dfe.load(ProductDataLoader::class, productId)`.
 */
fun <K : Any, V : Any> DataFetchingEnvironment.load(loader: KClass<out KotlinDataLoader<K, V>>, key: K): CompletableFuture<V> =
    dataLoader(loader).load(key)
