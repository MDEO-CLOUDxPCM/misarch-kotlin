package org.misarch.payment.provider

import org.misarch.payment.service.PaymentRegistrationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for the external payment provider
 * Receives payment status updates from the provider (direct HTTP, not via Dapr)
 *
 * @property paymentRegistrationService applies the status updates
 */
@Controller
class PaymentProviderController(
    private val paymentRegistrationService: PaymentRegistrationService
) {

    @PostMapping("/payment/update-payment-status")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun updatePaymentStatus(
        @RequestBody
        input: UpdatePaymentStatusInput
    ) {
        paymentRegistrationService.updatePaymentStatus(input.paymentId, input.status)
    }

}
