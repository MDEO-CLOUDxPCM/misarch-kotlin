package org.misarch.simulation.amqp

import org.slf4j.LoggerFactory
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Ends the application after an error it cannot recover from (broker connection lost, failed reconnect, an error
 * while consuming): the context is closed and the process exits with code 1, so that the container is restarted
 * (`restart: unless-stopped`) and connects anew.
 */
@Component
class FatalErrorHandler(private val applicationContext: ApplicationContext) {

    private val logger = LoggerFactory.getLogger(FatalErrorHandler::class.java)

    private val terminating = AtomicBoolean(false)

    fun fatal(message: String, cause: Throwable?) {
        logger.error("Fatal error, terminating: {}", message, cause)
        if (!terminating.compareAndSet(false, true)) return
        // on its own thread: the caller may be a broker thread that the shutdown waits for
        thread(name = "simulation-exit") {
            exitProcess(SpringApplication.exit(applicationContext, ExitCodeGenerator { 1 }))
        }
    }
}
