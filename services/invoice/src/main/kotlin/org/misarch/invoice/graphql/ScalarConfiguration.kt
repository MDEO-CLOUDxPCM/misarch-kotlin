package org.misarch.invoice.graphql

import graphql.scalars.datetime.DateTimeScalar
import graphql.scalars.id.UUIDScalar
import org.misarch.common.graphql.ScalarMapping
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `UUID` and `DateTime` scalars without descriptions (the SDL of the invoice service has none).
 */
@Configuration(proxyBeanMethods = false)
class ScalarConfiguration {

    @Bean
    fun uuidScalar() = ScalarMapping(UUID::class, UUIDScalar.INSTANCE.transform { it.description(null) })

    @Bean
    fun dateTimeScalar() = ScalarMapping(
        OffsetDateTime::class, DateTimeScalar.INSTANCE.transform { it.description(null).specifiedByUrl(null) }
    )

}
