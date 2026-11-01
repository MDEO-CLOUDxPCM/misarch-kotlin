package org.misarch.simulation.controller

import org.misarch.simulation.model.PendingRegistrationDTO
import org.misarch.simulation.model.Registration
import org.misarch.simulation.model.StatusUpdate
import org.misarch.simulation.service.RegistrationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * The HTTP API of a simulated provider (one subclass per provider, mapped to its path)
 */
abstract class ProviderController<R : Registration, U : StatusUpdate>(
    private val registrationService: RegistrationService<R>,
) {

    /** Registers a payment or shipment; the status callback follows after processing. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody registration: R) = registrationService.register(registration)

    /** Sends the given status right away instead of the automatic one (404 for an unknown registration). */
    @PostMapping("/update")
    @ResponseStatus(HttpStatus.OK)
    fun update(@RequestBody update: U) = registrationService.update(update)

    /** The registrations whose queue message has not been consumed yet. */
    @GetMapping("/registrations")
    @ResponseBody
    fun registrations(): List<PendingRegistrationDTO<R>> = registrationService.findAll()

}
