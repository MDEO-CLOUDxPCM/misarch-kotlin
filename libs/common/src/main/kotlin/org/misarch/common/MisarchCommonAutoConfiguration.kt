package org.misarch.common

import org.misarch.common.ecs.EcsVariable
import org.misarch.common.ecs.ExperimentConfigController
import org.misarch.common.event.DaprConfiguration
import org.misarch.common.graphql.GraphQLConfiguration
import org.misarch.common.graphql.LegacyFederatedTracingConfiguration
import org.misarch.common.persistence.PersistenceConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Entry point of libs/common (registered in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`): every service gets the shared
 * GraphQL, persistence and Dapr setup without component-scanning `org.misarch.common`.
 * Runs before graphql-kotlin's auto-configuration so that our hooks/context factory win.
 */
@AutoConfiguration(
    beforeName = ["com.expediagroup.graphql.server.spring.GraphQLAutoConfiguration"],
    afterName = [
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
    ],
)
@Import(
    GraphQLConfiguration::class, PersistenceConfiguration::class, DaprConfiguration::class,
    LegacyFederatedTracingConfiguration::class,
)
class MisarchCommonAutoConfiguration {

    /** Experiment-config-sidecar endpoints, only if the service declares variables. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(EcsVariable::class)
    class EcsConfiguration {
        @Bean
        fun experimentConfigController(variables: List<EcsVariable>) = ExperimentConfigController(variables)
    }
}
