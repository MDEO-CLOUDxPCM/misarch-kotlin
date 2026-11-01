package org.misarch.payment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.payment.graphql.model.connection.PaymentInformationConnection
import org.misarch.payment.graphql.model.connection.PaymentInformationFilter
import org.misarch.payment.graphql.model.connection.PaymentInformationOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("Foreign type User")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("The uuid identifier of the user")
    val id: UUID
) {

    @GraphQLDescription("A connection for an users payment informations.")
    fun paymentInformations(
        @GraphQLDescription("Number of items to skip")
        @GraphQLDefaultValue("0")
        skip: Int? = 0,
        @GraphQLDescription("Number of items to return")
        @GraphQLDefaultValue("2147483647")
        first: Int? = Int.MAX_VALUE,
        @GraphQLDescription("Ordering")
        orderBy: PaymentInformationOrder? = null,
        @GraphQLDescription("Filtering")
        filter: PaymentInformationFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): PaymentInformationConnection? {
        if (dfe.authorizedUser.id != id) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return PaymentInformationConnection(first, skip, filter, Condition.eq("userId", id), orderBy, entityTemplate)
    }

}
