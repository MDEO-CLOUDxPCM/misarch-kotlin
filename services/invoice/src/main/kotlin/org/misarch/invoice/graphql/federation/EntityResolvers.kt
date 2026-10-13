package org.misarch.invoice.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustEntitiesDataFetcher
import org.misarch.common.rust.RustUuid
import org.misarch.invoice.graphql.model.Invoice
import org.misarch.invoice.graphql.model.Order
import org.misarch.invoice.service.InvoiceService
import org.springframework.stereotype.Component
import java.util.UUID

private fun Map<String, Any>.idKey(): UUID =
    (this["id"] as? String)?.let(RustUuid::parseOrNull) ?: throw RustEntitiesDataFetcher.entityNotFound()

/** `order_entity_resolver`: first invoice of the order; missing → error (fails the whole `_entities`). */
@Component
class OrderEntityResolver(private val invoiceService: InvoiceService) : FederatedTypeSuspendResolver<Order> {
    override val typeName = "Order"
    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Order {
        val id = representation.idKey()
        return Order(id, invoiceService.loadInvoiceByOrderId(id))
    }
}

/** `invoice_entity_resolver`: like `invoice(id)`. */
@Component
class InvoiceEntityResolver(private val invoiceService: InvoiceService) : FederatedTypeSuspendResolver<Invoice> {
    override val typeName = "Invoice"
    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Invoice =
        invoiceService.loadInvoice(representation.idKey())
}
