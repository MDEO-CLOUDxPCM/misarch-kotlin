package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.catalog.graphql.model.connection.CategoricalCategoryCharacteristicValueConnection
import org.misarch.catalog.graphql.model.connection.CategoricalCategoryCharacteristicValueOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("A categorical characteristic of a category.")
@KeyDirective(fields = FieldSet("id"))
class CategoricalCategoryCharacteristic(
    id: UUID,
    name: String,
    description: String,
    categoryId: UUID
) : CategoryCharacteristic(id, name, description, categoryId) {

    @GraphQLDescription("Get all associated values")
    suspend fun values(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CategoricalCategoryCharacteristicValueOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate
    ): CategoricalCategoryCharacteristicValueConnection {
        return CategoricalCategoryCharacteristicValueConnection(
            first,
            skip,
            orderBy,
            entityTemplate,
            id
        )
    }

}
