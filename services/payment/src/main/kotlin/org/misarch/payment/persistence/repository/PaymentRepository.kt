package org.misarch.payment.persistence.repository

import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.graphql.model.PaymentStatus
import org.misarch.payment.persistence.model.PaymentEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repository for [PaymentEntity]s
 */
interface PaymentRepository : CoroutineCrudRepository<PaymentEntity, UUID> {

    /**
     * The payment with a row lock (status updates of the provider callback are processed one after another)
     *
     * @param id the payment
     */
    @Query("SELECT * FROM PaymentEntity WHERE id = :id FOR UPDATE")
    suspend fun findByIdForUpdate(id: UUID): PaymentEntity?

    /**
     * The payments with a status and a payment method created at or before a point in time, locked for the update
     * (index (status, createdAt))
     *
     * @param status the status
     * @param paymentMethod the payment method of the payment information
     * @param createdBefore the latest creation time
     */
    @Query(
        """
        SELECT t.* FROM PaymentEntity t INNER JOIN PaymentInformationEntity i ON i.id = t.paymentInformationId
        WHERE t.status = :status AND i.paymentMethod = :paymentMethod AND t.createdAt <= :createdBefore
        ORDER BY t.id FOR UPDATE OF t
        """
    )
    suspend fun findAllForUpdateByStatusAndPaymentMethodCreatedBefore(
        status: PaymentStatus, paymentMethod: PaymentMethod, createdBefore: OffsetDateTime
    ): List<PaymentEntity>

}
