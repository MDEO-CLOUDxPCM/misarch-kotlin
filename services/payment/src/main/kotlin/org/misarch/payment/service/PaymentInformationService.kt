package org.misarch.payment.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.payment.event.model.UserDTO
import org.misarch.payment.graphql.input.CreateCreditCardInformationInput
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.persistence.model.CreditCardDetails
import org.misarch.payment.persistence.model.PaymentInformationEntity
import org.misarch.payment.persistence.repository.PaymentInformationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Service for [PaymentInformationEntity]s
 */
@Service
class PaymentInformationService(private val repository: PaymentInformationRepository) {

    private val logger = LoggerFactory.getLogger(PaymentInformationService::class.java)

    /**
     * Creates the default payment informations (prepayment and invoice) of a new user. Idempotent: a payment method
     * the user already has is not created again; concurrent deliveries for one user are serialized.
     *
     * @param userDTO the created user
     */
    @Transactional
    suspend fun registerUser(userDTO: UserDTO) {
        repository.lockForTransaction("payment-user:${userDTO.id}")
        for (paymentMethod in listOf(PaymentMethod.PREPAYMENT, PaymentMethod.INVOICE)) {
            if (repository.existsByUserIdAndPaymentMethod(userDTO.id, paymentMethod)) {
                logger.info("User {} already has a {} payment information", userDTO.id, paymentMethod)
            } else {
                repository.save(
                    PaymentInformationEntity(
                        paymentMethod = paymentMethod,
                        publicMethodDetails = null,
                        secretMethodDetails = null,
                        userId = userDTO.id,
                        id = null
                    )
                )
            }
        }
    }

    /**
     * Creates a credit card payment information of a user; the public details carry the masked card number
     *
     * @param input the card data
     * @param userId the user
     * @throws IllegalArgumentException if the card number or the expiration date is invalid
     */
    @Transactional
    suspend fun createCreditCardInformation(input: CreateCreditCardInformationInput, userId: UUID): PaymentInformationEntity {
        CreditCardValidator.validate(input.cardNumber, input.expirationDate, LocalDate.now())
        val secretDetails = CreditCardDetails(input.cardHolder, input.cardNumber, input.expirationDate)
        return repository.save(
            PaymentInformationEntity(
                paymentMethod = PaymentMethod.CREDIT_CARD,
                publicMethodDetails = secretDetails.copy(cardNumber = CreditCardValidator.mask(input.cardNumber)),
                secretMethodDetails = secretDetails,
                userId = userId,
                id = null
            )
        )
    }

    /**
     * Deletes a payment information; employees may delete any, other users only their own. Payments made with it
     * are kept.
     *
     * @param id the payment information
     * @param authorizedUser the user who deletes it
     * @return the deleted payment information
     */
    @Transactional
    suspend fun deletePaymentInformation(id: UUID, authorizedUser: AuthorizedUser): PaymentInformationEntity {
        val paymentInformation = repository.findByIdOrThrow(id)
        if (paymentInformation.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        repository.delete(paymentInformation)
        return paymentInformation
    }

}
