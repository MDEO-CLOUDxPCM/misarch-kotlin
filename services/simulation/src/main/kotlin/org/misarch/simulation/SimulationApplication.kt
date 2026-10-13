package org.misarch.simulation

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.misarch.simulation.amqp.RabbitConsumer
import org.misarch.simulation.amqp.RabbitPublisher
import org.misarch.simulation.config.SimulationVariables
import org.misarch.simulation.connector.CallbackTransport
import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.connector.WebClientCallbackTransport
import org.misarch.simulation.processing.EventProcessor
import org.misarch.simulation.processing.FatalErrorHandler
import org.misarch.simulation.processing.HaltingFatalErrorHandler
import org.misarch.simulation.processing.SimulationState
import org.misarch.simulation.provider.ProviderService
import org.misarch.simulation.web.SimulationRoutes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

@SpringBootApplication
@EnableScheduling
class SimulationApplication

fun main(args: Array<String>) {
    runApplication<SimulationApplication>(*args)
}

/**
 * Wiring of the simulation (the original's Nest modules): variables, connector (callbacks), RabbitMQ consumer and
 * publishers, event processor, HTTP routes.
 */
@Configuration(proxyBeanMethods = false)
class SimulationConfiguration {

    private val log = LoggerFactory.getLogger(SimulationConfiguration::class.java)

    /** Scope of processing timers and callbacks (the original's `setTimeout`s and un-awaited promises). */
    @Bean(destroyMethod = "")
    fun simulationScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("simulation") +
            CoroutineExceptionHandler { _, e -> log.error("Unexpected error in a simulation task", e) }
    )

    @Bean
    fun fatalErrorHandler(): FatalErrorHandler = HaltingFatalErrorHandler()

    @Bean
    fun callbackTransport(): CallbackTransport = WebClientCallbackTransport()

    @Bean
    fun connectorService(variables: SimulationVariables, transport: CallbackTransport, scope: CoroutineScope) =
        ConnectorService(variables, transport, scope)

    /** `RABBITMQ_URL` (required: the original throws `RABBITMQ_URL is not set` during start-up). */
    @Bean
    fun rabbitUrl(variables: SimulationVariables): RabbitUrl =
        RabbitUrl(variables.env("RABBITMQ_URL") ?: throw IllegalStateException("RABBITMQ_URL is not set"))

    @Bean(destroyMethod = "shutdown")
    fun rabbitPublisher(url: RabbitUrl) = RabbitPublisher(url.value)

    @Bean
    fun rabbitConsumer(url: RabbitUrl, fatal: FatalErrorHandler) = RabbitConsumer(url.value, fatal)

    @Bean
    fun eventProcessor(
        state: SimulationState,
        variables: SimulationVariables,
        connector: ConnectorService,
        consumer: RabbitConsumer,
        fatal: FatalErrorHandler,
        scope: CoroutineScope,
    ) = EventProcessor(state, variables, connector, consumer, fatal, scope)

    @Bean
    fun providerService(state: SimulationState, publisher: RabbitPublisher, connector: ConnectorService) =
        ProviderService(state, publisher, connector)

    @Bean
    fun simulationRouter(providers: ProviderService, variables: SimulationVariables): RouterFunction<ServerResponse> =
        SimulationRoutes(providers, variables).router()
}

/** The AMQP URL of the broker (`RABBITMQ_URL`). */
data class RabbitUrl(val value: String)

/**
 * Start/stop of the event processing:
 * - connects to RabbitMQ and starts the consumers after all beans exist but **before the web server starts**
 *   (Nest's `onModuleInit` blocks `app.listen(8080)` until the broker is reachable, retrying every 5 s);
 * - `@Cron('0 * * * * *')` rate-limit reset (property `simulation.reset-cron`);
 * - on shutdown closes the consumer connection and cancels pending timers.
 */
@Component
class EventProcessorLifecycle(
    private val processor: EventProcessor,
    private val scope: CoroutineScope,
) : SmartInitializingSingleton, DisposableBean {

    override fun afterSingletonsInstantiated() = processor.start()

    @Scheduled(cron = "\${simulation.reset-cron:0 * * * * *}")
    fun resetMessageCounts() = processor.resetMessageCounts()

    override fun destroy() {
        processor.shutdown()
        scope.cancel()
    }
}
