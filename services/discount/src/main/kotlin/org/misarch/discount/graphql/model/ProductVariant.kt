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
import org.misarch.discount.graphql.model.connection.DiscountConnection
import org.misarch.discount.graphql.model.connection.DiscountOrder
import org.misarch.discount.service.DiscountService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.*

@GraphQLDescription("A product variant")
@KeyDirective(fields = FieldSet("id"))
class ProductVariant(
    id: UUID
) : Node(id) {

    @GraphQLDescription("Get all discounts which apply directly to this product variant")
    fun discounts(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: DiscountOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate, dfe: DataFetchingEnvironment
    ): DiscountConnection {
        dfe.authorizedUser.checkIsEmployee()
        return DiscountConnection(
            first,
            skip,
            Condition.eq("j.productVariantId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN DiscountToProductVariantEntity j ON j.discountId = t.id"
        )
    }

    @GraphQLDescription("Get all discounts which apply to this product variant for the authenticated user")
    fun applicableDiscounts(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: DiscountOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        @GraphQLIgnore
        @Autowired
        discountService: DiscountService,
        dfe: DataFetchingEnvironment
    ): DiscountConnection {
        val discountAppliesCondition = discountService.generateFullDiscountAppliesCondition(id, dfe.authorizedUserOrNull)
        return DiscountConnection(
            first, skip, discountAppliesCondition, orderBy, entityTemplate, dfe.authorizedUserOrNull
        )
    }

}
