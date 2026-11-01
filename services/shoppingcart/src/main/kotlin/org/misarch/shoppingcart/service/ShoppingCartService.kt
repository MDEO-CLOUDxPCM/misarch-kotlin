package org.misarch.shoppingcart.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.shoppingcart.event.model.OrderDTO
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartInput
import org.misarch.shoppingcart.persistence.model.ShoppingCartItemEntity
import org.misarch.shoppingcart.persistence.model.UserEntity
import org.misarch.shoppingcart.persistence.repository.ShoppingCartItemRepository
import org.misarch.shoppingcart.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Service for shopping carts (a [UserEntity] with its [ShoppingCartItemEntity]s)
 */
@Service
class ShoppingCartService(
    private val userRepository: UserRepository,
    private val itemRepository: ShoppingCartItemRepository,
    private val productVariantService: ProductVariantService,
) {

    /**
     * Replaces all items of a shopping cart (if [UpdateShoppingCartInput.shoppingCartItems] is given; identical
     * items collapse) and sets its `lastUpdatedAt`. The user row is updated first and so locked: concurrent updates
     * of one cart are applied one after the other (last writer wins).
     *
     * @param input the new items of the cart
     * @return the user owning the cart
     */
    @Transactional
    suspend fun updateShoppingCart(input: UpdateShoppingCartInput): UserEntity {
        val itemInputs = input.shoppingCartItems?.distinctBy { it.count to it.productVariantId }
        if (itemInputs != null) {
            itemInputs.forEach { require(it.count >= 0) { "Count must not be negative" } }
            productVariantService.validateProductVariantIds(itemInputs.map { it.productVariantId }.distinct())
            val now = OffsetDateTime.now()
            require(userRepository.updateShoppingcartLastUpdatedAt(input.id, now) != 0L) {
                "User with id ${input.id} does not exist"
            }
            itemRepository.deleteByUserId(input.id)
            itemRepository.saveAll(itemInputs.map {
                ShoppingCartItemEntity(
                    userId = input.id,
                    count = it.count.toLong(),
                    addedAt = now,
                    productVariantId = it.productVariantId,
                    id = null
                )
            }).toList()
        }
        return userRepository.findByIdOrThrow(input.id)
    }

    /**
     * Removes the ordered items from the user's shopping cart (from `order/order/created`); sets the cart's
     * `lastUpdatedAt` if items were removed, so a redelivered event changes nothing
     *
     * @param orderDTO the created order
     */
    @Transactional
    suspend fun removeOrderedItems(orderDTO: OrderDTO) {
        val itemIds = orderDTO.orderItems.map { it.shoppingCartItemId }
        if (itemIds.isEmpty()) {
            return
        }
        if (itemRepository.deleteByUserIdAndIdIn(orderDTO.userId, itemIds.toTypedArray()) > 0) {
            userRepository.updateShoppingcartLastUpdatedAt(orderDTO.userId, OffsetDateTime.now())
        }
    }

}
