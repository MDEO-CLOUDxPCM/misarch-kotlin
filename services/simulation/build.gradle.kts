plugins {
    id("misarch.service-conventions")
}

// RabbitMQ through the plain Java client (docs/specs/simulation.md §6): manual ack, unlimited prefetch, explicit
// reconnect at the minute boundary.
dependencies {
    implementation(libs.rabbitmq.amqp.client)
}
