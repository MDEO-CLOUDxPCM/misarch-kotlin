package org.misarch.shoppingcart.service

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.asyncgraphql.RustOffsetDateTime
import org.misarch.common.rust.rustAuthorizeUser
import org.misarch.shoppingcart.graphql.input.CreateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.model.ShoppingCart
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.graphql.model.User
import org.misarch.shoppingcart.persistence.ShoppingCartItemRow
import org.misarch.shoppingcart.persistence.ShoppingcartRepository
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Business logic of the shopping cart service; one method per resolver / event handler of the Rust original
 * (`graphql/mutation.rs`, `graphql/query.rs`, `event/http_event_service.rs`), same order of checks and messages.
 */
@Service
class ShoppingcartService(
    private val repository: ShoppingcartRepository,
) {

    /** `user/user/created`: user with an empty cart, `lastUpdatedAt = now`. Duplicate: no change (fix C3). */
    suspend fun registerUser(id: UUID) = repository.insertUser(id, RustOffsetDateTime.now())

    /** `catalog/product-variant/created`. Duplicate: no change (fix C3). */
    suspend fun registerProductVariant(id: UUID) = repository.insertProductVariant(id)

    /** `order/order/created`: removes the ordered items from the user's cart (and sets `lastUpdatedAt`, fix C4). */
    suspend fun removeOrderedItems(userId: UUID, shoppingCartItemIds: List<UUID>) {
        repository.deleteItemsOfUser(userId, shoppingCartItemIds, RustOffsetDateTime.now())
    }

    /** `query_object::<User>` (entity resolver, no authorization). */
    suspend fun user(id: UUID): User =
        repository.findUser(id)?.let { User(it.id, ShoppingCart(it.id, it.shoppingcartLastUpdatedAt)) }
            ?: throw GraphQLErrorException(Messages.userNotFound(id))

    /** `query_shoppingcart_item_user` + projection: the item (and its owner) or the not-found error. */
    suspend fun item(id: UUID): ShoppingCartItemRow =
        repository.findItem(id) ?: throw GraphQLErrorException(Messages.shoppingCartItemNotFound(id))

    /** `Query.shoppingcartItem`: existence first, then authorization of the owner. */
    suspend fun shoppingcartItem(dfe: DataFetchingEnvironment, id: UUID): ShoppingCartItem {
        val item = item(id)
        dfe.rustAuthorizeUser(item.userId)
        return ShoppingCartItem.of(item)
    }

    /** `Mutation.updateShoppingcart`. */
    suspend fun updateShoppingcart(dfe: DataFetchingEnvironment, input: UpdateShoppingCartInput): ShoppingCart {
        input.shoppingCartItems?.forEach {
            Messages.requireUnsigned(it.count, "input", "ShoppingCartItemInput", "[ShoppingCartItemInput!]", "UpdateShoppingCartInput")
        }
        dfe.rustAuthorizeUser(input.id)
        val itemInputs = input.shoppingCartItems
        if (itemInputs != null) {
            // HashSet<ShoppingCartItemInput>: equal (count, productVariantId) pairs collapse
            val distinct = itemInputs.map { it.count to it.productVariantId }.distinct()
            // fix C2: every product variant is validated (the original checked only one, in random order); the
            // error names the first unknown one in input order
            val productVariantIds = distinct.map { it.second }.distinct()
            val existing = try {
                repository.existingProductVariants(productVariantIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw GraphQLErrorException(Messages.PRODUCT_VARIANTS_LOOKUP_FAILED, cause = e)
            }
            productVariantIds.firstOrNull { it !in existing }?.let {
                throw GraphQLErrorException(Messages.productVariantNotPresent(it))
            }
            if (!repository.userExists(input.id)) {
                throw GraphQLErrorException(Messages.userNotFound(input.id))
            }
            val now = RustOffsetDateTime.now()
            val items = distinct.map { (count, productVariantId) ->
                ShoppingCartItemRow(UUID.randomUUID(), input.id, count.toLong(), now, productVariantId)
            }
            dbWrite(Messages.updateShoppingCartFailed(input.id)) { repository.replaceItems(input.id, items, now) }
        }
        val user = repository.findUser(input.id)
            ?: throw GraphQLErrorException(Messages.shoppingCartNotFound(input.id))
        return ShoppingCart(user.id, user.shoppingcartLastUpdatedAt)
    }

    /**
     * `Mutation.createShoppingcartItem`: "Queries for existing item, otherwise adds new shoppingcart item." Fix C1: an
     * existing item of the user for the same product variant is returned unchanged (the original's lookup always
     * failed because its projection dropped `_id`, so every call appended a new item).
     */
    suspend fun createShoppingcartItem(dfe: DataFetchingEnvironment, input: CreateShoppingCartItemInput): ShoppingCartItem {
        Messages.requireUnsigned(input.shoppingCartItem.count, "input", "ShoppingCartItemInput", "CreateShoppingCartItemInput")
        dfe.rustAuthorizeUser(input.id)
        if (!repository.userExists(input.id)) {
            throw GraphQLErrorException(Messages.userNotFound(input.id))
        }
        val productVariantId = input.shoppingCartItem.productVariantId
        if (!repository.productVariantExists(productVariantId)) {
            throw GraphQLErrorException(Messages.productVariantNotPresent(productVariantId))
        }
        val item = ShoppingCartItemRow(
            UUID.randomUUID(), input.id, input.shoppingCartItem.count.toLong(), RustOffsetDateTime.now(), productVariantId
        )
        var result = item
        dbWrite(Messages.addShoppingCartItemFailed(item.id)) { result = repository.findOrInsertItem(item) }
        return ShoppingCartItem.of(result)
    }

    /** `Mutation.updateShoppingcartItem`: existence, authorization of the owner, update, re-read. */
    suspend fun updateShoppingcartItem(dfe: DataFetchingEnvironment, input: UpdateShoppingCartItemInput): ShoppingCartItem {
        Messages.requireUnsigned(input.count, "input", "UpdateShoppingCartItemInput")
        val item = item(input.id)
        dfe.rustAuthorizeUser(item.userId)
        dbWrite(Messages.updateCountFailed(input.id)) {
            repository.updateItemCount(input.id, input.count.toLong(), RustOffsetDateTime.now())
        }
        return ShoppingCartItem.of(item(input.id))
    }

    /** `Mutation.deleteShoppingcartItem`: existence, authorization of the owner, delete. */
    suspend fun deleteShoppingcartItem(dfe: DataFetchingEnvironment, id: UUID): Boolean {
        val item = item(id)
        dfe.rustAuthorizeUser(item.userId)
        dbWrite(Messages.deleteItemFailed(id)) { repository.deleteItem(id, RustOffsetDateTime.now()) }
        return true
    }

    private suspend fun dbWrite(message: String, write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GraphQLErrorException(message, cause = e)
        }
    }
}
