package org.misarch.discount.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.persistence.Condition
import org.misarch.discount.graphql.model.connection.CategoryConnection
import org.misarch.discount.graphql.model.connection.CommonOrder
import org.misarch.discount.graphql.model.connection.CouponConnection
import org.misarch.discount.graphql.model.connection.CouponFilter
import org.misarch.discount.graphql.model.connection.CouponOrder
import org.misarch.discount.graphql.model.connection.DiscountUsageConnection
import org.misarch.discount.graphql.model.connection.DiscountUsageOrder
import org.misarch.discount.graphql.model.connection.ProductConnection
import org.misarch.discount.graphql.model.connection.ProductVariantConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription(
    """
    A discount.
    Multiple discounts are applied multiplicatively:
    discounted = original * (1 - discount1) * (1 - discount2) * ...
    """
)
@KeyDirective(fields = FieldSet("id"))
class Discount(
    id: UUID,
    @property:GraphQLDescription(
        """
        The discount applied to the order item, e.g. 0.2 meaning a 20% reduction in price.
        Multiple discounts are applied multiplicatively:
        discounted = original * (1 - discount1) * (1 - discount2) * ...
        """
    )
    val discount: Double,
    @property:GraphQLDescription("The maximum number of times a user can use this discount in bought ProductItems.")
    val maxUsagesPerUser: Int?,
    @property:GraphQLDescription("The date and time until which the discount is valid.")
    val validUntil: OffsetDateTime,
    @property:GraphQLDescription("The date and time from which the discount is valid.")
    val validFrom: OffsetDateTime,
    @property:GraphQLDescription("The minimum order amount required to use this discount.")
    val minOrderAmount: Int?
) : Node(id) {

    @GraphQLDescription("Get all categories to which this discount directly applies")
    fun discountAppliesToCategories(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): CategoryConnection {
        dfe.authorizedUser.checkIsEmployee()
        return CategoryConnection(
            first,
            skip,
            Condition.eq("j.discountId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN DiscountToCategoryEntity j ON j.categoryId = t.id"
        )
    }

    @GraphQLDescription("Get all products to which this discount directly applies")
    fun discountAppliesToProducts(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): ProductConnection {
        dfe.authorizedUser.checkIsEmployee()
        return ProductConnection(
            first,
            skip,
            Condition.eq("j.discountId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN DiscountToProductEntity j ON j.productId = t.id"
        )
    }

    @GraphQLDescription("Get all product variants to which this discount directly applies")
    fun discountAppliesToProductVariants(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): ProductVariantConnection {
        dfe.authorizedUser.checkIsEmployee()
        return ProductVariantConnection(
            first,
            skip,
            Condition.eq("j.discountId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN DiscountToProductVariantEntity j ON j.productVariantId = t.id"
        )
    }

    @GraphQLDescription("Get all product variants to which this discount directly applies")
    fun discountRequiresCoupon(
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
        return CouponConnection(
            first,
            skip,
            filter,
            Condition.eq("discountId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

    @GraphQLDescription("Get all the usages of this discount by all users.")
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
            Condition.eq("discountId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
