package org.misarch.payment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.payment.graphql.model.connection.PaymentConnection
import org.misarch.payment.graphql.model.connection.PaymentFilter
import org.misarch.payment.graphql.model.connection.PaymentOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

@GraphQLDescription("A stored payment information of an user")
@KeyDirective(fields = FieldSet("id"))
class PaymentInformation(
    @property:GraphQLDescription("The uuid identifier of the payment information")
    val id: UUID,
    @property:GraphQLDescription("The corresponding payment method")
    val paymentMethod: PaymentMethod,
    private val publicMethodDetails: String?,
    private val userId: UUID
) {

    @GraphQLDescription("The details for the User to identify the payment method")
    fun publicMethodDetails(
        @GraphQLIgnore
        @Autowired
        jsonMapper: JsonMapper
    ): Map<String, Any?>? = publicMethodDetails?.let { jsonMapper.readValue(it) }

    @GraphQLDescription("The user who owns the payment information")
    fun user(): User {
        return User(userId)
    }

    @GraphQLDescription("A connection for an users payments made with a payment information.")
    fun payments(
        @GraphQLDescription("Number of items to skip")
        @GraphQLDefaultValue("0")
        skip: Int? = 0,
        @GraphQLDescription("Number of items to return")
        @GraphQLDefaultValue("2147483647")
        first: Int? = Int.MAX_VALUE,
        @GraphQLDescription("Ordering")
        orderBy: PaymentOrder? = null,
        @GraphQLDescription("Filtering")
        filter: PaymentFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): PaymentConnection? {
        if (dfe.authorizedUser.id != userId) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return PaymentConnection(first, skip, filter, Condition.eq("paymentInformationId", id), orderBy, entityTemplate)
    }

}
