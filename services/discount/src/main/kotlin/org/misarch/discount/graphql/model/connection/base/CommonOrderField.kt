package org.misarch.discount.graphql.model.connection.base

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderField

@GraphQLDescription("Common order fields")
enum class CommonOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order entities by their id")
    ID("id"),
}
