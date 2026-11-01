package org.misarch.common.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Federation entity resolver of a type with `@key(fields: "id")` that is loaded through a data loader:
 * ```
 * @Component
 * class ProductResolver : DataLoaderEntityResolver<Product>(Product::class, ProductDataLoader::class)
 * ```
 * A representation without `id` resolves to null; a loaded value of another type (e.g. a `VendorAddress` for the
 * `UserAddress` resolver over a common address loader) resolves to null; an unknown id fails like the data loader.
 *
 * @param type the GraphQL type (its simple name is the `__typename`)
 * @param dataLoader the data loader that loads the values by id
 */
abstract class DataLoaderEntityResolver<T : Any>(
    private val type: KClass<T>,
    private val dataLoader: KClass<out KotlinDataLoader<UUID, *>>,
) : FederatedTypePromiseResolver<T> {

    override val typeName: String = type.simpleName!!

    override fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): CompletableFuture<T?> {
        val id = representation["id"] as String? ?: return CompletableFuture.completedFuture(null)
        return environment.dataLoader<UUID, Any?>(dataLoader.simpleName!!)
            .load(UUID.fromString(id))
            .thenApply { type.safeCast(it) }
    }
}

/**
 * Federation entity resolver of a type this service only references (no own data: the value is built from the id):
 * ```
 * @Component
 * class UserResolver : ReferenceEntityResolver<User>(User::class, ::User)
 * ```
 * A representation without `id` resolves to null.
 *
 * @param type the GraphQL type (its simple name is the `__typename`)
 * @param reference builds the value from the id
 */
abstract class ReferenceEntityResolver<T : Any>(
    type: KClass<T>,
    private val reference: (UUID) -> T,
) : FederatedTypePromiseResolver<T> {

    override val typeName: String = type.simpleName!!

    override fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): CompletableFuture<T?> {
        val id = representation["id"] as String?
        return CompletableFuture.completedFuture(id?.let { reference(UUID.fromString(it)) })
    }
}
