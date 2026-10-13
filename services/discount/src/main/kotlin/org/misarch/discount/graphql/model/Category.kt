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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.*

@GraphQLDescription("A category")
@KeyDirective(fields = FieldSet("id"))
class Category(
    id: UUID
) : Node(id) {

    @GraphQLDescription("Get all discounts which apply directly to this category")
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
            Condition.eq("j.categoryId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN DiscountToCategoryEntity j ON j.discountId = t.id"
        )
    }

}
