package org.misarch.shoppingcart.service

import org.misarch.common.rust.asyncgraphql.RustArgumentParseException
import java.util.UUID

/**
 * Error messages of the Rust original, verbatim (including the Rust type paths that `std::any::type_name` puts into
 * `query_object`'s messages and the "MongoDB" in database error messages).
 */
object Messages {

    private const val CRATE = "misarch_shoppingcart"

    fun userNotFound(id: UUID) = "$CRATE::graphql::model::user::User with UUID: `$id` not found."

    fun productVariantNotPresent(id: UUID) = "Product variant with the UUID: `$id` is not present in the system."

    fun shoppingCartNotFound(id: UUID) = "ShoppingCart with UUID: `$id` not found."

    fun shoppingCartItemNotFound(id: UUID) = "ShoppingCartItem of UUID: `$id` not found."

    fun updateShoppingCartFailed(id: UUID) =
        "Updating product_variant_ids of shoppingcart of id: `$id` failed in MongoDB."

    fun addShoppingCartItemFailed(itemId: UUID) = "Add shoppingcart item of id: `$itemId` failed in MongoDB."

    fun updateCountFailed(id: UUID) = "Updating count of shoppingcart item of id: `$id` failed in MongoDB."

    fun deleteItemFailed(id: UUID) = "Deleting shoppingcart item of id: `$id` failed in MongoDB."

    const val PRODUCT_VARIANTS_LOOKUP_FAILED = "Product variants with the specified UUIDs are not present in the system."

    /** async-graphql's message for a negative value of an unsigned (`u32`/`usize`) `Int` argument. */
    private const val INVALID_NUMBER = "Failed to parse \"Int\": Invalid number"

    /**
     * Rejects a negative `Int` like async-graphql does for the original's unsigned argument types; [parsedIn] are the
     * enclosing input types, innermost first (async-graphql appends `(occurred while parsing "<type>")` for each);
     * the error is located at the value of the field argument [argument].
     */
    fun requireUnsigned(value: Int?, argument: String, vararg parsedIn: String) {
        if (value != null && value < 0) {
            throw RustArgumentParseException(
                INVALID_NUMBER + parsedIn.joinToString("") { " (occurred while parsing \"$it\")" }, argument
            )
        }
    }
}
