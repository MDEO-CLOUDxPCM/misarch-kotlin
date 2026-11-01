package org.misarch.discount.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.discount.graphql.model.Coupon
import org.misarch.discount.persistence.model.CouponEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

/**
 * A GraphQL connection for [Coupon]s.
 *
 * The [filter] depends on the authorized user, so it is not passed to [BaseConnection] (whose filters have no user)
 * but combined with the authorization filter in [authorizedUserFilter]; both are evaluated lazily per field.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (alias `j`, main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `Coupon` values.")
@ShareableDirective
class CouponConnection(
    first: Int?,
    skip: Int?,
    private val filter: CouponFilter?,
    predicate: Condition?,
    order: CouponOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<Coupon, CouponEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CouponOrder.DEFAULT).toOrderBy(CouponOrderField.ID),
    entityTemplate,
    CouponEntity::class,
    authorizedUser,
    join
) {


    override fun authorizedUserFilter(): Condition? {
        val authorizedUser = authorizedUser
        val authorizationCondition = if (authorizedUser == null) {
            Condition.raw("FALSE")
        } else if (authorizedUser.isEmployee) {
            null
        } else {
            userHasCouponCondition(authorizedUser.id)
        }
        return Condition.allOf(filter?.toCondition(authorizedUser), authorizationCondition)
    }

}

@GraphQLDescription("Coupon order fields")
enum class CouponOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order coupons by their id")
    ID("id"),

    @GraphQLDescription("Order coupons by the valid from date")
    VALID_FROM("validFrom", "id"),

    @GraphQLDescription("Order coupons by the valid until date")
    VALID_UNTIL("validUntil", "id"),
}

@GraphQLDescription("Coupon order")
class CouponOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: CouponOrderField?
) : Ordering<CouponOrderField> {

    companion object {
        val DEFAULT = CouponOrder(OrderDirection.ASC, CouponOrderField.ID)
    }
}

@GraphQLDescription("Coupon filter")
class CouponFilter(
    @property:GraphQLDescription("Filter weather the user with the provided id own the coupon, other users than the authenticated user require at least EMPLOYEE")
    val userHasCoupon: UUID?
) {

    /**
     * Convert the filter to a SQL condition
     *
     * @param authorizedUser The authorized user
     * @return The condition, or null if the filter is empty
     */
    @GraphQLIgnore
    fun toCondition(authorizedUser: AuthorizedUser?): Condition? {
        return if (userHasCoupon != null) {
            require(authorizedUser != null) {
                "The userHasCoupon filter requires an authorized user"
            }
            if (userHasCoupon != authorizedUser.id) {
                authorizedUser.checkIsEmployee()
            }
            userHasCouponCondition(userHasCoupon)
        } else {
            null
        }
    }
}

/**
 * A function to filter the coupons by the user that owns them.
 *
 * @param userId The user id
 * @return The predicate to filter the coupons by
 */
private fun userHasCouponCondition(userId: UUID): Condition {
    return Condition.raw(
        "t.id IN (SELECT cr.couponId FROM CouponRedemptionEntity cr WHERE cr.userId = ?)", userId
    )
}
