package org.misarch.shoppingcart.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.shoppingcart.graphql.input.CreateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartItemInput
import org.misarch.shoppingcart.persistence.model.ShoppingCartItemEntity
import org.misarch.shoppingcart.persistence.repository.ShoppingCartItemRepository
import org.misarch.shoppingcart.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [ShoppingCartItemEntity]s. Every change of an item sets the `lastUpdatedAt` of its cart; the user
 * row is locked first.
 */
@Service
class ShoppingCartItemService(
    private val repository: ShoppingCartItemRepository,
    private val userRepository: UserRepository,
    private val productVariantService: ProductVariantService,
) {

    /**
     * Adds an item to a shopping cart, or returns the existing item of the cart for the product variant unchanged
     *
     * @param input the item to add
     * @return the new or existing item
     */
    @Transactional
    suspend fun createShoppingCartItem(input: CreateShoppingCartItemInput): ShoppingCartItemEntity {
        val itemInput = input.shoppingCartItem
        require(itemInput.count >= 0) { "Count must not be negative" }
        requireNotNull(userRepository.lockById(input.id)) { "User with id ${input.id} does not exist" }
        productVariantService.validateProductVariantIds(listOf(itemInput.productVariantId))
        val existing = repository.findFirstByUserIdAndProductVariantIdOrderByAddedAtAscIdAsc(
            input.id, itemInput.productVariantId
        )
        if (existing != null) {
            return existing
        }
        val now = OffsetDateTime.now()
        val item = ShoppingCartItemEntity(
            userId = input.id,
            count = itemInput.count.toLong(),
            addedAt = now,
            productVariantId = itemInput.productVariantId,
            id = null
        )
        val savedItem = repository.save(item)
        userRepository.updateShoppingcartLastUpdatedAt(input.id, now)
        return savedItem
    }

    /**
     * Updates the count of an item (also checks permissions)
     *
     * @param input the item and its new count
     * @param authorizedUser the user executing the mutation
     * @return the updated item
     */
    @Transactional
    suspend fun updateShoppingCartItem(
        input: UpdateShoppingCartItemInput, authorizedUser: AuthorizedUser
    ): ShoppingCartItemEntity {
        require(input.count >= 0) { "Count must not be negative" }
        val item = findOwnItem(input.id, authorizedUser)
        userRepository.updateShoppingcartLastUpdatedAt(item.userId, OffsetDateTime.now())
        item.count = input.count.toLong()
        return repository.save(item)
    }

    /**
     * Deletes an item (also checks permissions)
     *
     * @param id the id of the item
     * @param authorizedUser the user executing the mutation
     */
    @Transactional
    suspend fun deleteShoppingCartItem(id: UUID, authorizedUser: AuthorizedUser) {
        val item = findOwnItem(id, authorizedUser)
        userRepository.updateShoppingcartLastUpdatedAt(item.userId, OffsetDateTime.now())
        repository.delete(item)
    }

    /**
     * The item [id]; only its owner, employees and admins may change it
     */
    private suspend fun findOwnItem(id: UUID, authorizedUser: AuthorizedUser): ShoppingCartItemEntity {
        val item = repository.findByIdOrThrow(id)
        if (item.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return item
    }

}
