package org.misarch.simulation.web

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.simulation.config.SimulationVariables
import org.misarch.simulation.config.UndefinedVariableException
import org.misarch.simulation.js.JsJson
import org.misarch.simulation.processing.SimulationQueue
import org.misarch.simulation.provider.ProviderRecordNotFoundException
import org.misarch.simulation.provider.ProviderService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.databind.JsonNode

/**
 * The HTTP API of the original NestJS app (docs/specs/simulation.md §3), with NestJS/Express answers: every POST
 * handler answers 201, validation errors 400 `{"message":[...],"error":"Bad Request","statusCode":400}`, unknown
 * routes 404 `{"message":"Cannot <METHOD> <url>","error":"Not Found","statusCode":404}`, uncaught errors 500
 * `{"statusCode":500,"message":"Internal server error"}`. Paths match like Express (case-insensitive, optional
 * trailing slash); `GET` routes also answer `HEAD`.
 */
class SimulationRoutes(
    private val providers: ProviderService,
    private val variables: SimulationVariables,
) {
    private val log = LoggerFactory.getLogger(SimulationRoutes::class.java)

    fun router(): RouterFunction<ServerResponse> = coRouter {
        handle(HttpMethod.POST, "/payment/register") { register(it, SimulationQueue.PAYMENTS, Validation.CREATE_PAYMENT) }
        handle(HttpMethod.POST, "/payment/update") { update(it, SimulationQueue.PAYMENTS, Validation.UPDATE_PAYMENT) }
        handle(HttpMethod.POST, "/payment/findAll") { respondCreated(providers.findAll(SimulationQueue.PAYMENTS)) }
        handle(HttpMethod.POST, "/shipment/register") { register(it, SimulationQueue.SHIPMENTS, Validation.CREATE_SHIPMENT) }
        handle(HttpMethod.POST, "/shipment/update") { update(it, SimulationQueue.SHIPMENTS, Validation.UPDATE_SHIPMENT) }
        handle(HttpMethod.POST, "/shipment/findAll") { respondCreated(providers.findAll(SimulationQueue.SHIPMENTS)) }
        handle(HttpMethod.GET, "/ecs/defined-variables") { respondJson(200, variables.definedVariables()) }
        handle(HttpMethod.POST, "/ecs/variables") { setVariables(it) }
        handle(HttpMethod.GET, "/health") { respondJson(200, JsJson.objectNode().put("status", "OK")) }
        // Nest's not-found handler (Express' req.originalUrl includes the query string)
        RequestPredicate { true }.invoke { request ->
            val uri = request.uri()
            val url = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            nestError(404, JsJson.nodes.stringNode("Cannot ${request.method().name()} $url"), "Not Found")
        }
    }

    private fun org.springframework.web.reactive.function.server.CoRouterFunctionDsl.handle(
        method: HttpMethod,
        path: String,
        handler: suspend (ServerRequest) -> ServerResponse,
    ) {
        expressRoute(method, path).invoke { request ->
            try {
                handler(request)
            } catch (e: BodyParserException) {
                expressError(e.status, e.message ?: "Bad Request")
            } catch (e: ProviderRecordNotFoundException) {
                nestError(404, JsJson.nodes.stringNode(e.message), "Not Found")
            } catch (e: Exception) {
                log.error("Request {} {} failed", request.method(), request.uri().rawPath, e)
                internalServerError()
            }
        }
    }

    private suspend fun register(
        request: ServerRequest,
        queue: SimulationQueue,
        rules: List<Pair<String, Validation.Rule>>,
    ): ServerResponse {
        val body = readJsonBody(request)
        val errors = Validation.validate(body, rules)
        if (errors.isNotEmpty()) return badRequest(errors)
        providers.register(queue, body)
        return respondCreated(null)
    }

    private suspend fun update(
        request: ServerRequest,
        queue: SimulationQueue,
        rules: List<Pair<String, Validation.Rule>>,
    ): ServerResponse {
        val body = readJsonBody(request)
        val errors = Validation.validate(body, rules)
        if (errors.isNotEmpty()) return badRequest(errors)
        providers.update(queue, body.get(queue.idField).stringValue(), body.get("status").stringValue())
        return respondCreated(null)
    }

    private suspend fun setVariables(request: ServerRequest): ServerResponse {
        val body = readJsonBody(request)
        return try {
            variables.setVariables(body)
            respondCreated(null)
        } catch (e: UndefinedVariableException) {
            log.error(e.message)
            internalServerError()
        }
    }

    /**
     * Express `body-parser.json()` (100 kB limit, strict) as configured by NestJS: only `application/json` bodies are
     * parsed, everything else (and an empty body) is `{}`.
     */
    private suspend fun readJsonBody(request: ServerRequest): JsonNode {
        val contentType = request.headers().contentType().orElse(null)
        val isJson = contentType != null &&
            contentType.type.equals("application", ignoreCase = true) &&
            contentType.subtype.equals("json", ignoreCase = true)
        if (!isJson) return JsJson.objectNode()
        val bytes = request.bodyToMono(ByteArray::class.java).awaitSingleOrNull() ?: return JsJson.objectNode()
        if (bytes.size > BODY_LIMIT_BYTES) throw BodyParserException(413, "PayloadTooLargeError: request entity too large")
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return JsJson.objectNode()
        val first = text.firstOrNull { it != ' ' && it != '\t' && it != '\n' && it != '\r' }
        if (first != '{' && first != '[') {
            throw BodyParserException(400, "SyntaxError: Unexpected token ${first ?: ""} in JSON at position 0")
        }
        return try {
            JsJson.parse(text)
        } catch (e: IllegalArgumentException) {
            throw BodyParserException(400, "SyntaxError: ${e.message}")
        }
    }

    private class BodyParserException(val status: Int, message: String) : RuntimeException(message)

    companion object {
        const val BODY_LIMIT_BYTES = 100 * 1024

        private val JSON_UTF8 = MediaType.parseMediaType("application/json; charset=utf-8")

        /** Express route matching: case-insensitive, optional trailing slash; `GET` also matches `HEAD`. */
        fun expressRoute(method: HttpMethod, path: String): RequestPredicate {
            val pattern = Regex("^" + Regex.escape(path) + "/?$", RegexOption.IGNORE_CASE)
            return RequestPredicate { request ->
                val methodMatches = request.method() == method ||
                    (method == HttpMethod.GET && request.method() == HttpMethod.HEAD)
                methodMatches && pattern.matches(request.requestPath().pathWithinApplication().value())
            }
        }

        suspend fun respondJson(status: Int, body: JsonNode): ServerResponse =
            ServerResponse.status(status).contentType(JSON_UTF8)
                .bodyValueAndAwait(JsJson.stringify(body).toByteArray(Charsets.UTF_8))

        /** NestJS `@Post` default: 201, empty body for `undefined`. */
        suspend fun respondCreated(body: JsonNode?): ServerResponse =
            if (body == null) ServerResponse.status(201).buildAndAwait() else respondJson(201, body)

        suspend fun nestError(status: Int, message: JsonNode, error: String): ServerResponse =
            respondJson(status, JsJson.objectNode().set("message", message).put("error", error).put("statusCode", status))

        suspend fun badRequest(messages: List<String>): ServerResponse =
            nestError(400, JsJson.arrayNode().apply { messages.forEach { add(it) } }, "Bad Request")

        suspend fun internalServerError(): ServerResponse =
            respondJson(500, JsJson.objectNode().put("statusCode", 500).put("message", "Internal server error"))

        /** Express' default error handler (body-parser errors never reach NestJS): an HTML page. */
        suspend fun expressError(status: Int, message: String): ServerResponse {
            val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>Error</title>\n" +
                "</head>\n<body>\n<pre>$escaped</pre>\n</body>\n</html>\n"
            return ServerResponse.status(status).contentType(MediaType.parseMediaType("text/html; charset=utf-8"))
                .bodyValueAndAwait(html.toByteArray(Charsets.UTF_8))
        }
    }
}
