package org.misarch.shoppingcart.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.persistence.model.ShoppingCartItemEntity
import org.misarch.shoppingcart.persistence.repository.ShoppingCartItemRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [ShoppingCartItem]s
 */
@Component
class ShoppingCartItemDataLoader(
    repository: ShoppingCartItemRepository
) : IdDataLoader<ShoppingCartItem, ShoppingCartItemEntity>(repository)
