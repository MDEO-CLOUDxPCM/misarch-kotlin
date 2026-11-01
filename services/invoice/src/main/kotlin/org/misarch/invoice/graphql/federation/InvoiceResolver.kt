package org.misarch.invoice.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.invoice.graphql.dataloader.InvoiceDataLoader
import org.misarch.invoice.graphql.model.Invoice
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Invoice]s.
 */
@Component
class InvoiceResolver : DataLoaderEntityResolver<Invoice>(Invoice::class, InvoiceDataLoader::class)
