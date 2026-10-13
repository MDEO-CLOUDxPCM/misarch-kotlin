plugins {
    id("misarch.service-conventions")
}

// all shared dependencies come from libs/common (see misarch.service-conventions)

tasks.withType<Test>().configureEach {
    // the service containers run with the JVM default time zone UTC (eclipse-temurin images); timestamps read from
    // Postgres (r2dbc-postgresql) carry the JVM's offset, which is observable in GraphQL results and event payloads
    jvmArgs("-Duser.timezone=UTC")
}
