package org.misarch.invoice.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import org.misarch.invoice.graphql.model.Invoice
import org.misarch.invoice.service.InvoiceService
import org.springframework.stereotype.Component
import java.util.UUID

/** Invoice queries (original `graphql/query.rs`). No authorization at all (as in the original). */
@Component
class Query(private val invoiceService: InvoiceService) : Query {

    @GraphQLDescription("Query for invoice of specific UUID.")
    suspend fun invoice(
        @GraphQLDescription("UUID of invoice to retrieve.")
        id: UUID,
    ): Invoice = invoiceService.loadInvoice(id)
}
