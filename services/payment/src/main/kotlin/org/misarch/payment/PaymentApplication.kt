package org.misarch.payment

import org.misarch.common.nest.NestWebConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(NestWebConfiguration::class)
@org.springframework.scheduling.annotation.EnableScheduling
class PaymentApplication

fun main(args: Array<String>) {
    runApplication<PaymentApplication>(*args)
}
