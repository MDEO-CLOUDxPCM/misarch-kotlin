package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.model.connection.CategoryCharacteristicConnection
import org.misarch.catalog.graphql.model.connection.CategoryCharacteristicOrder
import org.misarch.catalog.graphql.model.connection.ProductConnection
import org.misarch.catalog.graphql.model.connection.ProductFilter
import org.misarch.catalog.graphql.model.connection.ProductOrder
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.persistence.Condition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("A category")
@KeyDirective(fields = FieldSet("id"))
class Category(
    id: UUID,
    @property:GraphQLDescription("The name of the category.")
    val name: String,
    @property:GraphQLDescription("The description of the category.")
    val description: String,
) : Node(id) {

    @GraphQLDescription("Get all associated products")
    suspend fun products(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ProductOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ProductFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): ProductConnection {
        return ProductConnection(
            first,
            skip,
            filter,
            Condition.eq("j.categoryId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN ProductToCategoryEntity j ON j.productId = t.id"
        )
    }

    @GraphQLDescription("Get characteristics for the category")
    suspend fun characteristics(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CategoryCharacteristicOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): CategoryCharacteristicConnection {
        return CategoryCharacteristicConnection(
            first,
            skip,
            Condition.eq("t.categoryId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
