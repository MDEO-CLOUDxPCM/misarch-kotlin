package org.misarch.payment.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.common.graphql.authorizedUser
import org.misarch.payment.graphql.model.connection.PaymentConnection
import org.misarch.payment.graphql.model.connection.PaymentFilter
import org.misarch.payment.graphql.model.connection.PaymentInformationConnection
import org.misarch.payment.graphql.model.connection.PaymentInformationFilter
import org.misarch.payment.graphql.model.connection.PaymentInformationOrder
import org.misarch.payment.graphql.model.connection.PaymentOrder
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component

/**
 * Defines GraphQL queries
 */
@Component
class Query(
    private val entityTemplate: R2dbcEntityTemplate
) : Query {

    @GraphQLDescription(" Retrieves all payment informations matching the filter")
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
        dfe: DataFetchingEnvironment
    ): PaymentInformationConnection {
        dfe.authorizedUser.checkIsEmployee()
        return PaymentInformationConnection(first, skip, filter, null, orderBy, entityTemplate)
    }

    @GraphQLDescription("Retrieves all payments")
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
        dfe: DataFetchingEnvironment
    ): PaymentConnection {
        dfe.authorizedUser.checkIsEmployee()
        return PaymentConnection(first, skip, filter, null, orderBy, entityTemplate)
    }

}
