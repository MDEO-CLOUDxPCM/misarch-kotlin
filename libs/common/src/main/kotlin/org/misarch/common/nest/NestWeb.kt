package org.misarch.common.nest

import com.expediagroup.graphql.server.spring.execution.SpringGraphQLContextFactory
import com.expediagroup.graphql.server.types.GraphQLResponse
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import org.misarch.common.graphql.ScalarMapping
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * HTTP behaviour of the NestJS original that is independent of the domain: `GET /health` → `{"status":"OK"}`,
 * the verbatim `GET /dapr/subscribe` list (router functions take precedence over the Dapr SDK controller), Nest's
 * error bodies, and the GraphQL beans (raw `Authorized-User` context, Nest error format, `_entities` errors, UUID),
 * plus the fire-and-forget task runner ([DetachedTasks], [DefaultProcessTerminator]).
 *
 * Opt-in (inventory, payment): `@Import(NestWebConfiguration::class)` on the application class; the service provides
 * a [DaprSubscriptions] bean and any further scalars (payment: `PaymentScalarConfiguration`).
 */
@Configuration(proxyBeanMethods = false)
class NestWebConfiguration {

    @Bean
    fun defaultProcessTerminator(@Value(DefaultProcessTerminator.EXIT_PROPERTY) exit: Boolean) = DefaultProcessTerminator(exit)

    @Bean
    fun detachedTasks(terminator: ProcessTerminator) = DetachedTasks(terminator)

    @Bean
    fun rawAuthorizedUserContextFactory(): SpringGraphQLContextFactory = RawAuthorizedUserContextFactory()

    @Bean
    fun nestDataFetcherExceptionHandler(): DataFetcherExceptionHandler = NestDataFetcherExceptionHandler()

    @Bean
    fun entityErrorInstrumentation(): Instrumentation = EntityErrorInstrumentation()

    @Bean
    fun uuidScalarMapping() = ScalarMapping(Uuid::class, UuidScalar.INSTANCE)

    /**
     * Apollo answers `"data": null` when execution nulled the root; graphql-kotlin omits `data` (NON_NULL).
     */
    @Bean
    fun graphQLResponseDataAlways() = JsonMapperBuilderCustomizer {
        it.addMixIn(GraphQLResponse::class.java, GraphQLResponseMixin::class.java)
    }

    @Bean
    fun nestRoutes(subscriptions: DaprSubscriptions): RouterFunction<ServerResponse> = coRouter {
        GET("/health") {
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait("""{"status":"OK"}""")
        }
        GET("/dapr/subscribe") {
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(subscriptions.json)
        }
    }
}

/** Mix-in for [GraphQLResponse]: always write `data` (also when null). */
abstract class GraphQLResponseMixin {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    abstract fun getData(): Any?
}

/** The service's `GET /dapr/subscribe` answer, verbatim. */
class DaprSubscriptions(val json: String)

/** Helpers for the event/REST handlers (NestJS controller semantics). */
object NestRest {

    private val logger = LoggerFactory.getLogger(NestRest::class.java)
    val mapper: JsonMapper = JsonMapper.builder().build()

    /** `@Body()` of a JSON request: empty body → `{}`. Invalid JSON → Express' 400. */
    fun parseBody(body: String?): JsonNode {
        if (body.isNullOrBlank()) return Js.nodes.objectNode()
        return try {
            mapper.readTree(body)
        } catch (e: Exception) {
            throw HttpException(400, "Bad Request", linkedMapOf("message" to (e.message ?: "Bad Request"), "error" to "Bad Request", "statusCode" to 400))
        }
    }

    /** `201` with an empty body (a Nest `@Post` handler that returns `undefined`). */
    fun created(): ResponseEntity<String> = ResponseEntity.status(HttpStatus.CREATED).build()

    /** `201` with a JSON body (the handler returned a value). */
    fun created(body: Any): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body))

    /** Nest's exception filter: HttpException → its status and response, anything else → 500. */
    fun error(e: Throwable): ResponseEntity<String> {
        val cause = NestGraphQLErrors.unwrap(e)
        return if (cause is HttpException) {
            ResponseEntity.status(cause.status).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(cause.response))
        } else {
            logger.error("Unhandled error in REST handler", cause)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body("""{"statusCode":500,"message":"Internal server error"}""")
        }
    }

    /** Runs a handler with Nest's error mapping. */
    suspend fun handle(block: suspend () -> ResponseEntity<String>): ResponseEntity<String> = try {
        block()
    } catch (e: Throwable) {
        error(e)
    }
}
