package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/**
 * Order of connections whose entities can only be ordered by id
 *
 * @property direction the direction (default ascending)
 * @property field the field (only the id)
 */
@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrderInput(
    @property:GraphQLDescription("Order direction of orders.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that orders should be ordered by.")
    val field: CommonOrderField? = null
) {

    /**
     * SQL order items (without the keyword)
     *
     * @param idColumn the id column of the ordered entity
     */
    fun toOrderBy(idColumn: String): List<String> = listOf("$idColumn ${(direction ?: OrderDirection.ASC).name}")

}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.\n\nOnly the id valid at the moment.")
enum class CommonOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID
}
