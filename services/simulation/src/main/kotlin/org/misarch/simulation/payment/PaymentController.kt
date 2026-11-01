package org.misarch.simulation.payment

import org.misarch.simulation.payment.dto.PaymentDTO
import org.misarch.simulation.payment.dto.RegisterPaymentDTO
import org.misarch.simulation.payment.dto.UpdatePaymentDTO
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Controller of the simulated payment provider
 *
 * @property paymentService service for registered payments
 */
@RestController
@RequestMapping("/payment")
class PaymentController(
    private val paymentService: PaymentService,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody input: RegisterPaymentDTO) = paymentService.register(input)

    @PostMapping("/update")
    @ResponseStatus(HttpStatus.CREATED)
    fun update(@RequestBody input: UpdatePaymentDTO) = paymentService.update(input)

    @PostMapping("/findAll")
    @ResponseStatus(HttpStatus.CREATED)
    fun findAll(): List<PaymentDTO> = paymentService.findAll()
}
