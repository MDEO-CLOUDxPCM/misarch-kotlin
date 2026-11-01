package org.misarch.simulation.controller

import org.misarch.simulation.model.RegisterShipmentDTO
import org.misarch.simulation.model.UpdateShipmentDTO
import org.misarch.simulation.service.RegistrationService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

/**
 * The simulated shipment provider (`/shipment/...`, called by shipment)
 */
@Controller
@RequestMapping("/shipment")
class ShipmentController(
    shipmentRegistrationService: RegistrationService<RegisterShipmentDTO>
) : ProviderController<RegisterShipmentDTO, UpdateShipmentDTO>(shipmentRegistrationService)
