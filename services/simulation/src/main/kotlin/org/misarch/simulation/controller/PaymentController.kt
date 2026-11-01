package org.misarch.simulation.controller

import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.UpdatePaymentDTO
import org.misarch.simulation.service.RegistrationService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

/**
 * The simulated payment provider (`/payment/...`, called by payment)
 */
@Controller
@RequestMapping("/payment")
class PaymentController(
    paymentRegistrationService: RegistrationService<RegisterPaymentDTO>
) : ProviderController<RegisterPaymentDTO, UpdatePaymentDTO>(paymentRegistrationService)
