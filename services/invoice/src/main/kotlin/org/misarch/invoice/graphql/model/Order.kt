package org.misarch.invoice.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.load
import org.misarch.invoice.graphql.dataloader.OrderInvoiceDataLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("Foreign type of an order.")
@KeyDirective(fields = FieldSet("id"))
class Order(
    @property:GraphQLDescription("UUID of the order.")
    val id: UUID
) {

    @GraphQLDescription("Invoice of the order.")
    fun invoice(dfe: DataFetchingEnvironment): CompletableFuture<Invoice> = dfe.load(OrderInvoiceDataLoader::class, id)

}
