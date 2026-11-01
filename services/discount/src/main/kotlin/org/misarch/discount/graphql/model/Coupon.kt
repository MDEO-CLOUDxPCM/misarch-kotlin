package org.misarch.discount.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.connection.CommonOrder
import org.misarch.common.graphql.load
import org.misarch.common.persistence.Condition
import org.misarch.discount.graphql.dataloader.DiscountDataLoader
import org.misarch.discount.graphql.model.connection.UserConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A coupon")
@KeyDirective(fields = FieldSet("id"))
class Coupon(
    id: UUID,
    private val usages: Int,
    private val maxUsages: Int?,
    @property:GraphQLDescription("The date and time until which the coupon is valid.")
    val validUntil: OffsetDateTime,
    @property:GraphQLDescription("The date and time from which the coupon is valid.")
    val validFrom: OffsetDateTime,
    @property:GraphQLDescription("The code of the coupon.")
    val code: String,
    private val discountId: UUID
) : Node(id) {

    @GraphQLDescription("The number of times the coupon has been used.")
    fun usages(dfe: DataFetchingEnvironment): Int {
        dfe.authorizedUser.checkIsEmployee()
        return usages
    }

    @GraphQLDescription("The maximum number of times the coupon can be used.")
    fun maxUsages(dfe: DataFetchingEnvironment): Int? {
        dfe.authorizedUser.checkIsEmployee()
        return maxUsages
    }

    @GraphQLDescription("The discount granted by this coupon.")
    fun discount(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Discount> {
        return dfe.load(DiscountDataLoader::class, discountId)
    }

    @GraphQLDescription("Get all users who claimed this coupon.")
    fun users(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): UserConnection {
        dfe.authorizedUser.checkIsEmployee()
        return UserConnection(
            first,
            skip,
            Condition.eq("j.couponId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN CouponRedemptionEntity j ON j.userId = t.id"
        )
    }

}
