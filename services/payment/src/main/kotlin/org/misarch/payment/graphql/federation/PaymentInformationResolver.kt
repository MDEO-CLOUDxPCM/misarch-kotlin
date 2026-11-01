package org.misarch.payment.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.payment.graphql.dataloader.PaymentInformationDataLoader
import org.misarch.payment.graphql.model.PaymentInformation
import org.springframework.stereotype.Component

/**
 * Federated resolver for [PaymentInformation]s.
 */
@Component
class PaymentInformationResolver :
    DataLoaderEntityResolver<PaymentInformation>(PaymentInformation::class, PaymentInformationDataLoader::class)
