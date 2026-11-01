package org.misarch.payment.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.payment.graphql.dataloader.PaymentDataLoader
import org.misarch.payment.graphql.model.Payment
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Payment]s.
 */
@Component
class PaymentResolver : DataLoaderEntityResolver<Payment>(Payment::class, PaymentDataLoader::class)
