package org.misarch.discount.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.persistence.Condition
import org.misarch.discount.graphql.model.connection.CouponConnection
import org.misarch.discount.graphql.model.connection.CouponFilter
import org.misarch.discount.graphql.model.connection.CouponOrder
import org.misarch.discount.graphql.model.connection.DiscountUsageConnection
import org.misarch.discount.graphql.model.connection.DiscountUsageOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("A user.")
@KeyDirective(fields = FieldSet("id"))
class User(
    id: UUID
) : Node(id) {

    @GraphQLDescription("Get all coupons this user has claimed")
    fun coupons(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CouponOrder? = null,
        @GraphQLDescription("Filtering")
        filter: CouponFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): CouponConnection {
        if (dfe.authorizedUser.id != id) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return CouponConnection(
            first,
            skip,
            filter,
            Condition.eq("j.userId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN CouponRedemptionEntity j ON j.couponId = t.id"
        )
    }

    @GraphQLDescription("Get all the discount usages by this user")
    fun discountUsages(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: DiscountUsageOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): DiscountUsageConnection {
        dfe.authorizedUser.checkIsEmployee()
        return DiscountUsageConnection(
            first,
            skip,
            Condition.eq("userId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
