package org.misarch.simulation.connector

import java.net.URI

/**
 * One HTTP POST with a JSON body; returns the status code, throws on network errors.
 */
fun interface CallbackTransport {
    suspend fun post(uri: URI, json: String): Int
}
