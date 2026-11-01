package org.misarch.simulation.shipment

import org.misarch.simulation.shipment.model.RegisterShipmentDTO
import org.misarch.simulation.shipment.model.ShipmentDTO
import org.misarch.simulation.shipment.model.UpdateShipmentDTO
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Controller of the simulated shipment provider
 *
 * @property shipmentService service for registered shipments
 */
@RestController
@RequestMapping("/shipment")
class ShipmentController(
    private val shipmentService: ShipmentService,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody input: RegisterShipmentDTO) = shipmentService.register(input)

    @PostMapping("/update")
    @ResponseStatus(HttpStatus.CREATED)
    fun update(@RequestBody input: UpdateShipmentDTO) = shipmentService.update(input)

    @PostMapping("/findAll")
    @ResponseStatus(HttpStatus.CREATED)
    fun findAll(): List<ShipmentDTO> = shipmentService.findAll()
}
