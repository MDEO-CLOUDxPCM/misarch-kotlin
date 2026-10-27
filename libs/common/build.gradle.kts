// Code shared by all services: GraphQL federation setup, Authorized-User handling, connections/pagination,
// id data loaders, persistence (R2DBC naming, SQL conditions, Flyway), Dapr publish/subscribe/invoke helpers,
// CloudEvents, experiment-config-sidecar variables. Test fixtures: Postgres container, fake Dapr sidecar,
// SDL canonicalizer/comparison.
plugins {
    id("misarch.library-conventions")
}

dependencies {
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.actuator)
    api(libs.spring.boot.starter.data.r2dbc)
    api(libs.jackson.module.kotlin)
    api(libs.kotlin.reflect)
    api(libs.kotlinx.coroutines.reactor)
    api(libs.reactor.kotlin.extensions)
    api(libs.graphql.kotlin.spring.server)
    api(libs.graphql.java)
    api(libs.java.dataloader)
    api(libs.graphql.java.extended.scalars)
    api(libs.dapr.sdk.springboot)
    api(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    api(libs.r2dbc.postgresql)
    // timing spans (org.misarch.common.timing): the Java agent bridges this API copy to its SDK
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.extension.kotlin)

    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.spring.boot.starter.webflux.test)
    testFixturesApi(libs.spring.boot.starter.data.r2dbc.test)
    testFixturesApi(libs.spring.boot.testcontainers)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.mockk)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.reactor.test)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.opentelemetry.sdk.testing)
}
