package org.misarch.common.config

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Default properties of every MiSArch service (lowest precedence: `application.yaml`, env vars and system
 * properties override them):
 * - actuator under `/` -> health endpoint `GET /health` answering `{"status":"UP"}` (compose/k8s health checks),
 * - GraphQL federation on, all `org.misarch` packages scanned for schema types,
 * - Flyway migrations from `classpath:db/migration`,
 * - Dapr sidecar endpoint from the Dapr env vars (`DAPR_HTTP_ENDPOINT`, `DAPR_HTTP_PORT`),
 * - Jackson: a JSON `null`/missing value for a primitive (`Int`, `Boolean`, ...) becomes its default instead of an
 *   error (Jackson 2 behaviour of the originals; Jackson 3 fails by default and would reject such events with 400).
 */
class MisarchDefaultsEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        environment.propertySources.addLast(MapPropertySource(SOURCE_NAME, DEFAULTS))
    }

    companion object {
        const val SOURCE_NAME = "misarchDefaults"

        val DEFAULTS: Map<String, Any> = mapOf(
            "spring.main.allow-bean-definition-overriding" to "true",
            "management.endpoints.web.base-path" to "/",
            // Boot 4 adds liveness/readiness groups by default; the originals answer exactly {"status":"UP"}
            "management.endpoint.health.probes.enabled" to "false",
            "graphql.packages" to "org.misarch",
            "graphql.federation.enabled" to "true",
            "spring.flyway.locations" to "classpath:db/migration",
            "misarch.dapr.http-endpoint" to "\${DAPR_HTTP_ENDPOINT:http://localhost:\${DAPR_HTTP_PORT:3500}}",
            "misarch.dapr.pubsub-name" to "pubsub",
            "spring.jackson.deserialization.fail-on-null-for-primitives" to "false",
        )
    }
}
