package org.misarch.payment.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.payment.persistence.model.PaymentInformationEntity
import org.misarch.payment.persistence.repository.PaymentInformationRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [PaymentInformation]s
 *
 * @param repository repository for [PaymentInformationEntity]s
 */
@Component
class PaymentInformationDataLoader(
    repository: PaymentInformationRepository
) : IdDataLoader<PaymentInformation, PaymentInformationEntity>(repository)
