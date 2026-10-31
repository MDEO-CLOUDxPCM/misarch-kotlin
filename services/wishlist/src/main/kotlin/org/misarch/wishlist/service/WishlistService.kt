package org.misarch.wishlist.service

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.asyncgraphql.RustOffsetDateTime
import org.misarch.common.rust.rustAuthorizeUser
import org.misarch.wishlist.graphql.input.CreateWishlistInput
import org.misarch.wishlist.graphql.input.UpdateWishlistInput
import org.misarch.wishlist.graphql.model.OrderDirection
import org.misarch.wishlist.graphql.model.User
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.graphql.model.WishlistConnection
import org.misarch.wishlist.graphql.model.WishlistOrderField
import org.misarch.wishlist.graphql.model.WishlistOrderInput
import org.misarch.wishlist.persistence.WishlistRepository
import org.misarch.wishlist.persistence.WishlistRow
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Business logic of the wishlist service; one method per resolver / event handler of the Rust original
 * (`graphql/mutation.rs`, `graphql/query.rs`, `graphql/model/user.rs`, `event/http_event_service.rs`), same order of
 * checks and messages.
 */
@Service
class WishlistService(
    private val repository: WishlistRepository,
) {

    /** `user/user/created`. Idempotent (FIXED, docs/fixes/wishlist.md). */
    suspend fun registerUser(id: UUID) = repository.insertUser(id)

    /** `catalog/product-variant/created`. Idempotent (FIXED, docs/fixes/wishlist.md). */
    suspend fun registerProductVariant(id: UUID) = repository.insertProductVariant(id)

    /** `query_object::<User>` (entity resolver, no authorization): the user must be in the local replica. */
    suspend fun user(id: UUID): User {
        if (!repository.userExists(id)) {
            throw GraphQLErrorException(Messages.userNotFound(id))
        }
        return User(id)
    }

    /** `query_object::<Wishlist>`: the wishlist or the not-found error. */
    private suspend fun findWishlist(id: UUID): WishlistRow =
        repository.findWishlist(id) ?: throw GraphQLErrorException(Messages.wishlistNotFound(id))

    /** `Query.wishlist` and the `Wishlist` entity resolver: existence first, then authorization of the owner. */
    suspend fun wishlist(dfe: DataFetchingEnvironment, id: UUID): Wishlist {
        val wishlist = findWishlist(id)
        dfe.rustAuthorizeUser(wishlist.userId)
        return Wishlist.of(wishlist)
    }

    /** `Mutation.createWishlist`. */
    suspend fun createWishlist(dfe: DataFetchingEnvironment, input: CreateWishlistInput): Wishlist {
        dfe.rustAuthorizeUser(input.userId)
        val productVariantIds = input.productVariantIds.distinct() // HashSet<Uuid>
        validateProductVariantIds(productVariantIds)
        if (!repository.userExists(input.userId)) {
            throw GraphQLErrorException(Messages.userNotFound(input.userId))
        }
        val now = RustOffsetDateTime.now()
        val wishlist = WishlistRow(UUID.randomUUID(), input.userId, input.name, now, now)
        dbWrite(Messages.ADD_WISHLIST_FAILED) { repository.insertWishlist(wishlist, productVariantIds) }
        return Wishlist.of(findWishlist(wishlist.id))
    }

    /** `Mutation.updateWishlist`: existence, authorization, variants (validated, then written), name, re-read. */
    suspend fun updateWishlist(dfe: DataFetchingEnvironment, input: UpdateWishlistInput): Wishlist {
        val wishlist = findWishlist(input.id)
        dfe.rustAuthorizeUser(wishlist.userId)
        val now = RustOffsetDateTime.now()
        input.productVariantIds?.let { ids ->
            val productVariantIds = ids.distinct()
            validateProductVariantIds(productVariantIds)
            dbWrite(Messages.updateProductVariantsFailed(input.id)) {
                repository.updateProductVariants(input.id, productVariantIds, now)
            }
        }
        input.name?.let { name ->
            dbWrite(Messages.updateNameFailed(input.id)) { repository.updateName(input.id, name, now) }
        }
        return Wishlist.of(findWishlist(input.id))
    }

    /** `Mutation.deleteWishlist`: existence, authorization, delete. */
    suspend fun deleteWishlist(dfe: DataFetchingEnvironment, id: UUID): Boolean {
        val wishlist = findWishlist(id)
        dfe.rustAuthorizeUser(wishlist.userId)
        dbWrite(Messages.deleteWishlistFailed(id)) { repository.deleteWishlist(id) }
        return true
    }

    /**
     * `User.wishlists` with the semantics of `mongodb-cursor-pagination` 0.3.2 (`PaginatedCursor::find`):
     * default page size 25, `_id DESC` appended as tie-breaker for every order field except `ID`, `hasNextPage` = a
     * further wishlist exists after the page (the library fetched one extra row; here the connection's probe
     * statement). FIXED (Q3, docs/fixes/wishlist.md): the library's `skip > 0` branch (`hasNextPage = rows + skip <
     * total`) returned `first + 1` nodes and `hasNextPage: false` when exactly `first + 1` rows remained; now
     * `hasNextPage` is "the extra row exists" for every `skip`. Arguments and authorization are checked here; page,
     * probe and count run only when their field is selected.
     */
    fun wishlistsOfUser(
        dfe: DataFetchingEnvironment, userId: UUID, first: Int?, skip: Int?, orderBy: WishlistOrderInput?
    ): WishlistConnection {
        Messages.requireUnsigned(first, "first")
        Messages.requireUnsigned(skip, "skip")
        dfe.rustAuthorizeUser(userId)
        val field = orderBy?.field ?: WishlistOrderField.ID
        val direction = if ((orderBy?.direction ?: OrderDirection.ASC) == OrderDirection.ASC) "ASC" else "DESC"
        val order = if (field == WishlistOrderField.ID) "id $direction" else "${field.column} $direction, id DESC"
        return WishlistConnection(repository.wishlistsOfUser(userId, order, first ?: DEFAULT_LIMIT, skip ?: 0))
    }

    /**
     * `validate_product_variant_ids`: every id must be in the product-variant replica; the first unknown one (input
     * order) is reported. FIXED (Q1, docs/fixes/wishlist.md): the original checked only one random id of the set, so
     * unknown variants could be stored.
     */
    private suspend fun validateProductVariantIds(productVariantIds: List<UUID>) {
        if (productVariantIds.isEmpty()) return
        val existing = repository.existingProductVariants(productVariantIds)
        productVariantIds.firstOrNull { it !in existing }?.let { id ->
            throw GraphQLErrorException(Messages.productVariantNotPresent(id))
        }
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

    companion object {
        /** `mongodb-cursor-pagination`'s `DEFAULT_LIMIT` (used when `first` is not given). */
        const val DEFAULT_LIMIT = 25
    }
}
