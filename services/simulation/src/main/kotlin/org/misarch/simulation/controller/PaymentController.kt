package org.misarch.simulation.controller

import org.misarch.simulation.model.PaymentDTO
import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.UpdatePaymentDTO
import org.misarch.simulation.service.PaymentService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Controller of the simulated payment provider. Every route is a `POST` answering 201 (the provider's contract,
 * also for the read-only `findAll`).
 *
 * @property paymentService service for registered payments
 */
@RestController
@RequestMapping("/payment")
class PaymentController(
    private val paymentService: PaymentService,
) {

    /** Registers a payment (called by payment); the status callback follows after processing. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody input: RegisterPaymentDTO) = paymentService.register(input)

    /** Sends the given status right away instead of the automatic one (404 for an unknown payment). */
    @PostMapping("/update")
    @ResponseStatus(HttpStatus.CREATED)
    fun update(@RequestBody input: UpdatePaymentDTO) = paymentService.update(input)

    /** Payments whose queue message has not been consumed yet. */
    @PostMapping("/findAll")
    @ResponseStatus(HttpStatus.CREATED)
    fun findAll(): List<PaymentDTO> = paymentService.findAll().map { it.toDTO() }
}
