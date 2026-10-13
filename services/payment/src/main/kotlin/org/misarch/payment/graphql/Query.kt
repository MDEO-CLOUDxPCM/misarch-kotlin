package org.misarch.payment.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.payment.graphql.input.PaymentFilter
import org.misarch.payment.graphql.input.PaymentInformationFilter
import org.misarch.payment.graphql.input.PaymentInformationOrder
import org.misarch.payment.graphql.input.PaymentOrder
import org.misarch.payment.graphql.model.PaymentConnection
import org.misarch.payment.graphql.model.PaymentInformationConnection
import org.misarch.common.nest.RolesGuard
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentService
import org.springframework.stereotype.Component

/** Queries of the original payment service (docs/specs/payment.md §4). */
@Component
class Query(
    private val paymentInformationService: PaymentInformationService,
    private val paymentService: PaymentService,
) : Query {

    @GraphQLDescription(" Retrieves all payment informations matching the filter")
    suspend fun paymentInformations(
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
        dfe: DataFetchingEnvironment,
    ): PaymentInformationConnection {
        RolesGuard.check(dfe, RolesGuard.ADMIN, RolesGuard.EMPLOYEE)
        val args = ConnectionArgs(dfe)
        args.validate()
        // `user` matches the owner id (fix P7; the original compared it with the stored {id} sub-document)
        val user = args.filterMatch("user")
        val method = args.filterMatch("paymentMethod")
        return PaymentInformationConnection.build(
            args,
            nodes = { paymentInformationService.find(user, method, args.orderBy, args.first, args.skip) },
            count = { paymentInformationService.count(user, method) },
        )
    }

    @GraphQLDescription("Retrieves all payments")
    suspend fun payments(
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
        dfe: DataFetchingEnvironment,
    ): PaymentConnection {
        RolesGuard.check(dfe, RolesGuard.ADMIN, RolesGuard.EMPLOYEE)
        val args = ConnectionArgs(dfe)
        args.validate()
        return PaymentConnection.build(
            args,
            nodes = { paymentService.find(args.filter, args.orderBy, args.first, args.skip) },
            count = { paymentService.count(args.filter) },
        )
    }
}
