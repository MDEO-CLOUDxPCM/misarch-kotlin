package org.misarch.simulation

import org.springframework.http.MediaType
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.test.web.reactive.server.WebTestClient

/** A [WebTestClient] for the given controllers, with the Kotlin JSON mapper. */
fun controllerTestClient(vararg controllers: Any): WebTestClient =
    WebTestClient.bindToController(*controllers)
        .httpMessageCodecs {
            it.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(testJsonMapper))
            it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(testJsonMapper))
        }
        .build()

fun WebTestClient.postJson(path: String, json: String?): WebTestClient.ResponseSpec {
    val spec = post().uri(path).contentType(MediaType.APPLICATION_JSON)
    return if (json != null) spec.bodyValue(json).exchange() else spec.exchange()
}

fun WebTestClient.ResponseSpec.body(): String? = expectBody(String::class.java).returnResult().responseBody
