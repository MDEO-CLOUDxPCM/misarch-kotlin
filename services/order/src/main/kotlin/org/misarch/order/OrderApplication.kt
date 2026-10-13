package org.misarch.order

import org.misarch.common.rust.asyncgraphql.AsyncGraphqlExecutionConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * async-graphql execution semantics of the Rust original (`data: null` + first error, `_entities` all-or-nothing with
 * the `["_entities"]` path, serial mutations stop at the first failure): [AsyncGraphqlExecutionConfiguration]
 * (docs/INTEGRATION.md F4). Scalars, `Authorized-User` parsing and the HTTP routes are order's own.
 */
@SpringBootApplication
@Import(AsyncGraphqlExecutionConfiguration::class)
class OrderApplication

fun main(args: Array<String>) {
    runApplication<OrderApplication>(*args)
}
