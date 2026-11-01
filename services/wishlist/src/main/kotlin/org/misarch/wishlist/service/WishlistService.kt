package org.misarch.wishlist.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.wishlist.graphql.input.CreateWishlistInput
import org.misarch.wishlist.graphql.input.UpdateWishlistInput
import org.misarch.wishlist.persistence.model.WishlistEntity
import org.misarch.wishlist.persistence.model.WishlistToProductVariantEntity
import org.misarch.wishlist.persistence.repository.UserRepository
import org.misarch.wishlist.persistence.repository.WishlistRepository
import org.misarch.wishlist.persistence.repository.WishlistToProductVariantRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [WishlistEntity]s and their product variants ([WishlistToProductVariantEntity]s). Changes of an
 * existing wishlist lock its row first, so concurrent changes of one wishlist are applied one after the other.
 *
 * @param repository the repository for [WishlistEntity]s
 * @param productVariantRepository the repository for the product variant links
 * @param userRepository the repository for the replicated users
 * @param productVariantService validates product variant ids
 */
@Service
class WishlistService(
    private val repository: WishlistRepository,
    private val productVariantRepository: WishlistToProductVariantRepository,
    private val userRepository: UserRepository,
    private val productVariantService: ProductVariantService,
) {

    /**
     * Creates a wishlist with its product variants (duplicate ids collapse)
     *
     * @param input the wishlist to create
     * @return the created wishlist
     */
    @Transactional
    suspend fun createWishlist(input: CreateWishlistInput): WishlistEntity {
        val productVariantIds = input.productVariantIds.distinct()
        productVariantService.validateProductVariantIds(productVariantIds)
        require(userRepository.existsById(input.userId)) { "User with id ${input.userId} does not exist" }
        val now = OffsetDateTime.now()
        val wishlist = repository.save(
            WishlistEntity(userId = input.userId, name = input.name, createdAt = now, lastUpdatedAt = now, id = null)
        )
        if (productVariantIds.isNotEmpty()) {
            productVariantRepository.createAll(wishlist.id!!, productVariantIds.toTypedArray())
        }
        return wishlist
    }

    /**
     * Replaces the product variants and/or sets the name of a wishlist (also checks permissions); `lastUpdatedAt`
     * changes if anything is given
     *
     * @param input the wishlist and the fields to update
     * @param authorizedUser the user executing the mutation
     * @return the updated wishlist
     */
    @Transactional
    suspend fun updateWishlist(input: UpdateWishlistInput, authorizedUser: AuthorizedUser): WishlistEntity {
        val wishlist = lockOwnWishlist(input.id, authorizedUser)
        if (input.productVariantIds == null && input.name == null) {
            return wishlist
        }
        input.productVariantIds?.distinct()?.let { productVariantIds ->
            productVariantService.validateProductVariantIds(productVariantIds)
            productVariantRepository.deleteByWishlistId(input.id)
            if (productVariantIds.isNotEmpty()) {
                productVariantRepository.createAll(input.id, productVariantIds.toTypedArray())
            }
        }
        input.name?.let { wishlist.name = it }
        wishlist.lastUpdatedAt = OffsetDateTime.now()
        return repository.save(wishlist)
    }

    /**
     * Deletes a wishlist with its product variant links (also checks permissions)
     *
     * @param id the id of the wishlist
     * @param authorizedUser the user executing the mutation
     */
    @Transactional
    suspend fun deleteWishlist(id: UUID, authorizedUser: AuthorizedUser) {
        val wishlist = lockOwnWishlist(id, authorizedUser)
        productVariantRepository.deleteByWishlistId(id)
        repository.delete(wishlist)
    }

    /**
     * The wishlist [id], locked; only its owner, employees and admins may change it
     */
    private suspend fun lockOwnWishlist(id: UUID, authorizedUser: AuthorizedUser): WishlistEntity {
        val wishlist = repository.findByIdForUpdate(id) ?: throw NoSuchElementException()
        if (wishlist.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return wishlist
    }

}
