package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.persistence.Condition
import org.misarch.review.graphql.model.connection.ReviewConnection
import org.misarch.review.graphql.model.connection.ReviewOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("Type of a user owning reviews.")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("UUID of the user.")
    val id: UUID,
) {

    @GraphQLDescription("Retrieves reviews of user.")
    fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
    ): ReviewConnection {
        return ReviewConnection(first, skip, Condition.eq("userId", id), orderBy, entityTemplate)
    }

}
