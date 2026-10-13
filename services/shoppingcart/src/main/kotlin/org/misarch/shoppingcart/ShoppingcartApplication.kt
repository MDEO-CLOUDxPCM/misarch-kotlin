package org.misarch.shoppingcart

import org.misarch.common.rust.asyncgraphql.AsyncGraphqlConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(AsyncGraphqlConfiguration::class)
class ShoppingcartApplication

fun main(args: Array<String>) {
    runApplication<ShoppingcartApplication>(*args)
}
