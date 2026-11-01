package org.misarch.payment.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.graphql.model.PaymentStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for payments (one per order)
 *
 * @property totalAmount the amount to pay in the smallest currency unit
 * @property status the status of the payment
 * @property paymentInformationId the payment information used (no foreign key: deleting it keeps the payment)
 * @property payedAt when the payment succeeded
 * @property numberOfRetries the retries of a failed credit card payment
 * @property createdAt when the payment was created
 * @property updatedAt when the payment was last changed
 * @property id unique identifier of the payment
 */
@Table
class PaymentEntity(
    val totalAmount: Double,
    var status: PaymentStatus,
    val paymentInformationId: UUID,
    var payedAt: OffsetDateTime?,
    var numberOfRetries: Int,
    val createdAt: OffsetDateTime,
    var updatedAt: OffsetDateTime,
    @Id
    override val id: UUID?
) : BaseEntity<Payment> {

    override fun toDTO(): Payment {
        return Payment(
            id = id!!,
            totalAmount = totalAmount,
            status = status,
            paymentInformationId = paymentInformationId,
            payedAt = payedAt,
            numberOfRetries = numberOfRetries.toDouble()
        )
    }

}
