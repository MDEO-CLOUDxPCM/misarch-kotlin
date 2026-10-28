package org.misarch.common

import org.misarch.common.event.DaprConfiguration
import org.misarch.common.graphql.FederatedTracingConfiguration
import org.misarch.common.graphql.GraphQLConfiguration
import org.misarch.common.mechanisms.EntityCacheConfiguration
import org.misarch.common.persistence.PersistenceConfiguration
import org.misarch.common.timing.TimingSpansConfiguration
import org.misarch.common.timing.counters.TimingCountersConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Import

/**
 * Entry point of libs/common (registered in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`): every service gets the shared
 * GraphQL, persistence and Dapr setup without component-scanning `org.misarch.common`.
 * Runs before graphql-kotlin's auto-configuration so that our hooks/context factory win. The experiment-config
 * endpoints are a separate auto-configuration ([org.misarch.common.ecs.EcsAutoConfiguration]), so a service without
 * GraphQL/persistence (simulation, which excludes this one) can use them too.
 */
@AutoConfiguration(
    beforeName = ["com.expediagroup.graphql.server.spring.GraphQLAutoConfiguration"],
    afterName = [
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
        "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration",
    ],
)
@Import(
    GraphQLConfiguration::class, PersistenceConfiguration::class, DaprConfiguration::class,
    FederatedTracingConfiguration::class, ClockConfiguration::class, TimingSpansConfiguration::class,
    TimingCountersConfiguration::class, EntityCacheConfiguration::class,
)
class MisarchCommonAutoConfiguration
