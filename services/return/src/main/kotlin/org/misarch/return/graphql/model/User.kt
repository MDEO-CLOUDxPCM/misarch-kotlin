package org.misarch.`return`.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.`return`.graphql.model.connection.ReturnConnection
import org.misarch.`return`.graphql.model.connection.ReturnOrder
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
        return ReturnConnection(first, skip, Condition.eq("j.userId", id), orderBy, entityTemplate, dfe.authorizedUser)
    }

}
