package org.misarch.tax.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.load
import org.misarch.common.persistence.Condition
import org.misarch.tax.graphql.dataloader.TaxRateVersionDataLoader
import org.misarch.tax.graphql.model.connection.TaxRateVersionConnection
import org.misarch.tax.graphql.model.connection.TaxRateVersionOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A TaxRate.")
@KeyDirective(fields = FieldSet("id"))
class TaxRate(
    id: UUID,
    @property:GraphQLDescription("The name of the TaxRate")
    val name: String,
    @property:GraphQLDescription("The description of the TaxRate")
    val description: String,
    private val currentVersionId: UUID
) : Node(id) {

    @GraphQLDescription("The current version of the TaxRate.")
    fun currentVersion(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<TaxRateVersion> {
        return dfe.load(TaxRateVersionDataLoader::class, currentVersionId)
    }

    @GraphQLDescription("Get all associated versions")
    suspend fun versions(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: TaxRateVersionOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): TaxRateVersionConnection {
        dfe.authorizedUser.checkIsEmployee()
        return TaxRateVersionConnection(
            first,
            skip,
            Condition.eq("taxRateId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
