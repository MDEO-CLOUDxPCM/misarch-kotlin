package org.misarch.payment.provider

import org.misarch.payment.service.PaymentService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for the external payment provider
 * Receives payment status updates from the provider (direct HTTP, not via Dapr)
 *
 * @property paymentService service for handling payments
 */
@Controller
class PaymentProviderController(
    private val paymentService: PaymentService
) {

    @PostMapping("/payment/update-payment-status")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun updatePaymentStatus(
        @RequestBody
        input: UpdatePaymentStatusInput
    ) {
        paymentService.updatePaymentStatus(input.paymentId, input.status)
    }

}
