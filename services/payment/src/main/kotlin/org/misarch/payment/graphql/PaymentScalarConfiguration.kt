package org.misarch.payment.graphql

import graphql.scalars.ExtendedScalars
import graphql.scalars.datetime.DateTimeScalar
import org.misarch.common.graphql.ScalarMapping
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.OffsetDateTime

/**
 * The scalars of the payment subgraph: `DateTime` with its own description and `JSONObject` (any JSON object, as
 * `Map`)
 */
@Configuration(proxyBeanMethods = false)
class PaymentScalarConfiguration {

    @Bean
    fun dateTimeScalarMapping() = ScalarMapping(
        OffsetDateTime::class,
        DateTimeScalar.INSTANCE.transform {
            it.description("A date-time string at UTC, such as 2019-12-03T09:54:33Z, compliant with the date-time format.")
                .specifiedByUrl(null)
        }
    )

    @Bean
    fun jsonObjectScalarMapping() = ScalarMapping(
        Map::class,
        ExtendedScalars.Object.transform {
            it.name("JSONObject").description(
                "The `JSONObject` scalar type represents JSON objects as specified by " +
                    "[ECMA-404](http://www.ecma-international.org/publications/files/ECMA-ST/ECMA-404.pdf)."
            )
        }
    )

}
