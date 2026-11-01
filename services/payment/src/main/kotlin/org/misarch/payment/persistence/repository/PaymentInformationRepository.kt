package org.misarch.payment.persistence.repository

import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.persistence.model.PaymentInformationEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [PaymentInformationEntity]s
 */
interface PaymentInformationRepository : CoroutineCrudRepository<PaymentInformationEntity, UUID> {

    /**
     * Whether a user has a payment information with a payment method
     *
     * @param userId the user
     * @param paymentMethod the payment method
     */
    suspend fun existsByUserIdAndPaymentMethod(userId: UUID, paymentMethod: PaymentMethod): Boolean

    /**
     * Takes a transaction-scoped advisory lock on [key] (serializes the handling of redelivered events of one user,
     * key `payment-user:<id>`, so that concurrent deliveries of `user/user/created` create the default payment
     * informations once)
     *
     * @param key the lock key
     */
    @Query("SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))")
    suspend fun lockForTransaction(key: String): Int

}
