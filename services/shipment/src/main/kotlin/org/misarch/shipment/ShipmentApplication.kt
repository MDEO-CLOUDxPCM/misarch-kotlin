package org.misarch.shipment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ShipmentApplication

fun main(args: Array<String>) {
    runApplication<ShipmentApplication>(*args)
}
