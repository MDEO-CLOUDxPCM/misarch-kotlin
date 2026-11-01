package org.misarch.common

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The [Clock] services read the current time from (`OffsetDateTime.now(clock)`): UTC; tests may replace it.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clock(): Clock = Clock.systemUTC()
}
