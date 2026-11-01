package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.order.graphql.model.connection.OrderConnection
import org.misarch.order.graphql.model.connection.OrderOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("Type of a user owning orders.")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("UUID of user.")
    val id: UUID
) {

    @GraphQLDescription("Retrieves orders of user.")
    fun orders(
        @GraphQLDescription("Describes that the `first` N orders should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many orders should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which orders are retrieved.")
        orderBy: OrderOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): OrderConnection {
        if (dfe.authorizedUser.id != id) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return OrderConnection(first ?: DEFAULT_PAGE_SIZE, skip, Condition.eq("userId", id), orderBy, entityTemplate)
    }

    companion object {
        /** The number of orders returned if `first` is not given. */
        const val DEFAULT_PAGE_SIZE = 25
    }

}
