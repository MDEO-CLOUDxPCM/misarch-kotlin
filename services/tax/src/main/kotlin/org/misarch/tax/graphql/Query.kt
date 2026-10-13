package org.misarch.tax.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.dataLoader
import org.misarch.tax.graphql.dataloader.TaxRateDataLoader
import org.misarch.tax.graphql.dataloader.TaxRateVersionDataLoader
import org.misarch.tax.graphql.model.TaxRate
import org.misarch.tax.graphql.model.TaxRateVersion
import org.misarch.tax.graphql.model.connection.TaxRateConnection
import org.misarch.tax.graphql.model.connection.TaxRateOrder
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 *
 * @property entityTemplate executes the connection queries
 */
@Component
class Query(
    private val entityTemplate: R2dbcEntityTemplate
) : Query {

    @GraphQLDescription("Get a TaxRate by id")
    fun taxRate(
        @GraphQLDescription("The id of the TaxRate")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<TaxRate> {
        dfe.authorizedUser.checkIsEmployee()
        return dfe.dataLoader<UUID, TaxRate>(TaxRateDataLoader::class.simpleName!!).load(id)
    }

    @GraphQLDescription("Get a TaxRateVersion by id")
    fun taxRateVersion(
        @GraphQLDescription("The id of the TaxRateVersion")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<TaxRateVersion> {
        dfe.authorizedUser.checkIsEmployee()
        return dfe.dataLoader<UUID, TaxRateVersion>(TaxRateVersionDataLoader::class.simpleName!!).load(id)
    }

    @GraphQLDescription("Get all TaxRates")
    suspend fun taxRates(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: TaxRateOrder? = null,
        dfe: DataFetchingEnvironment
    ): TaxRateConnection {
        dfe.authorizedUser.checkIsEmployee()
        return TaxRateConnection(first, skip, null, orderBy, entityTemplate, dfe.authorizedUserOrNull)
    }

}
