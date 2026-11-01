package org.misarch.payment.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.persistence.model.PaymentEntity
import org.misarch.payment.persistence.repository.PaymentRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Payment]s
 *
 * @param repository repository for [PaymentEntity]s
 */
@Component
class PaymentDataLoader(
    repository: PaymentRepository
) : IdDataLoader<Payment, PaymentEntity>(repository)
