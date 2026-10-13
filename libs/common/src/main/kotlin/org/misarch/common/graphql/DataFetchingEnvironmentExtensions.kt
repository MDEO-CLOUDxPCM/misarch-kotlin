package org.misarch.common.graphql

import graphql.schema.DataFetchingEnvironment
import org.dataloader.DataLoader

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
