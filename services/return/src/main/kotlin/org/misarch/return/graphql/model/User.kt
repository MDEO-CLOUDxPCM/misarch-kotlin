package org.misarch.`return`.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.`return`.graphql.model.connection.ReturnConnection
import org.misarch.`return`.graphql.model.connection.ReturnOrder
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("A user.")
@KeyDirective(fields = FieldSet("id"))
class User(
    id: UUID
) : Node(id) {

    @GraphQLDescription("Get all returns of this user")
    suspend fun returns(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ReturnOrder? = null,
        dfe: DataFetchingEnvironment,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate
    ): ReturnConnection {
        // the header is required (fix: `Unauthorized access` instead of the original's NullPointerException);
        // the connection's authorizedUserFilter restricts non-employees to their own returns
        return ReturnConnection(
            first, skip, Condition.eq("${ReturnConnection.ORDER_ALIAS}.userId", id), orderBy, entityTemplate,
            dfe.authorizedUser
        )
    }

}
