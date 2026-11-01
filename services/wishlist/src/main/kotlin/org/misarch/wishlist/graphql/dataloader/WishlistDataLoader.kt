package org.misarch.wishlist.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.persistence.model.WishlistEntity
import org.misarch.wishlist.persistence.repository.WishlistRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Wishlist]s
 */
@Component
class WishlistDataLoader(
    repository: WishlistRepository
) : IdDataLoader<Wishlist, WishlistEntity>(repository)
