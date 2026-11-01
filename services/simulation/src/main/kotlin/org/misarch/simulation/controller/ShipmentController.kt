package org.misarch.simulation.controller

import org.misarch.simulation.model.RegisterShipmentDTO
import org.misarch.simulation.model.ShipmentDTO
import org.misarch.simulation.model.UpdateShipmentDTO
import org.misarch.simulation.service.ShipmentService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Controller of the simulated shipment provider. Every route is a `POST` answering 201 (the provider's contract,
 * also for the read-only `findAll`).
 */
@RestController
@RequestMapping("/shipment")
class ShipmentController(
    private val shipmentService: ShipmentService,
) {

    /** Registers a shipment (called by shipment); the status callback follows after processing. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody input: RegisterShipmentDTO) = shipmentService.register(input)

    /** Sends the given status right away instead of the automatic one (404 for an unknown shipment). */
    @PostMapping("/update")
    @ResponseStatus(HttpStatus.CREATED)
    fun update(@RequestBody input: UpdateShipmentDTO) = shipmentService.update(input)

    /** Shipments whose queue message has not been consumed yet. */
    @PostMapping("/findAll")
    @ResponseStatus(HttpStatus.CREATED)
    fun findAll(): List<ShipmentDTO> = shipmentService.findAll().map { it.toDTO() }
}
