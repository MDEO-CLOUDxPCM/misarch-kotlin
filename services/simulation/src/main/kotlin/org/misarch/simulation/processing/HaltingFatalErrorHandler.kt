package org.misarch.simulation.processing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [FatalErrorHandler] that logs and terminates the JVM at once with exit code 1 (no graceful shutdown).
 */
@Component
class HaltingFatalErrorHandler : FatalErrorHandler {

    private val log = LoggerFactory.getLogger(HaltingFatalErrorHandler::class.java)

    override fun fatal(message: String, cause: Throwable?) {
        log.error("Fatal error, terminating: {}", message, cause)
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(1)
    }
}
