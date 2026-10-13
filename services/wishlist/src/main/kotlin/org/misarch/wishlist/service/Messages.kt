package org.misarch.wishlist.service

import org.misarch.common.rust.asyncgraphql.RustArgumentParseException
import java.util.UUID

/**
 * Error messages of the Rust original, verbatim (including the Rust type paths that `std::any::type_name` puts into
 * `query_object`'s messages and the "MongoDB" in database error messages).
 */
object Messages {

    private const val CRATE = "misarch_wishlist"

    fun wishlistNotFound(id: UUID) = "$CRATE::graphql::model::wishlist::Wishlist with UUID: `$id` not found."

    fun userNotFound(id: UUID) = "$CRATE::graphql::model::user::User with UUID: `$id` not found."

    fun productVariantNotPresent(id: UUID) = "Product variant with the UUID: `$id` is not present in the system."

    const val ADD_WISHLIST_FAILED = "Adding wishlist failed in MongoDB."

    fun updateProductVariantsFailed(id: UUID) = "Updating product_variant_ids of wishlist of id: `$id` failed in MongoDB."

    fun updateNameFailed(id: UUID) = "Updating name of wishlist of id: `$id` failed in MongoDB."

    fun deleteWishlistFailed(id: UUID) = "Deleting wishlist of id: `$id` failed in MongoDB."

    const val RETRIEVE_WISHLISTS_FAILED = "Retrieving wishlists failed in MongoDB."

    /** async-graphql's message for a negative value of an unsigned (`u32`/`u64`/`usize`) `Int` argument. */
    private const val INVALID_NUMBER = "Failed to parse \"Int\": Invalid number"

    /**
     * Rejects a negative `Int` like async-graphql does for the original's unsigned argument types; the error is
     * located at the value of the field argument [argument].
     */
    fun requireUnsigned(value: Int?, argument: String) {
        if (value != null && value < 0) {
            throw RustArgumentParseException(INVALID_NUMBER, argument)
        }
    }
}
