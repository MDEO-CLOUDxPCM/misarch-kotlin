package org.misarch.common.timing

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Timing spans ([TimingSpans]), only with `misarch.timing.spans=true` (env `MISARCH_TIMING_SPANS`): switches the
 * facade on with an [OpenTelemetry] bean if the application has one (tests), else with `GlobalOpenTelemetry` (the
 * Java agent), and adds the repository spans ([TimingRepositoryPostProcessor]). Without the property nothing of
 * this is registered.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = [TimingSpans.PROPERTY], havingValue = "true")
class TimingSpansConfiguration {

    @Bean
    fun timingSpansActivation(openTelemetry: ObjectProvider<OpenTelemetry>): TimingSpansActivation =
        TimingSpansActivation(openTelemetry.getIfAvailable { GlobalOpenTelemetry.get() })

    companion object {
        @Bean
        @JvmStatic
        fun timingRepositoryPostProcessor(): BeanPostProcessor = TimingRepositoryPostProcessor()
    }
}

/** Switches [TimingSpans] on for the lifetime of the application context. */
class TimingSpansActivation(openTelemetry: OpenTelemetry) : DisposableBean {

    init {
        val on = TimingSpans.enable(openTelemetry)
        LoggerFactory.getLogger(TimingSpans::class.java).info(
            if (on) "timing spans on" else "timing spans requested, but no OpenTelemetry agent/SDK: spans stay off"
        )
    }

    override fun destroy() = TimingSpans.disable()
}
