package org.misarch.payment.persistence.model

import io.r2dbc.postgresql.codec.Json
import org.misarch.common.persistence.BaseEntity
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.payment.graphql.model.PaymentMethod
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for payment informations (the payment methods of a user)
 *
 * @property paymentMethod the payment method
 * @property publicMethodDetails details shown to the user (JSON object; credit card: holder, masked number, expiry)
 * @property secretMethodDetails details only used for the payment (JSON object; credit card: the card data)
 * @property userId the user who owns the payment information
 * @property id unique identifier of the payment information
 */
@Table
class PaymentInformationEntity(
    val paymentMethod: PaymentMethod,
    val publicMethodDetails: Json?,
    val secretMethodDetails: Json?,
    val userId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<PaymentInformation> {

    override fun toDTO(): PaymentInformation {
        return PaymentInformation(
            id = id!!,
            paymentMethod = paymentMethod,
            publicMethodDetails = publicMethodDetails?.asString(),
            userId = userId
        )
    }

}
