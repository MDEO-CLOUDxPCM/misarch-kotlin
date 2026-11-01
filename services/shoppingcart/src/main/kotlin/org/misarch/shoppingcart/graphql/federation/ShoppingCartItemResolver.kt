package org.misarch.shoppingcart.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.shoppingcart.graphql.dataloader.ShoppingCartItemDataLoader
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ShoppingCartItem]s
 */
@Component
class ShoppingCartItemResolver :
    DataLoaderEntityResolver<ShoppingCartItem>(ShoppingCartItem::class, ShoppingCartItemDataLoader::class)
