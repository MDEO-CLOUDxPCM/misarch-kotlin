package org.misarch.shoppingcart.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustUuid
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.graphql.model.User
import org.misarch.shoppingcart.service.ShoppingcartService
import org.springframework.stereotype.Component
import java.util.UUID

/** The `id` key of a representation as async-graphql parses it (`UUID` scalar); null -> `Entity not found.` */
internal fun Map<String, Any>.idKey(): UUID? = (this["id"] as? String)?.let(RustUuid::tryParse)

/**
 * Entity resolver for user of specific UUID (`user_entity_resolver`, no authorization).
 */
@Component
class UserResolver(private val shoppingcartService: ShoppingcartService) : FederatedTypeSuspendResolver<User> {
    override val typeName: String = "User"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): User? =
        representation.idKey()?.let { shoppingcartService.user(it) }
}

/**
 * Entity resolver for shopping cart item of specific UUID (`shoppingcart_item_entity_resolver`, no authorization).
 */
@Component
class ShoppingCartItemResolver(
    private val shoppingcartService: ShoppingcartService
) : FederatedTypeSuspendResolver<ShoppingCartItem> {
    override val typeName: String = "ShoppingCartItem"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): ShoppingCartItem? =
        representation.idKey()?.let { ShoppingCartItem.of(shoppingcartService.item(it)) }
}
