package org.misarch.wishlist.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustUuid
import org.misarch.wishlist.graphql.model.User
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.service.WishlistService
import org.springframework.stereotype.Component
import java.util.UUID

/** The `id` key of a representation as async-graphql parses it (`UUID` scalar); null -> `Entity not found.` */
internal fun Map<String, Any>.idKey(): UUID? = (this["id"] as? String)?.let(RustUuid::tryParse)

/**
 * Entity resolver for user of specific UUID (`user_entity_resolver`: no authorization, user must be replicated).
 */
@Component
class UserResolver(private val wishlistService: WishlistService) : FederatedTypeSuspendResolver<User> {
    override val typeName: String = "User"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): User? =
        representation.idKey()?.let { wishlistService.user(it) }
}

/**
 * Entity resolver for wishlist of specific UUID (`wishlist_entity_resolver`, authorizes the owner).
 */
@Component
class WishlistResolver(private val wishlistService: WishlistService) : FederatedTypeSuspendResolver<Wishlist> {
    override val typeName: String = "Wishlist"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Wishlist? =
        representation.idKey()?.let { wishlistService.wishlist(environment, it) }
}
