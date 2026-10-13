package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Describes if order is placed, or yet pending. An order can be rejected during its lifetime.")
enum class OrderStatus {
    @GraphQLDescription("Order is saved a a template, this status can only last for max. 1 hour.")
    PENDING,

    @GraphQLDescription("Order is placed, which means SAGA for payment, fullfill and other validity checks need to be triggered.")
    PLACED,

    @GraphQLDescription("Something went wrong with the order and it was compensated in all relevant serivces.")
    REJECTED;

    /** Rust `{:?}` of the enum variant (used in error messages). */
    val debugName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

@GraphQLDescription("Describes the reason why an order was rejected, in case of rejection: `OrderStatus::Rejected`.")
enum class RejectionReason {
    @GraphQLDescription("The order was rejected due to its invalid content.")
    INVALID_ORDER_DATA,

    @GraphQLDescription("The inventory service was not able to reserve inventory items according to the order.")
    INVENTORY_RESERVATION_FAILED,
}

@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection {
    @GraphQLDescription("Ascending order direction.")
    ASC,

    @GraphQLDescription("Descending order direction.")
    DESC,
}

/**
 * Sort keys of `User.orders`. [sql] is the ORDER BY clause equivalent to the original's Mongo sort document plus the
 * `_id: -1` tiebreak that `mongodb-cursor-pagination` appends when the key is not `_id`. Fields that do not exist in
 * the documents (`name`, `last_updated_at`) and `user._id` (constant under the filter) sort by the tiebreak only.
 */
@GraphQLDescription("Describes the fields that a order can be ordered by.")
enum class OrderOrderField(private val column: String?) {
    @GraphQLDescription("Orders by \"id\".")
    ID("id"),

    @GraphQLDescription("Orders by \"user_id\".")
    USER_ID("user_id"),

    @GraphQLDescription("Orders by \"name\".")
    NAME(null),

    @GraphQLDescription("Orders by \"created_at\".")
    CREATED_AT("created_at"),

    @GraphQLDescription("Orders by \"last_updated_at\".")
    LAST_UPDATED_AT(null);

    /** ORDER BY clause for [direction]. */
    fun sql(direction: OrderDirection): String = when (this) {
        ID -> "id ${direction.name}"
        else -> if (column != null) "$column ${direction.name}, id DESC" else "id DESC"
    }
}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.\n\nOnly the id valid at the moment.")
enum class CommonOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID,
}
