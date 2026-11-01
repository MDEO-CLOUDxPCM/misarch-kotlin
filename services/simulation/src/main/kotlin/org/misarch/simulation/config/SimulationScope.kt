package org.misarch.simulation.config

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import kotlin.coroutines.CoroutineContext

/**
 * Scope of the background work (processing delays, callbacks, queue publishes); cancelled on shutdown. A failing
 * task is logged and does not affect the others.
 */
@Component
class SimulationScope : CoroutineScope, DisposableBean {

    private val logger = LoggerFactory.getLogger(SimulationScope::class.java)

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default +
        CoroutineName("simulation") +
        CoroutineExceptionHandler { _, e -> logger.error("Unexpected error in a simulation task", e) }

    override fun destroy() = cancel()
}
