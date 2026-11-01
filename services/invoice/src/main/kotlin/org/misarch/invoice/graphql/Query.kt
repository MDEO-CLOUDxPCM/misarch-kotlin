package org.misarch.invoice.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.load
import org.misarch.invoice.graphql.dataloader.InvoiceDataLoader
import org.misarch.invoice.graphql.model.Invoice
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries (invoices are readable without authorization)
 */
@Component
class Query : Query {

    @GraphQLDescription("Query for invoice of specific UUID.")
    fun invoice(
        @GraphQLDescription("UUID of invoice to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Invoice> {
        return dfe.load(InvoiceDataLoader::class, id)
    }

}
