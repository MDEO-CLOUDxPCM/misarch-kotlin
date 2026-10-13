package org.misarch.tax

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TaxApplication

fun main(args: Array<String>) {
    runApplication<TaxApplication>(*args)
}
